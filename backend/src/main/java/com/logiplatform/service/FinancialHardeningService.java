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
            final float left=42f;
            PDPage[] currentPage={null};
            PDPageContentStream[] stream={null};
            float[] y={0f};
            Runnable closePage=()->{ try { if(stream[0]!=null) stream[0].close(); } catch(Exception ignored){} stream[0]=null; };
            java.util.function.Consumer<Boolean> newPage=(first)->{
                closePage.run();
                try {
                    currentPage[0]=new PDPage(PDRectangle.A4); doc.addPage(currentPage[0]);
                    stream[0]=new PDPageContentStream(doc,currentPage[0]); y[0]=800f;
                    text(stream[0],companyName,left,y[0],16,true); y[0]-=24;
                    text(stream[0],"CUSTOMER STATEMENT",left,y[0],12,true); y[0]-=18;
                    text(stream[0],"Customer: "+client,left,y[0],9,true); y[0]-=14;
                    text(stream[0],"Period: "+data.get("from")+" to "+data.get("to"),left,y[0],8,false); y[0]-=24;
                    if(first){text(stream[0],"INVOICES",left,y[0],9,true);y[0]-=15;}
                } catch(Exception ex){throw new IllegalStateException(ex);}
            };
            newPage.accept(true);
            for(Map<String,Object> r:invoices){
                if(y[0]<75){newPage.accept(false);text(stream[0],"INVOICES (continued)",left,y[0],9,true);y[0]-=15;}
                String line=Objects.toString(r.get("invoice_no"),"")+"  "+Objects.toString(r.get("issue_date"),"")+"  "+Objects.toString(r.get("currency"),"")+" "+money(n(r.get("invoice_amount")))+"  Paid "+money(n(r.get("amount_paid")))+"  Balance "+money(n(r.get("balance")));
                text(stream[0],truncate(line,125),left,y[0],7,false);y[0]-=12;
            }
            if(y[0]<100){newPage.accept(false);} else {y[0]-=12;}
            text(stream[0],"PAYMENTS",left,y[0],9,true);y[0]-=15;
            for(Map<String,Object> r:payments){
                if(y[0]<75){newPage.accept(false);text(stream[0],"PAYMENTS (continued)",left,y[0],9,true);y[0]-=15;}
                String line=Objects.toString(r.get("invoice_no"),"")+"  "+Objects.toString(r.get("created_at"),"")+"  "+Objects.toString(r.get("currency"),"")+" "+money(n(r.get("amount")))+"  "+Objects.toString(r.get("reference"),"");
                text(stream[0],truncate(line,125),left,y[0],7,false);y[0]-=12;
            }
            text(stream[0],"Outstanding balances are shown by currency in the accompanying statement data.",left,45,7,false);
            closePage.run(); doc.save(out);
            byte[] pdf=out.toByteArray();
            UUID source=UUID.nameUUIDFromBytes((tenant()+":"+client+":"+data.get("from")+":"+data.get("to")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
        if(invoiceId!=null){
            Map<String,Object> inv=one("SELECT currency,lifecycle_status,invoice_amount,amount_paid,credit_note_amount,debit_note_amount FROM commercial_invoices WHERE tenant_id=? AND id=? FOR UPDATE",tenant,invoiceId);
            String invoiceCurrency=Objects.toString(inv.get("currency"),"USD");
            if(!invoiceCurrency.equalsIgnoreCase(currency)) throw bad("Finance note currency must match the linked invoice currency");
            if(Set.of("VOID","CANCELLED","DRAFT").contains(Objects.toString(inv.get("lifecycle_status"),"ISSUED").toUpperCase(Locale.ROOT)))
                throw bad("Finance notes can only be posted against an issued invoice");
            BigDecimal gross=n(inv.get("invoice_amount")).add(n(inv.get("debit_note_amount"))).subtract(n(inv.get("credit_note_amount")));
            if("CREDIT".equals(type) && amount.compareTo(gross)>0) throw bad("Credit note cannot exceed the invoice gross amount");
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
            BigDecimal paidNow=n(inv.get("amount_paid"));
            LocalDate dueDate=db.query("SELECT due_date FROM commercial_invoices WHERE tenant_id=? AND id=?",rs->{java.sql.Date d=rs.next()?rs.getDate(1):null;return d==null?null:d.toLocalDate();},tenant,invoiceId);
            String newStatus=balance.signum()<=0?"PAID":paidNow.signum()>0?"PARTIALLY_PAID":
                    dueDate!=null&&dueDate.isBefore(LocalDate.now())?"OVERDUE":
                    Set.of("SENT","OVERDUE").contains(oldStatus.toUpperCase(Locale.ROOT))?oldStatus:"ISSUED";
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

    @Transactional(readOnly=true)
    public List<Map<String,Object>> taxRules(String jurisdictionCode, LocalDate onDate) {
        LocalDate effective=onDate==null?LocalDate.now():onDate;
        if(blank(jurisdictionCode)) throw bad("Jurisdiction code is required");
        return db.queryForList("""
            SELECT id,code,tax_name,rate,withholding_rate,currency,valid_from,valid_until,
                   jurisdiction_code,tax_type,inclusive,exemption_code,applies_to
              FROM finance_tax_rules
             WHERE tenant_id=? AND active=true AND jurisdiction_code=?
               AND valid_from<=? AND (valid_until IS NULL OR valid_until>=?)
             ORDER BY code
            """,tenant(),jurisdictionCode.trim().toUpperCase(Locale.ROOT),effective,effective);
    }

    @Transactional
    public Map<String,Object> calculateTax(String jurisdictionCode, String taxCode, BigDecimal amount,
                                            String currency, LocalDate onDate, String documentType, UUID documentId) {
        if(amount==null||amount.signum()<0) throw bad("Taxable amount must be zero or greater");
        if(blank(currency)||currency.trim().length()!=3) throw bad("A three-letter currency is required");
        LocalDate effective=onDate==null?LocalDate.now():onDate;
        Map<String,Object> rule=one("""
            SELECT * FROM finance_tax_rules WHERE tenant_id=? AND active=true AND jurisdiction_code=? AND code=?
              AND valid_from<=? AND (valid_until IS NULL OR valid_until>=?) FOR SHARE
            """,tenant(),jurisdictionCode.trim().toUpperCase(Locale.ROOT),taxCode.trim().toUpperCase(Locale.ROOT),effective,effective);
        String ruleCurrency=Objects.toString(rule.get("currency"),"");
        if(!blank(ruleCurrency)&&!ruleCurrency.equalsIgnoreCase(currency)) throw bad("Tax rule currency does not match document currency");
        BigDecimal rate=n(rule.get("rate")).divide(new BigDecimal("100"),10,java.math.RoundingMode.HALF_UP);
        boolean inclusive=Boolean.TRUE.equals(rule.get("inclusive"));
        BigDecimal base=amount;
        BigDecimal tax;
        if(inclusive){
            BigDecimal divisor=BigDecimal.ONE.add(rate);
            base=rate.signum()==0?amount:amount.divide(divisor,4,java.math.RoundingMode.HALF_UP);
            tax=amount.subtract(base).setScale(4,java.math.RoundingMode.HALF_UP);
        } else tax=amount.multiply(rate).setScale(4,java.math.RoundingMode.HALF_UP);
        BigDecimal withholding=base.multiply(n(rule.get("withholding_rate")).divide(new BigDecimal("100"),10,java.math.RoundingMode.HALF_UP)).setScale(4,java.math.RoundingMode.HALF_UP);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("jurisdictionCode",jurisdictionCode.trim().toUpperCase(Locale.ROOT)); result.put("taxCode",rule.get("code"));
        result.put("taxName",rule.get("tax_name")); result.put("effectiveDate",effective); result.put("currency",currency.toUpperCase(Locale.ROOT));
        result.put("inclusive",inclusive); result.put("taxableAmount",base); result.put("rate",n(rule.get("rate")));
        result.put("taxAmount",tax); result.put("withholdingRate",n(rule.get("withholding_rate"))); result.put("withholdingAmount",withholding);
        result.put("totalAmount",inclusive?amount:amount.add(tax)); result.put("netPayable",(inclusive?amount:amount.add(tax)).subtract(withholding));
        if(documentId!=null){
            if(blank(documentType)) throw bad("Document type is required when saving a tax snapshot");
            db.update("""
                INSERT INTO finance_tax_calculation_snapshots(tenant_id,document_type,document_id,tax_rule_id,tax_code,tax_name,taxable_amount,rate,tax_amount,currency,rule_snapshot)
                VALUES(?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb))
                """,tenant(),documentType.trim().toUpperCase(Locale.ROOT),documentId,rule.get("id"),rule.get("code"),rule.get("tax_name"),base,n(rule.get("rate")),tax,currency.toUpperCase(Locale.ROOT),
                "{\"jurisdictionCode\":\""+jurisdictionCode.trim().toUpperCase(Locale.ROOT)+"\",\"effectiveDate\":\""+effective+"\",\"inclusive\":"+inclusive+",\"rate\":"+n(rule.get("rate")).toPlainString()+"}");
        }
        return result;
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
            // Claim this invoice/day before sending. The unique key prevents parallel schedulers
            // from sending the same reminder twice; FAILED claims may be retried safely.
            int claimed=db.update("""
                INSERT INTO finance_overdue_reminders(id,tenant_id,invoice_id,reminder_date,reminder_level,recipient_email,status)
                VALUES(?,?,?,?,1,?,'PENDING')
                ON CONFLICT(tenant_id,invoice_id,reminder_date,reminder_level)
                DO UPDATE SET status='PENDING',recipient_email=EXCLUDED.recipient_email,error_detail=NULL,created_at=now()
                WHERE finance_overdue_reminders.status='FAILED'
                """,UUID.randomUUID(),tenant,id,LocalDate.now(),email);
            if(claimed==0) continue;
            try{
                byte[] pdf=documents.invoicePdf(id);
                mail.sendFinancialDocument(email,Objects.toString(r.get("client"),"Customer"),
                        "Overdue invoice "+r.get("invoice_no"),
                        "Invoice <strong>"+escape(Objects.toString(r.get("invoice_no"),""))+"</strong> is overdue. Please arrange payment at your earliest convenience.",
                        "invoice-"+safeFile(Objects.toString(r.get("invoice_no"),id.toString()))+".pdf",pdf);
                db.update("UPDATE finance_overdue_reminders SET status='SENT',error_detail=NULL WHERE tenant_id=? AND invoice_id=? AND reminder_date=? AND reminder_level=1 AND status='PENDING'",tenant,id,LocalDate.now());
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
        // Atomic tenant/year sequence: safe when multiple operators create notes concurrently.
        String documentType="CREDIT".equals(type)?"CREDIT_NOTE":"DEBIT_NOTE";
        int year=LocalDate.now().getYear();
        Long next=db.queryForObject("""
            INSERT INTO finance_document_sequences(id,tenant_id,document_type,fiscal_year,last_value,updated_at)
            VALUES(gen_random_uuid(),?,?,?,1,now())
            ON CONFLICT(tenant_id,document_type,fiscal_year)
            DO UPDATE SET last_value=finance_document_sequences.last_value+1,updated_at=now()
            RETURNING last_value
            """,Long.class,tenant(),documentType,year);
        String prefix="CREDIT".equals(type)?"AAL-CN-":"AAL-DN-";
        return prefix+year+"-"+String.format(Locale.ROOT,"%06d",next==null?1L:next);
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
