package com.logiplatform.integration;

import com.logiplatform.integration.cargo_xml.CargoXmlParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CargoXmlParserSecurityTest {
    @Test void rejectsDoctypeExternalEntity(){
        String xml="<?xml version=\"1.0\"?><!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><foo>&xxe;</foo>";
        assertThrows(IllegalArgumentException.class,()->new CargoXmlParser().parse(xml));
    }
}
