package com.logiplatform.integration.cargo_xml;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.StringReader;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;

@Component
public class CargoXmlValidator {
    private final String xsd;
    public CargoXmlValidator(@Value("${cargo-xml.xsd-location:}") String xsd){this.xsd=xsd==null?"":xsd.trim();}
    public void validate(String xml){
        if(xml==null||xml.isBlank()) throw new IllegalArgumentException("Cargo-XML payload must not be blank");
        if(xml.length()>10_000_000) throw new IllegalArgumentException("Cargo-XML payload exceeds 10 MB");
        if(xsd.isBlank()) return; // The standard/schema is supplied by the implementation partner.
        if(!Files.exists(Path.of(xsd))) throw new IllegalStateException("Configured Cargo-XML XSD is unavailable");
        try {
            SchemaFactory factory=SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI); factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD,""); factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            factory.newSchema(Path.of(xsd).toFile()).newValidator().validate(new StreamSource(new StringReader(xml)));
        } catch(Exception ex){ throw new IllegalArgumentException("Cargo-XML schema validation failed",ex); }
    }
}
