package com.logiplatform.service;

import com.logiplatform.model.CommercialInvoice;
import com.logiplatform.security.TenantPrincipal;
import com.logiplatform.tenancy.TenantContext;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

@Service
public class FinancialHardeningService {
    private final JdbcTemplate db;
    private final MailService mail;
    private final FinancePostingService posting;
    private final FinancialDocumentArchiveService archive;
    private final FinancialDocumentService documents;

    @Value("${app.company.name:Aviation Africa Logistics Ltd}")
    private String companyName;

    @Value("${app.single-tenant.id:}")
    private String scheduledTenantId;

    public FinancialHardeningService(
            @Qualifier("tenantJdbcTemplate") JdbcTemplate db,
            MailService mail,
            FinancePostingService posting,
            FinancialDocumentArchiveService archive,
            FinancialDocumentService documents) {
        this.db=db; this.mail=mail; this.posting=posting; this.archive=archive; this.documents=documents;
    }

    @Transactional
    public Map<String,Object> transitionInvoice(UUID invoiceId, String target, String reason) {
        UUID tenant=tenant();
        Map<String,Object> row=one("""
                SELECT lifecycle_status,invoice_no,amount_paid,invoice_amount,credit_note_amount,debit_note_amount,
                       currency,due_date
                  FROM commercial_invoices WHERE tenant_id=? AND id=? FOR UPDATE
                """,tenant,invoiceId);
        String current=Objects.toString(row.get("lifecycle_status"),"ISSUED").toUpperCase(Locale.ROOT);
        String next=normalizeStatus(target);
        if(!allowed(current,next)) throw bad("Invalid invoice lifecycle transition: "+current+" -> "+next);
        BigDecimal paid=n(row.get("amount_paid"));
        BigDecimal total=n(row.get("invoice_amount")).add(n(row.get("debit_note_amount")))
                .subtract(n(row.get("credit_note_amount")));
        LocalDate due=row.get("due_date") instanceof java.sql.Date d ? d.toLocalDate()
                : row.get("due_date") instanceof LocalDate d ? d : null;
        if("PAID".equals(next) && paid.compareTo(total)<0) throw bad("Invoice cannot be marked PAID while a balance remains");
        if("PARTIALLY_PAID".equals(next) && (paid.signum()<=0 || paid.compareTo(total)>=0)) throw bad("PARTIALLY_PAID requires a positive remaining balance");
        if("OVERDUE".equals(next) && (due==null || !due.isBefore(LocalDate.now()) || paid.compareTo(total)>=0)) throw bad("OVERDUE requires an unpaid invoice past its due date");

        UUID user=currentUser();
        if ("DRAFT".equals(current) && "ISSUED".equals(next)) {
            posting.postInvoice(tenant, invoiceId, n(row.get("invoice_amount")),
                    Objects.toString(row.get("currency"),"USD"), Objects.toString(row.get("invoice_no"),""));
        }
        db.update("UPDATE commercial_invoices SET lifecycle_status=? WHERE tenant_id=? AND id=?",next,tenant,invoiceId);
        db.update("""
                INSERT INTO commercial_invoice_lifecycle_history
                    (id,tenant_id,invoice_id,from_status,to_status,reason,changed_by)
                VALUES(?,?,?,?,?,?,?)
                """,UUID.randomUUID(),tenant,invoiceId,current,next,blank(reason)?null:reason,user);
        return one("SELECT id,invoice_no,lifecycle_status,issue_date,due_date,invoice_amount,amount_paid FROM commercial_invoices WHERE tenant_id=? AND id=?",tenant,invoiceId);
    }



    @Transactional(readOnly=true)
    public List<Map<String,Object>> lifecycleHistory(UUID invoiceId){
        invoiceExists(invoiceId);
        return db.queryForList("""
            SELECT from_status,to_status,reason,changed_by,changed_at
              FROM commercial_invoice_lifecycle_history
             WHERE tenant_id=? AND invoice_id=?
             ORDER BY changed_at ASC
            """,tenant(),invoiceId);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> statement(String client, LocalDate from, LocalDate to) {
        String customer=requiredClient(client);
        LocalDate start=from==null?LocalDate.of(2000,1,1):from;
        LocalDate end=to==null?LocalDate.now():to;
        if(end.isBefore(start)) throw bad("Statement end date must not be before start date");

        UUID tenant=tenant();
        List<Map<String,Object>> invoices=db.queryForList("""
                SELECT id,invoice_no,issue_date,due_date,currency,invoice_amount,amount_paid,
                       GREATEST(invoice_amount+debit_note_amount-credit_note_amount-amount_paid,0) balance,lifecycle_status
                  FROM commercial_invoices
                 WHERE tenant_id=? AND client=? AND issue_date BETWEEN ? AND ?
                 ORDER BY issue_date,invoice_no
                """,tenant,customer,start,end);
        List<Map<String,Object>> payments=db.queryForList("""
                SELECT p.id,p.created_at,p.amount,p.currency,p.reference,i.invoice_no
                  FROM commercial_payments p
                  JOIN commercial_invoices i ON i.id=p.invoice_id AND i.tenant_id=p.tenant_id
                 WHERE p.tenant_id=? AND i.client=? AND p.created_at::date BETWEEN ? AND ?
                 ORDER BY p.created_at,p.id
                """,tenant,customer,start,end);

        Map<String,BigDecimal> balances=new LinkedHashMap<>();
        for(Map<String,Object> r:invoices) balances.merge(Objects.toString(r.get("currency"),"USD"),
                n(r.get("balance")),BigDecimal::add);
        return Map.of("client",customer,"from",start,"to",end,"invoices",invoices,"payments",payments,"outstandingByCurrency",balances);
    }

    @Transactional(readOnly=true)
    public byte[] statementPdf(String client, LocalDate from, LocalDate to) {
        Map<String,Object> data=statement(client,from,to);
        @SuppressWarnings("unchecked") List<Map<String,Object>> invoices=(List<Map<String,Object>>)data.get("invoices");
        @SuppressWarnings("unchecked") List<Map<String,Object>> payments=(List<Map<String,Object>>)data.get("payments");
        try(PDDocument doc=new PDDocument(); ByteArrayOutputStream out=new ByteArrayOutputStream()){
            PDPage page=new PDPage(PDRectangle.A4); doc.addPage(page);
            try(PDPageContentStream c=new PDPageContentStream(doc,page)){
                float y=800;
                text(c,companyName,42,y,18,true);
                text(c,"CUSTOMER STATEMENT",350,y,14,true); y-=28;
                text(c,"Customer: "+client,42,y,10,true);
                text(c,"Period: "+data.get("from")+" to "+data.get("to"),350,y,9,false); y-=32;
                text(c,"INVOICES",42,y,10,true); y-=16;
                for(Map<String,Object> r:invoices){
                    if(y<110)break;
                    String line=Objects.toString(r.get("invoice_no"),"")+"  "+
                            Objects.toString(r.get("issue_date"),"")+"  "+
                            Objects.toString(r.get("currency"),"")+" "+
                            money(n(r.get("invoice_amount")))+"  Balance "+
                            money(n(r.get("balance")));
                    text(c,truncate(line,105),42,y,8,false); y-=13;
                }
                y-=10; text(c,"PAYMENTS",42,y,10,true); y-=16;
                for(Map<String,Object> r:payments){
                    if(y<80)break;
                    String line=Objects.toString(r.get("invoice_no"),"")+"  "+
                            Objects.toString(r.get("created_at"),"")+"  "+
                            Objects.toString(r.get("currency"),"")+" "+
                            money(n(r.get("amount")))+"  "+Objects.toString(r.get("reference"),"");
                    text(c,truncate(line,105),42,y,8,false); y-=13;
                }
                text(c,"Outstanding balances are calculated from the tenant's posted commercial invoices.",42,60,8,false);
            }
            doc.save(out);
            byte[] pdf=out.toByteArray();
            UUID source=UUID.nameUUIDFromBytes((tenant()+":"+client+":"+data.get("from")+":"+data.get("to")).getBytes());
            archive.archive("STATEMENT",source,"statement-"+safeFile(client)+".pdf","application/pdf",pdf);
            return pdf;
        }catch(Exception e){throw new IllegalStateException("Unable to generate customer statement PDF",e);}
    }

    public void emailStatement(String client, LocalDate from, LocalDate to, String recipientEmail) {
        String customer=requiredClient(client);
        String recipient=blank(recipientEmail)?db.query("""
                SELECT email FROM client_records WHERE tenant_id=? AND client_company=? AND email IS NOT NULL AND email <> ''
                 ORDER BY updated_at DESC NULLS LAST LIMIT 1
                """,rs->rs.next()?rs.getString(1):null,tenant(),customer):recipientEmail.trim();
        if(blank(recipient)) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"No customer email is configured");
        byte[] pdf=statementPdf(customer,from,to);
        mail.sendFinancialDocument(recipient,customer,"Customer statement - "+customer,
                "Attached is your customer account statement for the requested period.",
                "statement-"+safeFile(customer)+".pdf",pdf);
    }

    @Transactional
    public Map<String,Object> createFinanceNote(String noteType, UUID invoiceId, UUID shipmentId,
                                                 BigDecimal amount, String currency, String reason) {
        String type=normalizeNoteType(noteType);
        if(amount==null||amount.signum()<=0) throw bad("Note amount must be greater than zero");
        if(blank(reason)) throw bad("Note reason is required");
        UUID tenant=tenant();
        if(invoiceId!=null) invoiceExists(invoiceId);
        String noteNo=nextNoteNumber(type);
        UUID id=UUID.randomUUID();
        db.update("""
                INSERT INTO finance_notes(id,tenant_id,note_no,note_type,invoice_id,shipment_id,amount,currency,reason,status,created_by)
                VALUES(?,?,?,?,?,?,?,?,?,'DRAFT',?)
                """,id,tenant,noteNo,type,invoiceId,shipmentId,amount,currency.trim().toUpperCase(Locale.ROOT),reason,currentUser());
        return note(id);
    }

    @Transactional
    public Map<String,Object> approveFinanceNote(UUID noteId) {
        UUID tenant=tenant();
        Map<String,Object> n=one("SELECT * FROM finance_notes WHERE tenant_id=? AND id=? FOR UPDATE",tenant,noteId);
        if(!"DRAFT".equalsIgnoreCase(Objects.toString(n.get("status")))) throw bad("Only DRAFT finance notes may be approved");
        UUID creator=n.get("created_by") instanceof UUID u ? u : null;
        UUID approver=currentUser();
        if(creator!=null && creator.equals(approver)) throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,"A finance note requires approval by a different user");
        UUID invoiceId=(UUID)n.get("invoice_id");
        String invoiceNo=invoiceId==null?"":Objects.toString(one("SELECT invoice_no FROM commercial_invoices WHERE tenant_id=? AND id=?",tenant,invoiceId).get("invoice_no"),"");
        String type=Objects.toString(n.get("note_type"),"").toUpperCase(Locale.ROOT);
        BigDecimal amount=n(n.get("amount"));
        String currency=Objects.toString(n.get("currency"),"USD");
        String reason=Objects.toString(n.get("reason"),"");
        if(invoiceId!=null && "CREDIT".equals(type)){
            Map<String,Object> inv=one("SELECT invoice_amount,amount_paid,credit_note_amount,debit_note_amount FROM commercial_invoices WHERE tenant_id=? AND id=? FOR UPDATE",tenant,invoiceId);
            BigDecimal remaining=n(inv.get("invoice_amount")).add(n(inv.get("debit_note_amount")))
                    .subtract(n(inv.get("credit_note_amount"))).subtract(n(inv.get("amount_paid")));
            if(amount.compareTo(remaining)>0) throw bad("Credit note cannot exceed the invoice balance");
        }
        if("CREDIT".equals(type)) posting.postCreditNote(tenant,noteId,amount,currency,invoiceNo,reason);
        else posting.postDebitNote(tenant,noteId,amount,currency,invoiceNo,reason);

        if(invoiceId!=null){
            if("CREDIT".equals(type))
                db.update("UPDATE commercial_invoices SET credit_note_amount=credit_note_amount+? WHERE tenant_id=? AND id=?",amount,tenant,invoiceId);
            else
                db.update("UPDATE commercial_invoices SET debit_note_amount=debit_note_amount+? WHERE tenant_id=? AND id=?",amount,tenant,invoiceId);

            Map<String,Object> inv=one("SELECT lifecycle_status,invoice_amount,amount_paid,credit_note_amount,debit_note_amount FROM commercial_invoices WHERE tenant_id=? AND id=?",tenant,invoiceId);
            BigDecimal balance=n(inv.get("invoice_amount")).add(n(inv.get("debit_note_amount")))
                    .subtract(n(inv.get("credit_note_amount"))).subtract(n(inv.get("amount_paid")));
            String oldStatus=Objects.toString(inv.get("lifecycle_status"),"ISSUED");
            String newStatus=balance.signum()<=0?"PAID":"PARTIALLY_PAID";
            db.update("UPDATE commercial_invoices SET lifecycle_status=? WHERE tenant_id=? AND id=?",
                    newStatus,tenant,invoiceId);
            if(!oldStatus.equalsIgnoreCase(newStatus)){
                db.update("""
                    INSERT INTO commercial_invoice_lifecycle_history
                        (id,tenant_id,invoice_id,from_status,to_status,reason,changed_by)
                    VALUES(?,?,?,?,?,?,?)
                    """,UUID.randomUUID(),tenant,invoiceId,oldStatus,newStatus,
                    type+" note "+Objects.toString(n.get("note_no"),noteId.toString()),currentUser());
            }
        }

        db.update("UPDATE finance_notes SET status='APPROVED',approved_by=?,approved_at=now(),posted_at=now() WHERE tenant_id=? AND id=?",
                currentUser(),tenant,noteId);
        return note(noteId);
    }

    @Transactional
    public Map<String,Object> voidFinanceNote(UUID noteId,String reason) {
        if(blank(reason)) throw bad("Void reason is required");
        int n=db.update("UPDATE finance_notes SET status='VOID',voided_by=?,voided_at=now(),reason=reason||' | VOID: '||? WHERE tenant_id=? AND id=? AND status='DRAFT'",
                currentUser(),reason,tenant(),noteId);
        if(n==0) throw bad("Only a DRAFT finance note can be voided");
        return note(noteId);
    }

    @Transactional
    public Map<String,Object> taxJurisdiction(String code,String legalName,String registrationNo,
                                               String countryCode,String address,String prefix,String currency,boolean active) {
        if(blank(code)||blank(legalName)||blank(countryCode)) throw bad("Tax jurisdiction code, legal name and country are required");
        UUID t=tenant();
        String normalizedCode=code.trim().toUpperCase(Locale.ROOT);
        if(active){
            db.update("UPDATE finance_tax_jurisdictions SET active=false,updated_at=now() WHERE tenant_id=? AND active=true AND jurisdiction_code<>?",
                    t,normalizedCode);
        }
        db.update("""
                INSERT INTO finance_tax_jurisdictions
                    (id,tenant_id,jurisdiction_code,legal_name,tax_registration_no,country_code,address,invoice_prefix,currency,active)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(tenant_id,jurisdiction_code) DO UPDATE SET
                    legal_name=EXCLUDED.legal_name,tax_registration_no=EXCLUDED.tax_registration_no,
                    country_code=EXCLUDED.country_code,address=EXCLUDED.address,invoice_prefix=EXCLUDED.invoice_prefix,
                    currency=EXCLUDED.currency,active=EXCLUDED.active,updated_at=now()
                """,UUID.randomUUID(),t,normalizedCode,legalName,blank(registrationNo)?null:registrationNo,
                countryCode.trim().toUpperCase(Locale.ROOT),address,prefix,currency.trim().toUpperCase(Locale.ROOT),active);
        return one("SELECT * FROM finance_tax_jurisdictions WHERE tenant_id=? AND jurisdiction_code=?",t,normalizedCode);
    }

    @Scheduled(cron="${finance.overdue-reminder-cron:0 0 8 * * *}")
    public void overdueReminderTick() {
        UUID tenant;
        boolean contextSetHere=false;
        if (TenantContext.isSet()) {
            tenant=TenantContext.getTenantId();
        } else if (!blank(scheduledTenantId)) {
            tenant=UUID.fromString(scheduledTenantId.trim());
            TenantContext.setTenantId(tenant);
            contextSetHere=true;
        } else {
            return;
        }
        try {
        List<Map<String,Object>> due=db.queryForList("""
                SELECT i.id,i.invoice_no,i.client,i.due_date,i.currency,i.invoice_amount,i.amount_paid,
                       c.email
                  FROM commercial_invoices i
                  LEFT JOIN client_records c ON c.tenant_id=i.tenant_id AND c.client_company=i.client
                 WHERE i.tenant_id=? AND GREATEST(i.invoice_amount+i.debit_note_amount-i.credit_note_amount-i.amount_paid,0)>0
                   AND i.due_date<CURRENT_DATE
                   AND (i.next_follow_up IS NULL OR i.next_follow_up<=CURRENT_DATE)
                   AND i.lifecycle_status NOT IN ('VOID','CANCELLED')
                 ORDER BY i.due_date
                 LIMIT 100
                """,tenant);
        for(Map<String,Object> r:due){
            UUID id=(UUID)r.get("id"); String email=Objects.toString(r.get("email"),null);
            if(blank(email)) continue;
            try{
                byte[] pdf=documents.invoicePdf(id);
                mail.sendFinancialDocument(email,Objects.toString(r.get("client"),"Customer"),
                        "Overdue invoice "+r.get("invoice_no"),
                        "Invoice <strong>"+escape(Objects.toString(r.get("invoice_no"),""))+"</strong> is overdue. Please arrange payment at your earliest convenience.",
                        "invoice-"+safeFile(Objects.toString(r.get("invoice_no"),id.toString()))+".pdf",pdf);
                db.update("""
                        INSERT INTO finance_overdue_reminders(id,tenant_id,invoice_id,reminder_date,reminder_level,recipient_email,status)
                        VALUES(?,?,?,?,1,?,'SENT')
                        ON CONFLICT(tenant_id,invoice_id,reminder_date,reminder_level) DO NOTHING
                        """,UUID.randomUUID(),tenant,id,LocalDate.now(),email);
                db.update("UPDATE commercial_invoices SET last_follow_up=CURRENT_DATE,next_follow_up=CURRENT_DATE+7 WHERE tenant_id=? AND id=?",tenant,id);
                db.update("UPDATE commercial_invoices SET lifecycle_status='OVERDUE' WHERE tenant_id=? AND id=? AND lifecycle_status IN ('ISSUED','SENT')",tenant,id);
            }catch(Exception ex){
                db.update("""
                        INSERT INTO finance_overdue_reminders(id,tenant_id,invoice_id,reminder_date,reminder_level,recipient_email,status,error_detail)
                        VALUES(?,?,?,?,1,?,'FAILED',?)
                        ON CONFLICT(tenant_id,invoice_id,reminder_date,reminder_level)
                        DO UPDATE SET status='FAILED',error_detail=EXCLUDED.error_detail
                        """,UUID.randomUUID(),tenant,id,LocalDate.now(),email,truncate(ex.getMessage(),500));
            }
        }
        } finally {
            if (contextSetHere) TenantContext.clear();
        }
    }

    private void invoiceExists(UUID id){
        Boolean exists=db.query("SELECT EXISTS(SELECT 1 FROM commercial_invoices WHERE tenant_id=? AND id=?)",
                rs -> rs.next() && rs.getBoolean(1), tenant(), id);
        if(!Boolean.TRUE.equals(exists)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Invoice not found");
    }
    private Map<String,Object> note(UUID id){return one("SELECT * FROM finance_notes WHERE tenant_id=? AND id=?",tenant(),id);}
    private String nextNoteNumber(String type){
        String prefix="CREDIT".equals(type)?"AAL-CN-":"AAL-DN-";
        Integer next=db.queryForObject("SELECT COUNT(*)+1 FROM finance_notes WHERE tenant_id=? AND note_type=?",Integer.class,tenant(),type);
        return prefix+LocalDate.now().getYear()+"-"+String.format("%06d",next==null?1:next);
    }
    private Map<String,Object> one(String sql,Object... args){
        return db.query(sql,rs->{if(!rs.next())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Record not found");Map<String,Object> m=new LinkedHashMap<>();var md=rs.getMetaData();for(int i=1;i<=md.getColumnCount();i++)m.put(md.getColumnLabel(i),rs.getObject(i));return m;},args);
    }
    private String requiredClient(String client){if(blank(client))throw bad("Client is required");return client.trim();}
    private static String normalizeStatus(String s){if(blank(s))throw bad("Invoice status is required");String v=s.trim().toUpperCase(Locale.ROOT);return v.replace(' ','_');}
    private static String normalizeNoteType(String s){String v=normalizeStatus(s);if(!Set.of("CREDIT","DEBIT").contains(v))throw bad("Note type must be CREDIT or DEBIT");return v;}
    private static boolean allowed(String from,String to){
        return switch(from){
            case "DRAFT"->Set.of("ISSUED","CANCELLED").contains(to);
            case "ISSUED"->Set.of("SENT","PARTIALLY_PAID","PAID","OVERDUE","VOID","CANCELLED").contains(to);
            case "SENT"->Set.of("PARTIALLY_PAID","PAID","OVERDUE","VOID","CANCELLED").contains(to);
            case "PARTIALLY_PAID"->Set.of("PAID","OVERDUE").contains(to);
            case "OVERDUE"->Set.of("PARTIALLY_PAID","PAID").contains(to);
            default->false;
        };
    }
    private static BigDecimal n(Object v){return v instanceof BigDecimal b?b:v==null?BigDecimal.ZERO:new BigDecimal(v.toString());}
    private UUID tenant(){UUID t=TenantContext.getTenantId();if(t==null)throw new IllegalStateException("Tenant context is required");return t;}
    private UUID currentUser(){Object p=SecurityContextHolder.getContext().getAuthentication()==null?null:SecurityContextHolder.getContext().getAuthentication().getPrincipal();return p instanceof TenantPrincipal tp?tp.userId():null;}
    private static ResponseStatusException bad(String s){return new ResponseStatusException(HttpStatus.BAD_REQUEST,s);}
    private static boolean blank(String s){return s==null||s.isBlank();}
    private static String safeFile(String s){return s==null?"customer":s.replaceAll("[^A-Za-z0-9._-]+","_");}
    private static String escape(String s){return s==null?"":s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}
    private static String truncate(String s,int max){String v=s==null?"":s;return v.length()<=max?v:v.substring(0,Math.max(1,max-3))+"...";}
    private static String money(BigDecimal b){return b==null?"0.00":b.setScale(2,java.math.RoundingMode.HALF_UP).toPlainString();}
    private static void text(PDPageContentStream c,String value,float x,float y,float size,boolean bold)throws Exception{c.beginText();c.setFont(bold?PDType1Font.HELVETICA_BOLD:PDType1Font.HELVETICA,size);c.newLineAtOffset(x,y);c.showText(value==null?"":value);c.endText();}
    private record CommercialInvoiceProjection(UUID id,String invoiceNo){CommercialInvoice asEntityNotUsed(){return null;}}
}
