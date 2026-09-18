package com.logiplatform.service;

import com.logiplatform.dto.CustomerPortalDtos.*;
import com.logiplatform.security.TenantPrincipal;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class CustomerPortalService {
    private final JdbcTemplate db;
    public CustomerPortalService(@Qualifier("tenantJdbcTemplate") JdbcTemplate db){this.db=db;}

    private TenantPrincipal principal(){
        Object p=SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if(!(p instanceof TenantPrincipal tp) || !"CUSTOMER".equalsIgnoreCase(tp.role())) throw new AccessDeniedException("Customer access required");
        return tp;
    }
    private String clientId(){
        TenantPrincipal p=principal();
        String id=db.query("SELECT customer_client_id FROM users WHERE id=? AND tenant_id=? AND active=true",rs->rs.next()?rs.getString(1):null,p.userId(),TenantContext.getTenantId());
        if(id==null||id.isBlank()) throw new AccessDeniedException("Customer account is not linked to an organization");
        return id;
    }
    public Profile profile(){
        TenantPrincipal p=principal(); String cid=clientId();
        return db.queryForObject("SELECT u.id,email,customer_client_id,client_company,contact_person,phone,country,city FROM users u LEFT JOIN client_records c ON c.client_id=u.customer_client_id AND c.tenant_id=u.tenant_id WHERE u.id=? AND u.tenant_id=?",(rs,n)->new Profile(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8)),p.userId(),TenantContext.getTenantId());
    }
    public List<Shipment> shipments(){
        String cid=clientId();
        return db.query("SELECT id,reference_code,status,transport_mode,origin_address,destination_address,eta,carrier_name,tracking_token FROM shipments WHERE tenant_id=? AND client_name IN (SELECT client_company FROM client_records WHERE tenant_id=? AND client_id=?) ORDER BY created_at DESC",(rs,n)->new Shipment(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getTimestamp(7)==null?null:rs.getTimestamp(7).toInstant(),rs.getString(8),rs.getObject(9,UUID.class).toString()),TenantContext.getTenantId(),TenantContext.getTenantId(),cid);
    }
    public Shipment shipment(UUID id){
        String cid=clientId();
        return db.queryForObject("SELECT s.id,s.reference_code,s.status,s.transport_mode,s.origin_address,s.destination_address,s.eta,s.carrier_name,s.tracking_token FROM shipments s WHERE s.id=? AND s.tenant_id=? AND s.client_name IN (SELECT client_company FROM client_records WHERE tenant_id=? AND client_id=?)",(rs,n)->new Shipment(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getTimestamp(7)==null?null:rs.getTimestamp(7).toInstant(),rs.getString(8),rs.getObject(9,UUID.class).toString()),id,TenantContext.getTenantId(),TenantContext.getTenantId(),cid);
    }
    public List<Quote> quotes(){
        String cid=clientId(); return db.query("SELECT id,quote_id,quote_date,client,route,service_type,quoted_amount,currency,valid_until,status FROM commercial_quotes WHERE tenant_id=? AND client IN (SELECT client_company FROM client_records WHERE tenant_id=? AND client_id=?) ORDER BY quote_date DESC",(rs,n)->new Quote(rs.getObject(1,UUID.class),rs.getString(2),rs.getDate(3).toLocalDate(),rs.getString(4),rs.getString(5),rs.getString(6),rs.getBigDecimal(7),rs.getString(8),rs.getDate(9)==null?null:rs.getDate(9).toLocalDate(),rs.getString(10)),TenantContext.getTenantId(),TenantContext.getTenantId(),cid);
    }
    public List<Invoice> invoices(){
        String cid=clientId(); return db.query("SELECT i.id,i.invoice_no,i.shipment_id,i.invoice_amount,i.amount_paid,GREATEST(i.invoice_amount-COALESCE(i.amount_paid,0),0),i.currency,i.status,i.due_date FROM commercial_invoices i LEFT JOIN shipments s ON s.id=i.shipment_id WHERE i.tenant_id=? AND (s.client_name IN (SELECT client_company FROM client_records WHERE tenant_id=? AND client_id=?) OR i.client IN (SELECT client_company FROM client_records WHERE tenant_id=? AND client_id=?)) ORDER BY i.issue_date DESC",(rs,n)->new Invoice(rs.getObject(1,UUID.class),rs.getString(2),rs.getObject(3,UUID.class),rs.getBigDecimal(4),rs.getBigDecimal(5),rs.getBigDecimal(6),rs.getString(7),rs.getString(8),rs.getDate(9)==null?null:rs.getDate(9).toLocalDate()),TenantContext.getTenantId(),TenantContext.getTenantId(),cid,TenantContext.getTenantId(),cid);
    }
    public List<Event> events(UUID shipmentId){
        shipment(shipmentId);
        return db.query("SELECT e.id,e.event_type,e.location,e.notes,e.occurred_at FROM shipment_tracking_events e WHERE e.tenant_id=? AND e.shipment_id=? ORDER BY e.occurred_at ASC",(rs,n)->new Event(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getTimestamp(5).toInstant()),TenantContext.getTenantId(),shipmentId);
    }
    public List<Document> documents(UUID shipmentId){
        shipment(shipmentId); return db.query("SELECT id,shipment_id,document_type,document_uri,created_at FROM transport_leg_documents d JOIN transport_legs l ON l.id=d.leg_id WHERE d.tenant_id=? AND l.shipment_id=? AND d.customer_visible=true ORDER BY d.created_at DESC",(rs,n)->new Document(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4),rs.getTimestamp(5).toInstant()),TenantContext.getTenantId(),shipmentId);
    }
}
