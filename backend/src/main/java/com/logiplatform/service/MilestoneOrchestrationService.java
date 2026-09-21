package com.logiplatform.service;

import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class MilestoneOrchestrationService {
    private final JdbcTemplate db;
    private final OperationsEventStreamService events;
    public MilestoneOrchestrationService(@Qualifier("tenantJdbcTemplate") JdbcTemplate db, OperationsEventStreamService events){this.db=db;this.events=events;}

    @Transactional
    public void applyShipmentStatus(UUID shipmentId, String shipmentStatus) {
        UUID tenant=TenantContext.getTenantId();
        String code=switch((shipmentStatus==null?"":shipmentStatus).toUpperCase(Locale.ROOT)){
            case "BOOKED" -> "BOOKED"; case "PICKED_UP" -> "PICKED_UP"; case "DEPARTED" -> "DEPARTED_ORIGIN";
            case "IN_TRANSIT" -> "IN_TRANSIT"; case "ARRIVED" -> "ARRIVED_DESTINATION"; case "CUSTOMS" -> "CUSTOMS_CLEARED";
            case "OUT_FOR_DELIVERY" -> "OUT_FOR_DELIVERY"; case "DELIVERED","COMPLETED" -> "DELIVERED"; default -> null;
        };
        if(code==null) return;
        db.update("UPDATE shipment_milestones SET actual_at=COALESCE(actual_at,now()),status='COMPLETED',updated_at=now() WHERE tenant_id=? AND shipment_id=? AND milestone_code=?",tenant,shipmentId,code);
        events.publish(tenant,"milestone",Map.of("shipmentId",shipmentId,"code",code,"status","COMPLETED"));
    }

    @Transactional
    public void initialize(UUID shipmentId, String mode, String origin, String destination) {
        UUID tenant=TenantContext.getTenantId();
        List<String> codes=switch((mode==null?"ROAD":mode).toUpperCase(Locale.ROOT)){
            case "AIR" -> List.of("BOOKED","AWB_ISSUED","DEPARTED_ORIGIN","ARRIVED_DESTINATION","CUSTOMS_CLEARED","OUT_FOR_DELIVERY","DELIVERED");
            case "SEA" -> List.of("BOOKED","CONTAINER_ASSIGNED","GATE_IN","VESSEL_DEPARTED","VESSEL_ARRIVED","CUSTOMS_CLEARED","DELIVERED");
            case "RAIL" -> List.of("BOOKED","PICKED_UP","RAIL_DEPARTED","RAIL_ARRIVED","CUSTOMS_CLEARED","DELIVERED");
            default -> List.of("BOOKED","PICKED_UP","DEPARTED_ORIGIN","IN_TRANSIT","ARRIVED_DESTINATION","OUT_FOR_DELIVERY","DELIVERED");
        };
        for(int i=0;i<codes.size();i++){
            String code=codes.get(i); String name=code.replace('_',' ');
            db.update("INSERT INTO shipment_milestones(id,tenant_id,shipment_id,milestone_code,milestone_name,sequence_no,status,actual_at,source,notes) VALUES(gen_random_uuid(),?,?,?,?,?, ?, CASE WHEN ?='COMPLETED' THEN now() ELSE NULL END, 'SYSTEM', ?) ON CONFLICT(tenant_id,shipment_id,milestone_code) DO NOTHING",
                    tenant,shipmentId,code,name,i, i==0?"COMPLETED":"PLANNED", i==0?"COMPLETED":"PLANNED", "ORIGIN="+(origin==null?"":origin)+";DESTINATION="+(destination==null?"":destination));
        }
        events.publish(tenant,"milestones-initialized",Map.of("shipmentId",shipmentId,"mode",mode,"count",codes.size()));
    }
}
