package com.logiplatform.service;

import com.logiplatform.tenancy.TenantContext;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Service
public class CommercialQuoteDocumentService {
    private final JdbcTemplate db;
    private final FinancialDocumentArchiveService archive;
    @Value("${app.company.name:Aviation Africa Logistics Ltd}") private String companyName;

    public CommercialQuoteDocumentService(@Qualifier("tenantJdbcTemplate") JdbcTemplate db,
                                           FinancialDocumentArchiveService archive){
        this.db=db;this.archive=archive;
    }

    public byte[] quotePdf(UUID quoteId){
        UUID tenant=tenant();
        Map<String,Object> q=db.queryForMap("""
            SELECT quote_id,quote_date,client,route,service_type,commodity,chargeable_weight_kg,
                   quoted_amount,currency,tax_rate,tax_amount,valid_until,status,incoterm,customer_credit_terms,notes
              FROM commercial_quotes WHERE tenant_id=? AND id=?
            """,tenant,quoteId);
        try(PDDocument doc=new PDDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            PDPage page=new PDPage(PDRectangle.A4);doc.addPage(page);
            try(PDPageContentStream c=new PDPageContentStream(doc,page)){
                float y=800;
                text(c,companyName,42,y,18,true);text(c,"QUOTATION",430,y,15,true);y-=28;
                text(c,"Quotation: "+q.get("quote_id"),42,y,10,true);
                text(c,"Date: "+q.get("quote_date"),420,y,9,false);y-=30;
                text(c,"CLIENT",42,y,10,true);text(c,s(q.get("client")),42,y-15,10,false);y-=45;
                row(c,"Route",s(q.get("route")),y);y-=18;
                row(c,"Service",s(q.get("service_type")),y);y-=18;
                row(c,"Commodity",s(q.get("commodity")),y);y-=18;
                row(c,"Chargeable weight",s(q.get("chargeable_weight_kg"))+" kg",y);y-=18;
                row(c,"Incoterm",s(q.get("incoterm")),y);y-=28;
                row(c,"Quoted total",s(q.get("currency"))+" "+money(q.get("quoted_amount")),y);y-=18;
                row(c,"Tax",s(q.get("currency"))+" "+money(q.get("tax_amount"))+" ("+s(q.get("tax_rate"))+"%)",y);y-=18;
                row(c,"Valid until",s(q.get("valid_until")),y);y-=18;
                row(c,"Status",s(q.get("status")),y);y-=30;
                if(q.get("customer_credit_terms")!=null) {text(c,"Credit terms: "+s(q.get("customer_credit_terms")),42,y,9,false);y-=18;}
                if(q.get("notes")!=null) {text(c,"Notes: "+truncate(s(q.get("notes")),100),42,y,8,false);}
                text(c,"This quotation is subject to the commercial terms recorded in the AAL system.",42,60,8,false);
            }
            doc.save(out);byte[] pdf=out.toByteArray();
            archive.archive("QUOTE",quoteId,"quotation-"+s(q.get("quote_id"))+".pdf","application/pdf",pdf);
            return pdf;
        }catch(Exception e){throw new IllegalStateException("Unable to generate quotation PDF",e);}
    }

    public String secureLink(UUID quoteId,long hours){
        quotePdf(quoteId);UUID id=archive.latestArchiveId("QUOTE",quoteId);
        if(id==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Archived quotation not found");
        return archive.issueLink(id,Duration.ofHours(hours));
    }

    private UUID tenant(){UUID t=TenantContext.getTenantId();if(t==null)throw new IllegalStateException("Tenant context is required");return t;}
    private static String s(Object v){return v==null?"":String.valueOf(v);}
    private static String money(Object v){if(v==null)return "0.00";return new BigDecimal(v.toString()).setScale(2,java.math.RoundingMode.HALF_UP).toPlainString();}
    private static String truncate(String v,int max){return v.length()<=max?v:v.substring(0,max-3)+"...";}
    private static void row(PDPageContentStream c,String label,String value,float y)throws Exception{text(c,label,42,y,9,true);text(c,value,180,y,9,false);}
    private static void text(PDPageContentStream c,String value,float x,float y,float size,boolean bold)throws Exception{c.beginText();c.setFont(bold?PDType1Font.HELVETICA_BOLD:PDType1Font.HELVETICA,size);c.newLineAtOffset(x,y);c.showText(value==null?"":value);c.endText();}
}
