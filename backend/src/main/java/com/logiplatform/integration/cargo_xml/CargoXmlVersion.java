package com.logiplatform.integration.cargo_xml;

public record CargoXmlVersion(String standard, String version) {
    public CargoXmlVersion { standard = standard == null || standard.isBlank() ? "IATA_CARGO_XML" : standard.trim().toUpperCase(); version = version == null || version.isBlank() ? "UNKNOWN" : version.trim(); }
}
