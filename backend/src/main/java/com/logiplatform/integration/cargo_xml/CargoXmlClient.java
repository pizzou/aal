package com.logiplatform.integration.cargo_xml;

import com.logiplatform.integration.control.OperationRetryPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.UUID;

/** Dedicated Cargo-XML boundary. It is intentionally not the generic JSON airline adapter. */
@Component
public class CargoXmlClient {
    private final RestTemplate rest; private final String baseUrl; private final boolean enabled; private final CargoXmlParser parser; private final CargoXmlValidator validator;
    public CargoXmlClient(RestTemplate rest,@Value("${cargo-xml.enabled:false}") boolean enabled,@Value("${cargo-xml.base-url:}") String baseUrl,CargoXmlParser parser,CargoXmlValidator validator){this.rest=rest;this.enabled=enabled;this.baseUrl=baseUrl==null?"":baseUrl.replaceAll("/+$","");this.parser=parser;this.validator=validator;}
    public String send(CargoXmlMessage message){if(!enabled||baseUrl.isBlank()) throw new IllegalStateException("Cargo-XML integration is not configured"); validator.validate(message.payloadXml()); parser.parse(message.payloadXml()); HttpHeaders h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_XML);h.setAccept(List.of(MediaType.APPLICATION_XML,MediaType.APPLICATION_JSON));h.set("X-Cargo-XML-Message-Id",message.messageId()==null?UUID.randomUUID().toString():message.messageId());h.set("X-Cargo-XML-Version",message.version().version()); if(OperationRetryPolicy.classify("POST",message.messageId())==OperationRetryPolicy.CONDITIONALLY_RETRYABLE) h.set("Idempotency-Key",message.messageId()); ResponseEntity<String> r=rest.postForEntity(baseUrl,new HttpEntity<>(message.payloadXml(),h),String.class); if(!r.getStatusCode().is2xxSuccessful()) throw new IllegalStateException("Cargo-XML provider rejected message: HTTP "+r.getStatusCode().value()); return r.getBody();}
}
