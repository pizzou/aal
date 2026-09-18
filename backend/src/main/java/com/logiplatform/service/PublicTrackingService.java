package com.logiplatform.service;

import com.logiplatform.dto.PublicTrackingDtos.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Token-authorized customer visibility.
 *
 * Public tracking exposes only shipment information associated with the
 * supplied tracking token. No tenant-wide or internal identifiers are exposed.
 */
@Service
public class PublicTrackingService {

        private final JdbcTemplate db;

        public PublicTrackingService(
                        @Qualifier("authJdbcTemplate") JdbcTemplate db) {
                this.db = db;
        }

        public PublicShipmentView findByToken(UUID token) {

                PublicShipmentView base;

                try {
                        base = db.queryForObject(
                                        """
                                                        SELECT
                                                            reference_code,
                                                            origin_address,
                                                            destination_address,
                                                            status,
                                                            transport_mode,
                                                            carrier_name,
                                                            carrier_reference_number,
                                                            eta
                                                        FROM shipments
                                                        WHERE tracking_token = ?
                                                          AND tracking_revoked = false
                                                          AND (tracking_expires_at IS NULL OR tracking_expires_at > now())
                                                        """,
                                        (rs, n) -> new PublicShipmentView(
                                                        rs.getString("reference_code"),
                                                        rs.getString("origin_address"),
                                                        rs.getString("destination_address"),
                                                        rs.getString("status"),
                                                        rs.getString("transport_mode"),
                                                        rs.getString("carrier_name"),
                                                        rs.getString("carrier_reference_number"),
                                                        rs.getTimestamp("eta") == null
                                                                        ? null
                                                                        : rs.getTimestamp("eta").toInstant(),
                                                        List.of(),
                                                        List.of(),
                                                        null),
                                        token);

                } catch (EmptyResultDataAccessException e) {
                        throw new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "Tracking link not found");
                }

                List<PublicTrackingEvent> events = db.query(
                                """
                                                SELECT
                                                    e.event_type,
                                                    e.location,
                                                    e.notes,
                                                    e.occurred_at
                                                FROM shipment_tracking_events e
                                                JOIN shipments s
                                                    ON s.id = e.shipment_id
                                                WHERE s.tracking_token = ?
                                                ORDER BY e.occurred_at ASC
                                                """,
                                (rs, n) -> new PublicTrackingEvent(
                                                rs.getString("event_type"),
                                                rs.getString("location"),
                                                rs.getString("notes"),
                                                rs.getTimestamp("occurred_at").toInstant()),
                                token);

                /*
                 * IMPORTANT:
                 * Both cargo_documents and shipments contain a "status" column.
                 * Qualify every selected column from cargo_documents so PostgreSQL
                 * cannot interpret "status" as ambiguous.
                 */
                List<PublicDocument> documents = db.query(
                                """
                                                SELECT
                                                    d.document_type,
                                                    d.status,
                                                    d.created_at
                                                FROM cargo_documents d
                                                JOIN shipments s
                                                    ON s.id = d.shipment_id
                                                WHERE s.tracking_token = ?
                                                ORDER BY d.created_at DESC
                                                """,
                                (rs, n) -> new PublicDocument(
                                                rs.getString("document_type"),
                                                rs.getString("status"),
                                                rs.getTimestamp("created_at").toInstant()),
                                token);

                documents = new ArrayList<>(documents);

                documents.addAll(
                                db.query(
                                                """
                                                                SELECT
                                                                    d.declaration_type,
                                                                    d.status,
                                                                    COALESCE(d.submitted_at, now())
                                                                FROM customs_declarations d
                                                                JOIN shipments s
                                                                    ON s.id = d.shipment_id
                                                                WHERE s.tracking_token = ?
                                                                ORDER BY d.submitted_at DESC NULLS LAST
                                                                """,
                                                (rs, n) -> new PublicDocument(
                                                                rs.getString("declaration_type"),
                                                                rs.getString("status"),
                                                                rs.getTimestamp(3).toInstant()),
                                                token));

                PublicPod pod = db.query(
                                """
                                                SELECT
                                                    p.recipient_name,
                                                    p.delivered_at,
                                                    (
                                                        p.signature_uri IS NOT NULL
                                                        OR p.photo_uri IS NOT NULL
                                                    ) AS evidence_available
                                                FROM proof_of_delivery p
                                                JOIN shipments s
                                                    ON s.id = p.shipment_id
                                                WHERE s.tracking_token = ?
                                                ORDER BY p.delivered_at DESC
                                                LIMIT 1
                                                """,
                                rs -> rs.next()
                                                ? new PublicPod(
                                                                rs.getString("recipient_name"),
                                                                rs.getTimestamp("delivered_at").toInstant(),
                                                                rs.getBoolean("evidence_available"))
                                                : null,
                                token);

                return new PublicShipmentView(
                                base.referenceCode(),
                                base.originAddress(),
                                base.destinationAddress(),
                                base.status(),
                                base.transportMode(),
                                base.carrierName(),
                                base.carrierReferenceNumber(),
                                base.eta(),
                                events,
                                documents,
                                pod);
        }
}
