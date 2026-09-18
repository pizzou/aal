package com.logiplatform.service;

import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.logiplatform.dto.CommandCenterDtos.*;

/** Date-driven operational dispatch board built from the existing Shipment/Trip/TMS model. */
@Service
public class DailyOperationsService {

    private final JdbcTemplate jdbc;

    public DailyOperationsService(@Qualifier("reportingJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public DailyOperationsResponse operations(LocalDate date) {
        LocalDate selected = date == null ? LocalDate.now() : date;
        UUID tenant = TenantContext.getTenantId();
        if (tenant == null) throw new IllegalStateException("Tenant context is not available");

        return new DailyOperationsResponse(
                selected,
                summary(tenant, selected),
                fleet(tenant),
                jobs(tenant, selected),
                routes(tenant, selected),
                exceptions(tenant, selected));
    }

    private DailySummary summary(UUID tenant, LocalDate date) {
        return jdbc.queryForObject(
                """
                SELECT
                  COUNT(*) FILTER (WHERE job.day = ?::date) AS total_jobs,
                  COUNT(*) FILTER (WHERE job.day = ?::date AND job.scheduled) AS scheduled_jobs,
                  COUNT(*) FILTER (WHERE job.day = ?::date AND job.status='IN_TRANSIT') AS in_transit_jobs,
                  COUNT(*) FILTER (WHERE job.day = ?::date AND job.status IN ('DELIVERED','COMPLETED')) AS delivered_jobs,
                  COUNT(*) FILTER (WHERE job.day = ?::date AND job.delayed) AS delayed_jobs,
                  COUNT(*) FILTER (WHERE job.day = ?::date AND job.exception) AS exception_jobs,
                  COUNT(*) FILTER (WHERE job.day = ?::date AND job.unassigned) AS unassigned_jobs,
                  (SELECT COUNT(*) FROM trips t WHERE t.tenant_id=? AND t.scheduled_departure::date=?::date AND t.status<>'CANCELLED') AS route_count
                FROM (
                  SELECT s.status,
                         COALESCE(s.etd::date, s.date_opened, s.created_at::date) AS day,
                         EXISTS (SELECT 1 FROM trip_shipments ts JOIN trips t ON t.id=ts.trip_id WHERE ts.tenant_id=s.tenant_id AND ts.shipment_id=s.id AND t.status<>'CANCELLED') AS scheduled,
                         s.eta IS NOT NULL AND s.eta < (?::date + INTERVAL '1 day') AND s.status NOT IN ('DELIVERED','COMPLETED','CANCELLED') AS delayed,
                         (s.status='ON_HOLD' OR (s.eta IS NOT NULL AND s.eta < (?::date + INTERVAL '1 day') AND s.status NOT IN ('DELIVERED','COMPLETED','CANCELLED'))) AS exception,
                         NOT EXISTS (SELECT 1 FROM trip_shipments ts JOIN trips t ON t.id=ts.trip_id WHERE ts.tenant_id=s.tenant_id AND ts.shipment_id=s.id AND t.status IN ('PLANNED','IN_PROGRESS')) AS unassigned
                  FROM shipments s WHERE s.tenant_id=?
                ) job
                """,
                (rs, rowNum) -> new DailySummary(
                        rs.getInt("total_jobs"), rs.getInt("scheduled_jobs"), rs.getInt("in_transit_jobs"),
                        rs.getInt("delivered_jobs"), rs.getInt("delayed_jobs"), rs.getInt("exception_jobs"),
                        rs.getInt("unassigned_jobs"), rs.getInt("route_count")),
                date,date,date,date,date,date,date,tenant,date,date,date,tenant);
    }

    private FleetSummary fleet(UUID tenant) {
        return jdbc.queryForObject(
                """
                SELECT
                  (SELECT COUNT(*) FROM vehicles WHERE tenant_id=?) total_vehicles,
                  (SELECT COUNT(*) FROM vehicles WHERE tenant_id=? AND status='AVAILABLE') available_vehicles,
                  (SELECT COUNT(*) FROM vehicles WHERE tenant_id=? AND status='ON_TRIP') on_trip_vehicles,
                  (SELECT COUNT(*) FROM vehicles WHERE tenant_id=? AND status='MAINTENANCE') maintenance_vehicles,
                  (SELECT COUNT(*) FROM drivers WHERE tenant_id=?) total_drivers,
                  (SELECT COUNT(*) FROM drivers WHERE tenant_id=? AND status='AVAILABLE') available_drivers,
                  (SELECT COUNT(*) FROM drivers WHERE tenant_id=? AND status='ON_TRIP') on_trip_drivers
                """,
                (rs, rowNum) -> new FleetSummary(
                        rs.getInt("total_vehicles"), rs.getInt("available_vehicles"),
                        rs.getInt("on_trip_vehicles"), rs.getInt("maintenance_vehicles"),
                        rs.getInt("total_drivers"), rs.getInt("available_drivers"), rs.getInt("on_trip_drivers")),
                tenant,tenant,tenant,tenant,tenant,tenant,tenant);
    }

    private List<RoutingJob> jobs(UUID tenant, LocalDate date) {
        return jdbc.query(
                """
                SELECT s.id, s.reference_code, s.client_name, s.service_type, s.transport_mode,
                       COALESCE(NULLIF(s.origin_city_port,''),s.origin_address) origin,
                       COALESCE(NULLIF(s.destination_city_port,''),s.destination_address) destination,
                       s.status, s.etd, s.eta, s.carrier_name, s.carrier_reference_number,
                       d.full_name driver_name, v.registration_number vehicle_registration,
                       t.id trip_id,
                       CASE WHEN s.eta IS NOT NULL AND s.eta < (?::date + INTERVAL '1 day')
                                  AND s.status NOT IN ('DELIVERED','COMPLETED','CANCELLED') THEN 'URGENT'
                            WHEN s.status='ON_HOLD' THEN 'HIGH' ELSE 'NORMAL' END priority,
                       (s.eta IS NOT NULL AND s.eta < (?::date + INTERVAL '1 day')
                                  AND s.status NOT IN ('DELIVERED','COMPLETED','CANCELLED')) delayed,
                       (t.id IS NULL) unassigned,
                       s.next_action
                FROM shipments s
                LEFT JOIN LATERAL (
                    SELECT ts.trip_id FROM trip_shipments ts JOIN trips tx ON tx.id=ts.trip_id
                    WHERE ts.tenant_id=s.tenant_id AND ts.shipment_id=s.id AND tx.status IN ('PLANNED','IN_PROGRESS')
                    ORDER BY tx.scheduled_departure NULLS LAST, tx.created_at DESC LIMIT 1
                ) ts ON true
                LEFT JOIN trips t ON t.id=ts.trip_id AND t.tenant_id=s.tenant_id
                LEFT JOIN vehicles v ON v.id=t.vehicle_id AND v.tenant_id=s.tenant_id
                LEFT JOIN drivers d ON d.id=t.driver_id AND d.tenant_id=s.tenant_id
                WHERE s.tenant_id=?
                  AND COALESCE(s.etd::date,s.date_opened,s.created_at::date)=?::date
                ORDER BY CASE WHEN s.status='ON_HOLD' THEN 0 WHEN s.eta < now() THEN 1 ELSE 2 END, s.etd NULLS LAST, s.reference_code
                """,
                (rs,rowNum)->new RoutingJob(
                        UUID.fromString(rs.getString("id")),rs.getString("reference_code"),rs.getString("client_name"),rs.getString("service_type"),
                        rs.getString("transport_mode"),rs.getString("origin"),rs.getString("destination"),rs.getString("status"),
                        timestamp(rs.getTimestamp("etd")),timestamp(rs.getTimestamp("eta")),rs.getString("carrier_name"),rs.getString("carrier_reference_number"),
                        rs.getString("driver_name"),rs.getString("vehicle_registration"),uuid(rs.getString("trip_id")),rs.getString("priority"),
                        rs.getBoolean("delayed"),rs.getBoolean("unassigned"),rs.getString("next_action")),
                date,date,tenant,date);
    }

    private List<RouteSummary> routes(UUID tenant, LocalDate date) {
        return jdbc.query(
                """
                SELECT t.id,t.origin_address,t.destination_address,t.scheduled_departure,t.status,
                       d.full_name driver_name,v.registration_number vehicle_registration,
                       COUNT(ts.shipment_id) shipment_count,
                       COALESCE(array_agg(ts.shipment_id) FILTER (WHERE ts.shipment_id IS NOT NULL),'{}'::uuid[]) shipment_ids
                FROM trips t
                LEFT JOIN drivers d ON d.id=t.driver_id AND d.tenant_id=t.tenant_id
                LEFT JOIN vehicles v ON v.id=t.vehicle_id AND v.tenant_id=t.tenant_id
                LEFT JOIN trip_shipments ts ON ts.trip_id=t.id AND ts.tenant_id=t.tenant_id
                WHERE t.tenant_id=? AND t.scheduled_departure::date=?::date
                GROUP BY t.id,d.full_name,v.registration_number
                ORDER BY t.scheduled_departure NULLS LAST,t.created_at
                """,
                (rs,rowNum)->new RouteSummary(
                        UUID.fromString(rs.getString("id")),rs.getString("origin_address"),rs.getString("destination_address"),
                        timestamp(rs.getTimestamp("scheduled_departure")),rs.getString("status"),rs.getString("driver_name"),
                        rs.getString("vehicle_registration"),rs.getInt("shipment_count"),uuidArray(rs.getArray("shipment_ids"))),
                tenant,date);
    }

    private List<OperationalException> exceptions(UUID tenant, LocalDate date) {
        return jdbc.query(
                """
                SELECT 'DELAYED_SHIPMENT' type, 'HIGH' severity, s.reference_code reference,
                       'ETA has passed without delivery' message, s.id shipment_id, NULL::uuid trip_id
                FROM shipments s WHERE s.tenant_id=? AND COALESCE(s.etd::date,s.date_opened,s.created_at::date)=?::date
                  AND s.eta IS NOT NULL AND s.eta < (?::date + INTERVAL '1 day')
                  AND s.status NOT IN ('DELIVERED','COMPLETED','CANCELLED')
                UNION ALL
                SELECT 'UNASSIGNED_SHIPMENT','MEDIUM',s.reference_code,'Active shipment has no route assignment',s.id,NULL
                FROM shipments s WHERE s.tenant_id=? AND COALESCE(s.etd::date,s.date_opened,s.created_at::date)=?::date
                  AND s.status NOT IN ('DELIVERED','COMPLETED','CANCELLED')
                  AND NOT EXISTS (SELECT 1 FROM trip_shipments ts JOIN trips t ON t.id=ts.trip_id WHERE ts.tenant_id=s.tenant_id AND ts.shipment_id=s.id AND t.status IN ('PLANNED','IN_PROGRESS'))
                ORDER BY severity,type,reference
                """,
                (rs,rowNum)->new OperationalException(rs.getString("type"),rs.getString("severity"),rs.getString("reference"),rs.getString("message"),uuid(rs.getString("shipment_id")),uuid(rs.getString("trip_id"))),
                tenant,date,date,tenant,date);
    }

    private static Instant timestamp(java.sql.Timestamp value) { return value == null ? null : value.toInstant(); }
    private static UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }
    private static List<UUID> uuidArray(java.sql.Array array) throws java.sql.SQLException {
        if (array == null) return List.of();
        Object value=array.getArray();
        if (!(value instanceof Object[] values)) return List.of();
        java.util.ArrayList<UUID> out=new java.util.ArrayList<>(values.length);
        for(Object v:values) if(v!=null) out.add(UUID.fromString(v.toString()));
        return List.copyOf(out);
    }
}
