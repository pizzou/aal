package com.logiplatform.integration.cargo_xml;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

@Component
public class CargoXmlMapper {
    public CargoXmlMessage toMessage(Document document,String messageId,CargoXmlVersion version,String direction,String payload){
        String type=document.getDocumentElement()==null?"UNKNOWN":document.getDocumentElement().getLocalName();
        return new CargoXmlMessage(messageId,type,version,direction,payload);
    }
}
