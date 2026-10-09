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
                SELECT lifecycle_status,invoice_no,amount_paid,invoice_amount,subtotal_amount,
                       tax_amount,credit_note_amount,debit_note_amount,currency,due_date
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
        BigDecimal remaining=total.subtract(paid).max(BigDecimal.ZERO);
        if("PAID".equals(next) && remaining.signum()>0) throw bad("Invoice cannot be marked PAID while a balance remains");
        if("PARTIALLY_PAID".equals(next) && (paid.signum()<=0 || remaining.signum()<=0)) throw bad("PARTIALLY_PAID requires a positive payment and a positive remaining balance");
        if("OVERDUE".equals(next) && (due==null || !due.isBefore(LocalDate.now()) || remaining.signum()<=0)) throw bad("OVERDUE requires an unpaid invoice past its due date");

        boolean voiding="VOID".equals(next)||"CANCELLED".equals(next);
        if(voiding && blank(reason)) throw bad("A reason is required to void or cancel an invoice");
        if(voiding && (paid.signum()!=0 || n(row.get("credit_note_amount")).signum()>0 || n(row.get("debit_note_amount")).signum()>0))
            throw bad("An invoice with payments or posted notes cannot be voided/cancelled; use a further credit/debit note or refund workflow");

        UUID user=currentUser();
        if ("DRAFT".equals(current) && "ISSUED".equals(next)) {
            BigDecimal tax=n(row.get("tax_amount"));
            if(tax.signum()>0) posting.postInvoiceWithTax(tenant,invoiceId,n(row.get("invoice_amount")),tax,
                    Objects.toString(row.get("currency"),"USD"),Objects.toString(row.get("invoice_no"),""));
            else posting.postInvoice(tenant, invoiceId, n(row.get("invoice_amount")),
                    Objects.toString(row.get("currency"),"USD"), Objects.toString(row.get("invoice_no"),""));
        } else if(voiding && !"DRAFT".equals(current)) {
            posting.reverseInvoice(tenant,invoiceId,Objects.toString(row.get("invoice_no"),""),reason);
        }
        db.update("UPDATE commercial_invoices SET lifecycle_status=? WHERE tenant_id=? AND id=?",next,tenant,invoiceId);
        db.update("""
                INSERT INTO commercial_invoice_lifecycle_history
                    (id,tenant_id,invoice_id,from_status,to_status,reason,changed_by)
                VALUES(?,?,?,?,?,?,?)
                """,UUID.randomUUID(),tenant,invoiceId,current,next,blank(reason)?null:reason.trim(),user);
        return one("SELECT id,invoice_no,lifecycle_status,issue_date,due_date,invoice_amount,amount_paid FROM commercial_invoices WHERE tenant_id=? AND id=?",tenant,invoiceId);
    }

    @Transactional(readOnly=true)
    public void validateInvoiceCanBeEmailed(UUID invoiceId) {
        Map<String,Object> row=one("SELECT lifecycle_status FROM commercial_invoices WHERE tenant_id=? AND id=?",tenant(),invoiceId);
        String status=Objects.toString(row.get("lifecycle_status"),"ISSUED").toUpperCase(Locale.ROOT);
        if(Set.of("DRAFT","VOID","CANCELLED").contains(status))
            throw bad("Only an issued invoice can be emailed; current status is " + status);
    }

    @Transactional
    public void markInvoiceSentAfterEmail(UUID invoiceId) {
        UUID t=tenant();
        Map<String,Object> row=one("SELECT lifecycle_status FROM commercial_invoices WHERE tenant_id=? AND id=? FOR UPDATE",t,invoiceId);
        String current=Objects.toString(row.get("lifecycle_status"),"ISSUED").toUpperCase(Locale.ROOT);
        if(!"ISSUED".equals(current)) return;
        db.update("UPDATE commercial_invoices SET lifecycle_status='SENT' WHERE tenant_id=? AND id=? AND lifecycle_status='ISSUED'",t,invoiceId);
        db.update("INSERT INTO commercial_invoice_lifecycle_history(id,tenant_id,invoice_id,from_status,to_status,reason,changed_by) VALUES(?,?,?,?,?,?,?)",
                UUID.randomUUID(),t,invoiceId,current,"SENT","Invoice emailed to customer",currentUser());
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
        UUID t=tenant();

        List<Map<String,Object>> invoices=db.queryForList("""
                SELECT id,invoice_no,issue_date,due_date,currency,subtotal_amount,tax_rate,tax_amount,tax_code,
                       invoice_amount,amount_paid,credit_note_amount,debit_note_amount,
                       CASE WHEN lifecycle_status IN ('VOID','CANCELLED','DRAFT') THEN 0
                            ELSE GREATEST(invoice_amount+debit_note_amount-credit_note_amount-amount_paid,0) END balance,
                       lifecycle_status
                  FROM commercial_invoices
                 WHERE tenant_id=? AND client=? AND issue_date BETWEEN ? AND ?
                   AND lifecycle_status NOT IN ('DRAFT','VOID','CANCELLED')
                 ORDER BY issue_date,invoice_no
                """,t,customer,start,end);
        List<Map<String,Object>> payments=db.queryForList("""
                SELECT p.id,p.created_at,p.amount,p.currency,p.reference,p.receipt_no,i.invoice_no
                  FROM commercial_payments p
                  JOIN commercial_invoices i ON i.id=p.invoice_id AND i.tenant_id=p.tenant_id
                 WHERE p.tenant_id=? AND i.client=? AND p.created_at::date BETWEEN ? AND ?
                 ORDER BY p.created_at,p.id
                """,t,customer,start,end);
        List<Map<String,Object>> notes=db.queryForList("""
                SELECT n.id,n.note_no,n.note_type,n.amount,n.currency,n.reason,
                       COALESCE(n.approved_at,n.created_at) AS effective_at,i.invoice_no
                  FROM finance_notes n
                  JOIN commercial_invoices i ON i.id=n.invoice_id AND i.tenant_id=n.tenant_id
                 WHERE n.tenant_id=? AND i.client=? AND n.status='APPROVED'
                   AND COALESCE(n.approved_at,n.created_at)::date BETWEEN ? AND ?
                 ORDER BY COALESCE(n.approved_at,n.created_at),n.note_no
                """,t,customer,start,end);

        List<Map<String,Object>> history=db.queryForList("""
            SELECT issue_date::date AS transaction_date,'INVOICE'::varchar AS transaction_type,
                   invoice_no AS reference,COALESCE('Invoice billed to '||client,'Invoice') AS description,
                   invoice_amount AS debit_amount,0::numeric AS credit_amount,currency
              FROM commercial_invoices
             WHERE tenant_id=? AND client=? AND issue_date BETWEEN ? AND ?
               AND lifecycle_status NOT IN ('DRAFT','VOID','CANCELLED')
            UNION ALL
            SELECT p.created_at::date,'PAYMENT'::varchar,COALESCE(p.receipt_no,p.reference,'PAYMENT'),
                   'Customer payment for '||i.invoice_no,0::numeric,p.amount,p.currency
              FROM commercial_payments p JOIN commercial_invoices i ON i.id=p.invoice_id AND i.tenant_id=p.tenant_id
             WHERE p.tenant_id=? AND i.client=? AND p.created_at::date BETWEEN ? AND ?
            UNION ALL
            SELECT COALESCE(n.approved_at,n.created_at)::date,
                   CASE WHEN n.note_type='CREDIT' THEN 'CREDIT_NOTE' ELSE 'DEBIT_NOTE' END,
                   n.note_no,n.reason,
                   CASE WHEN n.note_type='DEBIT' THEN n.amount ELSE 0::numeric END,
                   CASE WHEN n.note_type='CREDIT' THEN n.amount ELSE 0::numeric END,n.currency
              FROM finance_notes n JOIN commercial_invoices i ON i.id=n.invoice_id AND i.tenant_id=n.tenant_id
             WHERE n.tenant_id=? AND i.client=? AND n.status='APPROVED'
               AND COALESCE(n.approved_at,n.created_at)::date BETWEEN ? AND ?
            ORDER BY transaction_date,reference
            """,t,customer,start,end,t,customer,start,end,t,customer,start,end);

        Map<String,BigDecimal> opening=new TreeMap<>();
        for(Map<String,Object> r:db.queryForList("""
                SELECT currency,SUM(invoice_amount) amount FROM commercial_invoices
                 WHERE tenant_id=? AND client=? AND issue_date<? AND lifecycle_status NOT IN ('DRAFT','VOID','CANCELLED') GROUP BY currency
                """,t,customer,start)) opening.merge(Objects.toString(r.get("currency"),"USD"),n(r.get("amount")),BigDecimal::add);
        for(Map<String,Object> r:db.queryForList("""
                SELECT p.currency,SUM(p.amount) amount FROM commercial_payments p
                  JOIN commercial_invoices i ON i.id=p.invoice_id AND i.tenant_id=p.tenant_id
                 WHERE p.tenant_id=? AND i.client=? AND p.created_at::date<? GROUP BY p.currency
                """,t,customer,start)) opening.merge(Objects.toString(r.get("currency"),"USD"),n(r.get("amount")).negate(),BigDecimal::add);
        for(Map<String,Object> r:db.queryForList("""
                SELECT n.currency,SUM(CASE WHEN n.note_type='DEBIT' THEN n.amount ELSE -n.amount END) amount
                  FROM finance_notes n JOIN commercial_invoices i ON i.id=n.invoice_id AND i.tenant_id=n.tenant_id
                 WHERE n.tenant_id=? AND i.client=? AND n.status='APPROVED'
                   AND COALESCE(n.approved_at,n.created_at)::date<? GROUP BY n.currency
                """,t,customer,start)) opening.merge(Objects.toString(r.get("currency"),"USD"),n(r.get("amount")),BigDecimal::add);

        Map<String,BigDecimal> balances=new TreeMap<>();
        for(Map<String,Object> r:db.queryForList("""
                SELECT currency,SUM(GREATEST(invoice_amount+debit_note_amount-credit_note_amount-amount_paid,0)) balance
                  FROM commercial_invoices WHERE tenant_id=? AND client=? AND lifecycle_status NOT IN ('VOID','CANCELLED','DRAFT')
                 GROUP BY currency ORDER BY currency
                """,t,customer)) balances.put(Objects.toString(r.get("currency"),"USD"),n(r.get("balance")));
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("client",customer); result.put("from",start); result.put("to",end);
        result.put("invoices",invoices); result.put("payments",payments); result.put("notes",notes);
        result.put("transactionHistory",history); result.put("openingBalanceByCurrency",opening);
        result.put("outstandingByCurrency",balances);
        return result;
    }

    @Transactional(readOnly=true)
    public byte[] statementPdf(String client, LocalDate from, LocalDate to) {
        Map<String,Object> data=statement(client,from,to);
        @SuppressWarnings("unchecked") List<Map<String,Object>> history=(List<Map<String,Object>>)data.get("transactionHistory");
        @SuppressWarnings("unchecked") Map<String,BigDecimal> opening=(Map<String,BigDecimal>)data.get("openingBalanceByCurrency");
        @SuppressWarnings("unchecked") Map<String,BigDecimal> outstanding=(Map<String,BigDecimal>)data.get("outstandingByCurrency");
        try(PDDocument doc=new PDDocument(); ByteArrayOutputStream out=new ByteArrayOutputStream()){
            final float left=42f;
            PDPageContentStream[] stream={null};
            float[] y={0f};
            Runnable closePage=()->{ try { if(stream[0]!=null) stream[0].close(); } catch(Exception ignored){} stream[0]=null; };
            java.util.function.Consumer<Boolean> newPage=(first)->{
                closePage.run();
                try {
                    PDPage page=new PDPage(PDRectangle.A4); doc.addPage(page);
                    stream[0]=new PDPageContentStream(doc,page); y[0]=800f;
                    text(stream[0],pdfSafe(companyName),left,y[0],16,true); y[0]-=23;
                    text(stream[0],first?"CUSTOMER ACCOUNT STATEMENT":"CUSTOMER STATEMENT - CONTINUED",left,y[0],11,true); y[0]-=17;
                    text(stream[0],"Customer: "+pdfSafe(client),left,y[0],9,true); y[0]-=13;
                    text(stream[0],"Period: "+data.get("from")+" to "+data.get("to"),left,y[0],8,false); y[0]-=18;
                    if(first){
                        text(stream[0],"OPENING BALANCE",left,y[0],8,true); y[0]-=13;
                        if(opening.isEmpty()){text(stream[0],"No opening balance",left,y[0],8,false);y[0]-=12;}
                        else for(Map.Entry<String,BigDecimal> e:opening.entrySet()){
                            text(stream[0],pdfSafe(e.getKey()+" "+money(e.getValue())),left,y[0],8,false); y[0]-=12;
                        }
                        y[0]-=5;
                        text(stream[0],"TRANSACTION HISTORY",left,y[0],9,true); y[0]-=15;
                    }
                } catch(Exception ex){throw new IllegalStateException(ex);}
            };
            newPage.accept(true);
            for(Map<String,Object> r:history){
                if(y[0]<78){newPage.accept(false);}
                String type=Objects.toString(r.get("transaction_type"),"");
                String date=Objects.toString(r.get("transaction_date"),"");
                String ref=Objects.toString(r.get("reference"),"");
                String currency=Objects.toString(r.get("currency"),"");
                String debit=money(n(r.get("debit_amount")));
                String credit=money(n(r.get("credit_amount")));
                String description=Objects.toString(r.get("description"),"");
                text(stream[0],pdfSafe(truncate(date+" | "+type+" | "+ref,120)),left,y[0],7,true);y[0]-=10;
                text(stream[0],pdfSafe(truncate(description,115)),left+8,y[0],7,false);y[0]-=10;
                text(stream[0],pdfSafe("Debit "+currency+" "+debit+"     Credit "+currency+" "+credit),left+8,y[0],7,false);y[0]-=13;
            }
            if(y[0]<110){newPage.accept(false);} else y[0]-=8;
            text(stream[0],"CURRENT OUTSTANDING BALANCE",left,y[0],9,true); y[0]-=14;
            if(outstanding.isEmpty()){text(stream[0],"No outstanding receivables",left,y[0],8,false);y[0]-=12;}
            else for(Map.Entry<String,BigDecimal> e:outstanding.entrySet()){
                if(y[0]<55){newPage.accept(false);}
                text(stream[0],pdfSafe(e.getKey()+" "+money(e.getValue())),left,y[0],8,true);y[0]-=12;
            }
            text(stream[0],"This statement is generated from the tenant's invoice, payment and approved-note records.",left,42,7,false);
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
        if(blank(currency)||!currency.trim().matches("[A-Za-z]{3}")) throw bad("A three-letter currency is required");
        UUID tenant=tenant();
        String noteCurrency=currency.trim().toUpperCase(Locale.ROOT);
        BigDecimal noteTaxRate=BigDecimal.ZERO;
        BigDecimal noteTaxAmount=BigDecimal.ZERO;
        String noteTaxCode=null;
        String noteTaxJurisdiction=null;
        boolean noteTaxInclusive=false;
        if(invoiceId!=null) {
            Map<String,Object> invoice=one("""
                SELECT currency,lifecycle_status,invoice_amount,amount_paid,credit_note_amount,debit_note_amount,
                       tax_rate,tax_code,tax_jurisdiction_code,tax_inclusive
                  FROM commercial_invoices WHERE tenant_id=? AND id=? FOR SHARE
                """,tenant,invoiceId);
            String lifecycle=Objects.toString(invoice.get("lifecycle_status"),"ISSUED").toUpperCase(Locale.ROOT);
            if(Set.of("DRAFT","VOID","CANCELLED").contains(lifecycle))
                throw bad("Credit/debit notes may only be prepared against an issued invoice");
            if(!noteCurrency.equalsIgnoreCase(Objects.toString(invoice.get("currency"),"USD")))
                throw bad("Finance note currency must match the linked invoice currency");
            if("CREDIT".equals(type)) {
                BigDecimal outstanding=n(invoice.get("invoice_amount")).add(n(invoice.get("debit_note_amount")))
                    .subtract(n(invoice.get("credit_note_amount"))).subtract(n(invoice.get("amount_paid"))).max(BigDecimal.ZERO);
                if(amount.compareTo(outstanding)>0)
                    throw bad("Credit note exceeds the unpaid invoice balance. Customer refunds/customer-credit balances require a separate approved workflow.");
            }
            noteTaxRate=n(invoice.get("tax_rate"));
            noteTaxCode=Objects.toString(invoice.get("tax_code"),null);
            noteTaxJurisdiction=Objects.toString(invoice.get("tax_jurisdiction_code"),null);
            noteTaxInclusive=Boolean.TRUE.equals(invoice.get("tax_inclusive"));
            if(noteTaxRate.signum()>0) {
                noteTaxAmount=amount.multiply(noteTaxRate).divide(new BigDecimal("100").add(noteTaxRate),4,java.math.RoundingMode.HALF_UP);
                if(noteTaxAmount.compareTo(amount)>0) throw bad("Calculated note tax exceeds the gross adjustment");
            }
        }
        String noteNo=nextNoteNumber(type);
        UUID id=UUID.randomUUID();
        db.update("""
                INSERT INTO finance_notes(id,tenant_id,note_no,note_type,invoice_id,shipment_id,amount,currency,reason,
                                         tax_rate,tax_amount,tax_code,tax_jurisdiction_code,tax_inclusive,status,created_by)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT',?)
                """,id,tenant,noteNo,type,invoiceId,shipmentId,amount,noteCurrency,reason.trim(),noteTaxRate,noteTaxAmount,
                noteTaxCode,noteTaxJurisdiction,noteTaxInclusive,currentUser());
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
        BigDecimal noteTax=n(n.get("tax_amount"));
        String currency=Objects.toString(n.get("currency"),"USD");
        String reason=Objects.toString(n.get("reason"),"");
        if(invoiceId!=null){
            Map<String,Object> inv=one("SELECT currency,lifecycle_status,invoice_amount,amount_paid,credit_note_amount,debit_note_amount FROM commercial_invoices WHERE tenant_id=? AND id=? FOR UPDATE",tenant,invoiceId);
            String invoiceCurrency=Objects.toString(inv.get("currency"),"USD");
            if(!invoiceCurrency.equalsIgnoreCase(currency)) throw bad("Finance note currency must match the linked invoice currency");
            if(Set.of("VOID","CANCELLED","DRAFT").contains(Objects.toString(inv.get("lifecycle_status"),"ISSUED").toUpperCase(Locale.ROOT)))
                throw bad("Finance notes can only be posted against an issued invoice");
            BigDecimal gross=n(inv.get("invoice_amount")).add(n(inv.get("debit_note_amount"))).subtract(n(inv.get("credit_note_amount")));
            BigDecimal paid=n(inv.get("amount_paid"));
            if("CREDIT".equals(type)) {
                BigDecimal outstanding=gross.subtract(paid).max(BigDecimal.ZERO);
                if(amount.compareTo(outstanding)>0)
                    throw bad("Credit note exceeds the unpaid invoice balance. Customer refunds/credit balances must use a separately approved refund workflow.");
            }
        }
        if("CREDIT".equals(type)) {
            if(noteTax.signum()>0) posting.postCreditNoteWithTax(tenant,noteId,amount,noteTax,currency,invoiceNo,reason);
            else posting.postCreditNote(tenant,noteId,amount,currency,invoiceNo,reason);
        } else {
            if(noteTax.signum()>0) posting.postDebitNoteWithTax(tenant,noteId,amount,noteTax,currency,invoiceNo,reason);
            else posting.postDebitNote(tenant,noteId,amount,currency,invoiceNo,reason);
        }

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
                                               String countryCode,String address,String prefix,String currency,boolean active,boolean defaultForInvoicing) {
        if(blank(code)||blank(legalName)||blank(countryCode)) throw bad("Tax jurisdiction code, legal name and country are required");
        String normalizedCode=code.trim().toUpperCase(Locale.ROOT);
        String normalizedCountry=countryCode.trim().toUpperCase(Locale.ROOT);
        String normalizedCurrency=blank(currency)?"USD":currency.trim().toUpperCase(Locale.ROOT);
        if(!normalizedCode.matches("[A-Z0-9._-]{1,80}")) throw bad("Jurisdiction code may contain only letters, numbers, dot, underscore and hyphen");
        if(!normalizedCountry.matches("[A-Z]{2,3}")) throw bad("Country code must be ISO alpha-2 or alpha-3");
        if(!normalizedCurrency.matches("[A-Z]{3}")) throw bad("Currency must be a three-letter ISO code");
        String normalizedPrefix=blank(prefix)?null:prefix.trim().toUpperCase(Locale.ROOT);
        if(normalizedPrefix!=null&&!normalizedPrefix.matches("[A-Z0-9][A-Z0-9-]{0,38}")) throw bad("Invoice prefix may contain letters, numbers and hyphens only");
        if(defaultForInvoicing&&!active) throw bad("The default invoice-numbering jurisdiction must be active");
        UUID t=tenant();
        if(defaultForInvoicing){
            db.update("UPDATE finance_tax_jurisdictions SET default_for_invoicing=false,updated_at=now() WHERE tenant_id=? AND jurisdiction_code<>? AND default_for_invoicing=true",t,normalizedCode);
        }
        db.update("""
                INSERT INTO finance_tax_jurisdictions
                    (id,tenant_id,jurisdiction_code,legal_name,tax_registration_no,country_code,address,invoice_prefix,currency,active,default_for_invoicing)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(tenant_id,jurisdiction_code) DO UPDATE SET
                    legal_name=EXCLUDED.legal_name,tax_registration_no=EXCLUDED.tax_registration_no,
                    country_code=EXCLUDED.country_code,address=EXCLUDED.address,invoice_prefix=EXCLUDED.invoice_prefix,
                    currency=EXCLUDED.currency,active=EXCLUDED.active,default_for_invoicing=EXCLUDED.default_for_invoicing,updated_at=now()
                """,UUID.randomUUID(),t,normalizedCode,legalName.trim(),blank(registrationNo)?null:registrationNo.trim(),
                normalizedCountry,address,normalizedPrefix,normalizedCurrency,active,defaultForInvoicing);
        return one("SELECT * FROM finance_tax_jurisdictions WHERE tenant_id=? AND jurisdiction_code=?",t,normalizedCode);
    }

    /** Compatibility overload; newly configured jurisdictions should explicitly identify the invoice-numbering default. */
    @Deprecated
    @Transactional
    public Map<String,Object> taxJurisdiction(String code,String legalName,String registrationNo,
                                               String countryCode,String address,String prefix,String currency,boolean active) {
        return taxJurisdiction(code,legalName,registrationNo,countryCode,address,prefix,currency,active,false);
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> taxJurisdictions() {
        return db.queryForList("SELECT * FROM finance_tax_jurisdictions WHERE tenant_id=? ORDER BY active DESC,jurisdiction_code",tenant());
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> financeNotes(String status, UUID invoiceId) {
        String normalized=blank(status)?null:status.trim().toUpperCase(Locale.ROOT);
        if(normalized!=null&&!Set.of("DRAFT","APPROVED","VOID").contains(normalized)) throw bad("Invalid finance note status filter");
        return db.queryForList("""
            SELECT n.*,i.invoice_no,i.client FROM finance_notes n
              LEFT JOIN commercial_invoices i ON i.id=n.invoice_id AND i.tenant_id=n.tenant_id
             WHERE n.tenant_id=? AND (?::varchar IS NULL OR n.status=?) AND (?::uuid IS NULL OR n.invoice_id=?)
             ORDER BY n.created_at DESC,n.note_no DESC LIMIT 500
            """,tenant(),normalized,normalized,invoiceId,invoiceId);
    }

    @Transactional
    public Map<String,Object> upsertTaxRule(String jurisdictionCode,String code,String taxName,
            BigDecimal rate,BigDecimal withholdingRate,String currency,LocalDate validFrom,LocalDate validUntil,
            String taxType,boolean inclusive,String exemptionCode,String appliesTo,boolean active) {
        if(blank(jurisdictionCode)||blank(code)||blank(taxName)) throw bad("Jurisdiction, tax code and tax name are required");
        if(rate==null||rate.signum()<0||rate.compareTo(new BigDecimal("100"))>0) throw bad("Tax rate must be between 0 and 100 percent");
        if(withholdingRate==null||withholdingRate.signum()<0||withholdingRate.compareTo(new BigDecimal("100"))>0) throw bad("Withholding rate must be between 0 and 100 percent");
        if(validFrom==null) throw bad("Tax rule valid-from date is required");
        if(validUntil!=null&&validUntil.isBefore(validFrom)) throw bad("Tax rule valid-until date cannot be before valid-from date");
        String jurisdiction=jurisdictionCode.trim().toUpperCase(Locale.ROOT);
        String taxCode=code.trim().toUpperCase(Locale.ROOT);
        if(!taxCode.matches("[A-Z0-9._-]{1,80}")) throw bad("Tax code may contain only letters, numbers, dot, underscore and hyphen");
        String normalizedCurrency=blank(currency)?null:currency.trim().toUpperCase(Locale.ROOT);
        if(normalizedCurrency!=null&&!normalizedCurrency.matches("[A-Z]{3}")) throw bad("Tax rule currency must be a three-letter ISO code");
        String normalizedType=blank(taxType)?"VAT":taxType.trim().toUpperCase(Locale.ROOT);
        String normalizedApplies=blank(appliesTo)?"INVOICE":appliesTo.trim().toUpperCase(Locale.ROOT);
        if(!Set.of("VAT","GST","SALES_TAX","WITHHOLDING","OTHER").contains(normalizedType)) throw bad("Unsupported tax type");
        if(!Set.of("INVOICE","ALL").contains(normalizedApplies)) throw bad("Tax rule applies-to must be INVOICE or ALL");
        UUID t=tenant();
        Boolean configured=db.query("SELECT EXISTS(SELECT 1 FROM finance_tax_jurisdictions WHERE tenant_id=? AND jurisdiction_code=?)",rs->rs.next()&&rs.getBoolean(1),t,jurisdiction);
        if(!Boolean.TRUE.equals(configured)) throw bad("Configure the tax jurisdiction before adding tax rules");
        db.update("""
            INSERT INTO finance_tax_rules(id,tenant_id,code,tax_name,rate,withholding_rate,currency,valid_from,valid_until,active,
                                          jurisdiction_code,tax_type,inclusive,exemption_code,applies_to)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(tenant_id,jurisdiction_code,code,valid_from) DO UPDATE SET
                tax_name=EXCLUDED.tax_name,rate=EXCLUDED.rate,withholding_rate=EXCLUDED.withholding_rate,
                currency=EXCLUDED.currency,valid_until=EXCLUDED.valid_until,active=EXCLUDED.active,
                tax_type=EXCLUDED.tax_type,inclusive=EXCLUDED.inclusive,exemption_code=EXCLUDED.exemption_code,
                applies_to=EXCLUDED.applies_to
            """,UUID.randomUUID(),t,taxCode,taxName.trim(),rate,withholdingRate,normalizedCurrency,validFrom,validUntil,active,
                jurisdiction,normalizedType,inclusive,blank(exemptionCode)?null:exemptionCode.trim(),normalizedApplies);
        return one("SELECT * FROM finance_tax_rules WHERE tenant_id=? AND jurisdiction_code=? AND code=? AND valid_from=?",t,jurisdiction,taxCode,validFrom);
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> taxRules(String jurisdictionCode, LocalDate onDate) {
        LocalDate effective=onDate==null?LocalDate.now():onDate;
        if(blank(jurisdictionCode)) throw bad("Jurisdiction code is required");
        return db.queryForList("""
            SELECT r.id,r.code,r.tax_name,r.rate,r.withholding_rate,r.currency,r.valid_from,r.valid_until,
                   r.jurisdiction_code,r.tax_type,r.inclusive,r.exemption_code,r.applies_to,r.active
              FROM finance_tax_rules r JOIN finance_tax_jurisdictions j
                ON j.tenant_id=r.tenant_id AND j.jurisdiction_code=r.jurisdiction_code AND j.active=true
             WHERE r.tenant_id=? AND r.active=true AND r.jurisdiction_code=?
               AND r.valid_from<=? AND (r.valid_until IS NULL OR r.valid_until>=?)
             ORDER BY r.code,r.valid_from DESC
            """,tenant(),jurisdictionCode.trim().toUpperCase(Locale.ROOT),effective,effective);
    }

    @Transactional
    public Map<String,Object> calculateTax(String jurisdictionCode, String taxCode, BigDecimal amount,
                                            String currency, LocalDate onDate, String documentType, UUID documentId) {
        if(amount==null||amount.signum()<0) throw bad("Taxable amount must be zero or greater");
        if(blank(currency)||!currency.trim().matches("[A-Za-z]{3}")) throw bad("A three-letter currency is required");
        if(blank(jurisdictionCode)||blank(taxCode)) throw bad("Tax jurisdiction and tax code are required");
        LocalDate effective=onDate==null?LocalDate.now():onDate;
        String jurisdiction=jurisdictionCode.trim().toUpperCase(Locale.ROOT);
        String code=taxCode.trim().toUpperCase(Locale.ROOT);
        String normalizedCurrency=currency.trim().toUpperCase(Locale.ROOT);
        Map<String,Object> rule=one("""
            SELECT r.*,j.legal_name AS jurisdiction_legal_name,j.tax_registration_no AS jurisdiction_tax_registration_no,
                   j.address AS jurisdiction_address,j.country_code AS jurisdiction_country_code
              FROM finance_tax_rules r JOIN finance_tax_jurisdictions j
                ON j.tenant_id=r.tenant_id AND j.jurisdiction_code=r.jurisdiction_code AND j.active=true
             WHERE r.tenant_id=? AND r.active=true AND r.jurisdiction_code=? AND r.code=?
               AND r.valid_from<=? AND (r.valid_until IS NULL OR r.valid_until>=?)
               AND (r.applies_to='ALL' OR r.applies_to=?)
             ORDER BY r.valid_from DESC LIMIT 1 FOR SHARE OF r
            """,tenant(),jurisdiction,code,effective,effective,
                blank(documentType)?"INVOICE":documentType.trim().toUpperCase(Locale.ROOT));
        String ruleCurrency=Objects.toString(rule.get("currency"),"");
        if(!blank(ruleCurrency)&&!ruleCurrency.equalsIgnoreCase(normalizedCurrency)) throw bad("Tax rule currency does not match document currency");
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
        BigDecimal total=inclusive?amount:amount.add(tax);
        BigDecimal net=total.subtract(withholding);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("jurisdictionCode",jurisdiction); result.put("taxCode",rule.get("code"));
        result.put("taxName",rule.get("tax_name")); result.put("effectiveDate",effective); result.put("currency",normalizedCurrency);
        result.put("jurisdictionLegalName",rule.get("jurisdiction_legal_name"));
        result.put("taxRegistrationNo",rule.get("jurisdiction_tax_registration_no"));
        result.put("jurisdictionAddress",rule.get("jurisdiction_address"));
        result.put("jurisdictionCountryCode",rule.get("jurisdiction_country_code"));
        result.put("inclusive",inclusive); result.put("taxableAmount",base); result.put("rate",n(rule.get("rate")));
        result.put("taxAmount",tax); result.put("withholdingRate",n(rule.get("withholding_rate"))); result.put("withholdingAmount",withholding);
        result.put("totalAmount",total); result.put("netPayable",net);
        if(documentId!=null){
            if(blank(documentType)) throw bad("Document type is required when saving a tax snapshot");
            String json="{\"jurisdictionCode\":\""+jsonEscape(jurisdiction)+"\",\"effectiveDate\":\""+effective+"\",\"inclusive\":"+inclusive+
                    ",\"rate\":"+n(rule.get("rate")).toPlainString()+",\"withholdingRate\":"+n(rule.get("withholding_rate")).toPlainString()+
                    ",\"jurisdictionLegalName\":\""+jsonEscape(Objects.toString(rule.get("jurisdiction_legal_name"),""))+"\",\"taxRegistrationNo\":\""+
                    jsonEscape(Objects.toString(rule.get("jurisdiction_tax_registration_no"),""))+"\"}";
            db.update("""
                INSERT INTO finance_tax_calculation_snapshots
                    (tenant_id,document_type,document_id,tax_rule_id,jurisdiction_code,tax_code,tax_name,effective_date,inclusive,
                     taxable_amount,rate,tax_amount,withholding_rate,withholding_amount,total_amount,net_payable,currency,rule_snapshot)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb))
                """,tenant(),documentType.trim().toUpperCase(Locale.ROOT),documentId,rule.get("id"),jurisdiction,rule.get("code"),rule.get("tax_name"),
                    effective,inclusive,base,n(rule.get("rate")),tax,n(rule.get("withholding_rate")),withholding,total,net,normalizedCurrency,json);
        }
        return result;
    }

    @Transactional
    public Map<String,Object> applyTaxToDraftInvoice(UUID invoiceId, String jurisdictionCode, String taxCode, LocalDate onDate) {
        UUID t=tenant();
        Map<String,Object> inv=one("SELECT id,invoice_no,lifecycle_status,invoice_amount,amount_paid,currency,tax_amount,tax_code FROM commercial_invoices WHERE tenant_id=? AND id=? FOR UPDATE",t,invoiceId);
        if(!"DRAFT".equalsIgnoreCase(Objects.toString(inv.get("lifecycle_status"),"ISSUED")))
            throw bad("Tax can only be applied before an invoice is issued");
        if(n(inv.get("amount_paid")).signum()>0) throw bad("Tax cannot be changed after a collection has been recorded");
        if(!blank(Objects.toString(inv.get("tax_code"),null))) throw bad("A tax rule is already applied to this draft invoice; do not compound tax by applying a second rule");
        Map<String,Object> tax=calculateTax(jurisdictionCode,taxCode,n(inv.get("invoice_amount")),Objects.toString(inv.get("currency"),"USD"),
                onDate,"INVOICE",invoiceId);
        if(n(tax.get("withholdingAmount")).signum()>0)
            throw bad("This tax rule includes customer withholding. Apply a rule with 0% withholding until withholding-certificate clearing is enabled.");
        db.update("""
            UPDATE commercial_invoices SET subtotal_amount=?,invoice_amount=?,tax_rate=?,tax_amount=?,tax_code=?,tax_jurisdiction_code=?,tax_inclusive=?,
                   withholding_amount=?,tax_legal_name_snapshot=?,tax_registration_snapshot=?,tax_address_snapshot=?,tax_country_snapshot=?
             WHERE tenant_id=? AND id=? AND lifecycle_status='DRAFT'
            """,tax.get("taxableAmount"),tax.get("totalAmount"),tax.get("rate"),tax.get("taxAmount"),tax.get("taxCode"),tax.get("jurisdictionCode"),tax.get("inclusive"),
                tax.get("withholdingAmount"),tax.get("jurisdictionLegalName"),tax.get("taxRegistrationNo"),tax.get("jurisdictionAddress"),tax.get("jurisdictionCountryCode"),t,invoiceId);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("invoiceId",invoiceId);result.put("invoiceNo",inv.get("invoice_no"));result.put("lifecycleStatus","DRAFT");
        result.put("subtotalAmount",tax.get("taxableAmount"));result.put("taxAmount",tax.get("taxAmount"));
        result.put("taxRate",tax.get("rate"));result.put("taxCode",tax.get("taxCode"));result.put("taxJurisdictionCode",tax.get("jurisdictionCode"));
        result.put("invoiceAmount",tax.get("totalAmount"));result.put("currency",tax.get("currency"));
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
                SELECT i.id,i.invoice_no,i.client,i.due_date,i.currency,i.invoice_amount,i.amount_paid,i.lifecycle_status,
                       GREATEST(i.invoice_amount+i.debit_note_amount-i.credit_note_amount-i.amount_paid,0) AS balance,
                       CASE WHEN i.due_date < CURRENT_DATE-30 THEN 4
                            WHEN i.due_date < CURRENT_DATE-14 THEN 3
                            WHEN i.due_date < CURRENT_DATE-6 THEN 2 ELSE 1 END AS reminder_level,
                       c.email
                  FROM commercial_invoices i
                  LEFT JOIN LATERAL (
                      SELECT email FROM client_records cr
                       WHERE cr.tenant_id=i.tenant_id AND cr.client_company=i.client
                         AND cr.email IS NOT NULL AND btrim(cr.email)<>''
                       ORDER BY cr.updated_at DESC NULLS LAST LIMIT 1
                  ) c ON TRUE
                 WHERE i.tenant_id=? AND GREATEST(i.invoice_amount+i.debit_note_amount-i.credit_note_amount-i.amount_paid,0)>0
                   AND i.due_date<CURRENT_DATE
                   AND (i.next_follow_up IS NULL OR i.next_follow_up<=CURRENT_DATE)
                   AND i.lifecycle_status IN ('ISSUED','SENT','PARTIALLY_PAID','OVERDUE')
                 ORDER BY i.due_date
                 LIMIT 100
                """,tenant);
            for(Map<String,Object> r:due){
                UUID id=(UUID)r.get("id");
                String email=Objects.toString(r.get("email"),null);
                int level=((Number)r.get("reminder_level")).intValue();
                String current=Objects.toString(r.get("lifecycle_status"),"ISSUED").toUpperCase(Locale.ROOT);
                if(!"OVERDUE".equals(current)) {
                    int changed=db.update("UPDATE commercial_invoices SET lifecycle_status='OVERDUE' WHERE tenant_id=? AND id=? AND lifecycle_status=?",tenant,id,current);
                    if(changed>0) db.update("""
                        INSERT INTO commercial_invoice_lifecycle_history(id,tenant_id,invoice_id,from_status,to_status,reason,changed_by)
                        VALUES(?,?,?,?,?,?,NULL)
                        """,UUID.randomUUID(),tenant,id,current,"OVERDUE","Automatically marked overdue by finance reminder scheduler");
                }
                if(blank(email)) {
                    db.update("""
                        INSERT INTO finance_overdue_reminders(id,tenant_id,invoice_id,reminder_date,reminder_level,recipient_email,status,error_detail)
                        VALUES(?,?,?,?,?,NULL,'FAILED','No customer email is configured')
                        ON CONFLICT(tenant_id,invoice_id,reminder_date,reminder_level)
                        DO UPDATE SET status='FAILED',error_detail=EXCLUDED.error_detail,created_at=now()
                        WHERE finance_overdue_reminders.status='FAILED'
                        """,UUID.randomUUID(),tenant,id,LocalDate.now(),level);
                    continue;
                }
                int claimed=db.update("""
                    INSERT INTO finance_overdue_reminders(id,tenant_id,invoice_id,reminder_date,reminder_level,recipient_email,status)
                    VALUES(?,?,?,?,?,?,'PENDING')
                    ON CONFLICT(tenant_id,invoice_id,reminder_date,reminder_level)
                    DO UPDATE SET status='PENDING',recipient_email=EXCLUDED.recipient_email,error_detail=NULL,created_at=now()
                    WHERE finance_overdue_reminders.status='FAILED'
                    """,UUID.randomUUID(),tenant,id,LocalDate.now(),level,email);
                if(claimed==0) continue;
                try{
                    byte[] pdf=documents.invoicePdf(id);
                    String invoiceNo=Objects.toString(r.get("invoice_no"),id.toString());
                    long days=java.time.temporal.ChronoUnit.DAYS.between(
                            r.get("due_date") instanceof java.sql.Date d?d.toLocalDate():LocalDate.now(),LocalDate.now());
                    mail.sendFinancialDocument(email,Objects.toString(r.get("client"),"Customer"),
                            "Overdue invoice "+invoiceNo+" - reminder "+level,
                            "Invoice <strong>"+escape(invoiceNo)+"</strong> is "+days+" day(s) overdue. Outstanding balance: <strong>"+
                                    escape(Objects.toString(r.get("currency"),"USD")+" "+money(n(r.get("balance"))))+
                                    "</strong>. Please arrange payment or contact our accounts team if you need assistance.",
                            "invoice-"+safeFile(invoiceNo)+".pdf",pdf);
                    db.update("UPDATE finance_overdue_reminders SET status='SENT',error_detail=NULL WHERE tenant_id=? AND invoice_id=? AND reminder_date=? AND reminder_level=? AND status='PENDING'",tenant,id,LocalDate.now(),level);
                    db.update("UPDATE commercial_invoices SET last_follow_up=CURRENT_DATE,next_follow_up=CURRENT_DATE+7 WHERE tenant_id=? AND id=?",tenant,id);
                }catch(Exception ex){
                    db.update("""
                        UPDATE finance_overdue_reminders SET status='FAILED',error_detail=?
                         WHERE tenant_id=? AND invoice_id=? AND reminder_date=? AND reminder_level=?
                        """,truncate(ex.getMessage(),500),tenant,id,LocalDate.now(),level);
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
        if(from.equals(to)) return false;
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
    private static String pdfSafe(String s){return s==null?"":s.replaceAll("[^\\x20-\\x7E]","?");}
    private static String jsonEscape(String s){if(s==null)return "";return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");}
    private static String money(BigDecimal b){return b==null?"0.00":b.setScale(2,java.math.RoundingMode.HALF_UP).toPlainString();}
    private static void text(PDPageContentStream c,String value,float x,float y,float size,boolean bold)throws Exception{c.beginText();c.setFont(bold?PDType1Font.HELVETICA_BOLD:PDType1Font.HELVETICA,size);c.newLineAtOffset(x,y);c.showText(value==null?"":value);c.endText();}
    private record CommercialInvoiceProjection(UUID id,String invoiceNo){CommercialInvoice asEntityNotUsed(){return null;}}
}
