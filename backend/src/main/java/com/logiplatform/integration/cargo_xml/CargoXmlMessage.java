package com.logiplatform.integration.cargo_xml;

public record CargoXmlMessage(String messageId,String messageType,CargoXmlVersion version,String direction,String payloadXml) {}
