package com.logiplatform.integration.cargo_xml;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

@Component
public class CargoXmlParser {
    public Document parse(String xml){
        try{
            DocumentBuilderFactory f=DocumentBuilderFactory.newInstance(); f.setNamespaceAware(true); f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true); f.setFeature("http://xml.org/sax/features/external-general-entities",false); f.setFeature("http://xml.org/sax/features/external-parameter-entities",false); f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false); f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,""); f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        }catch(Exception ex){throw new IllegalArgumentException("Invalid Cargo-XML payload",ex);}
    }
}
