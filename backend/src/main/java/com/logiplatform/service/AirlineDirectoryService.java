package com.logiplatform.service;

import com.logiplatform.integration.AirCargoProviderPort;
import com.logiplatform.integration.AirCargoProviderRegistry;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Curated air-cargo airline directory used by the operations desk.
 *
 * <p>This is deliberately a reference catalog, not a claim that every carrier
 * is currently connected to AAL. Live connectivity is determined from the
 * configured provider adapters and from actual search results. That distinction
 * prevents a static airline list from being mistaken for live capacity.</p>
 */
@Service
public class AirlineDirectoryService {

    public record Airline(
            String iataCode,
            String icaoCode,
            String name,
            String cargoBrand,
            String country,
            String region,
            String officialWebsite,
            String cargoWebsite,
            List<String> providerPaths,
            List<String> capabilities,
            String integrationNote) {
    }

    public record DirectoryResponse(
            List<Airline> airlines,
            List<String> configuredProviders,
            long liveSearchProviders,
            long liveBookingProviders,
            long liveTrackingProviders) {
    }

    private final AirCargoProviderRegistry providers;

    public AirlineDirectoryService(AirCargoProviderRegistry providers) {
        this.providers = providers;
    }

    public DirectoryResponse list() {
        List<Airline> airlines = new ArrayList<>(CATALOG);
        airlines.sort(Comparator.comparing(Airline::name));

        List<AirCargoProviderRegistry.ProviderHealth> health = providers.health();
        List<String> configured = health.stream()
                .filter(AirCargoProviderRegistry.ProviderHealth::configured)
                .map(AirCargoProviderRegistry.ProviderHealth::code)
                .sorted()
                .toList();

        long search = health.stream()
                .filter(p -> p.configured() && p.scheduleSearch())
                .count();
        long booking = health.stream()
                .filter(p -> p.configured() && p.booking())
                .count();
        long tracking = health.stream()
                .filter(p -> p.configured() && p.flightStatus())
                .count();

        return new DirectoryResponse(airlines, configured, search, booking, tracking);
    }

    public Airline find(String iataCode) {
        String code = iataCode == null ? "" : iataCode.trim().toUpperCase(Locale.ROOT);
        return CATALOG.stream()
                .filter(a -> a.iataCode().equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown airline IATA code: " + iataCode));
    }

    private static Airline airline(
            String iata,
            String icao,
            String name,
            String cargoBrand,
            String country,
            String region,
            String officialWebsite,
            String cargoWebsite,
            List<String> providerPaths,
            List<String> capabilities,
            String note) {
        return new Airline(iata, icao, name, cargoBrand, country, region,
                officialWebsite, cargoWebsite, providerPaths, capabilities, note);
    }

    /**
     * Initial production catalog. Codes are airline identifiers, not provider
     * credentials. Provider coverage is resolved at runtime.
     */
    private static final List<Airline> CATALOG = List.of(
            airline("ET", "ETH", "Ethiopian Airlines", "Ethiopian Cargo & Logistics Services", "Ethiopia", "Africa & Middle East",
                    "https://www.ethiopianairlines.com/", "https://cargo.ethiopianairlines.com/",
                    List.of("CARGOAI", "WEBCARGO"), List.of("SCHEDULE", "CAPACITY", "QUOTE", "BOOK", "TRACK"),
                    "Ethiopian Cargo publicly announced a CargoAi digital-booking partnership in September 2026."),
            airline("WB", "RWD", "RwandAir", "RwandAir Cargo", "Rwanda", "Africa & Middle East",
                    "https://www.rwandair.com/", "https://www.rwandair.com/business-solutions/cargo/",
                    List.of("CARGOAI", "DIRECT_PARTNER_API"), List.of("SCHEDULE", "BOOK", "TRACK"),
                    "Direct AAL booking requires an airline API/EDI agreement; provider-network coverage can be enabled when available."),
            airline("KQ", "KQA", "Kenya Airways", "Kenya Airways Cargo", "Kenya", "Africa & Middle East",
                    "https://www.kenya-airways.com/", "https://www.kenya-airways.com/",
                    List.of("CARGOAI", "WEBCARGO", "DIRECT_PARTNER_API"), List.of("SCHEDULE", "BOOK", "TRACK"),
                    "Use a configured cargo distribution provider or a Kenya Airways Cargo partner API/EDI contract for live booking."),
            airline("QR", "QTR", "Qatar Airways", "Qatar Airways Cargo", "Qatar", "Africa & Middle East",
                    "https://www.qatarairways.com/", "https://www.qrcargo.com/",
                    List.of("QATAR", "CARGOAI", "WEBCARGO"), List.of("SCHEDULE", "CAPACITY", "QUOTE", "BOOK", "TRACK"),
                    "AAL already contains the documented Qatar Availability/Rate adapter; booking requires the carrier's current booking/stock contract."),
            airline("EK", "UAE", "Emirates", "Emirates SkyCargo", "United Arab Emirates", "Africa & Middle East",
                    "https://www.emirates.com/", "https://www.skycargo.com/",
                    List.of("CARGOAI", "WEBCARGO", "DIRECT_PARTNER_API"), List.of("SCHEDULE", "QUOTE", "BOOK", "TRACK"),
                    "Live airline booking is enabled only when a configured provider has this carrier in its account coverage."),
            airline("EY", "ETD", "Etihad Airways", "Etihad Cargo", "United Arab Emirates", "Africa & Middle East",
                    "https://www.etihad.com/", "https://www.etihadcargo.com/",
                    List.of("CARGOAI", "WEBCARGO", "DIRECT_PARTNER_API"), List.of("SCHEDULE", "QUOTE", "BOOK", "TRACK"),
                    "Provider-network coverage or a direct cargo API/EDI agreement is required for live booking."),
            airline("TK", "THY", "Turkish Airlines", "Turkish Cargo", "Türkiye", "Europe",
                    "https://www.turkishairlines.com/", "https://www.turkishcargo.com/",
                    List.of("CARGOAI", "WEBCARGO", "DIRECT_PARTNER_API"), List.of("SCHEDULE", "CAPACITY", "QUOTE", "BOOK", "TRACK"),
                    "Turkish Cargo exposes digital cargo workflows; AAL should use a provisioned partner/API connection for automated booking."),
            airline("LH", "DLH", "Lufthansa", "Lufthansa Cargo", "Germany", "Europe",
                    "https://www.lufthansa.com/", "https://lufthansa-cargo.com/",
                    List.of("LHCARGO", "CARGOAI", "WEBCARGO"), List.of("SCHEDULE", "QUOTE", "BOOK", "TRACK"),
                    "AAL already contains direct Lufthansa Cargo AWB tracking. smartBooking requires partner registration and issued API credentials/endpoints."),
            airline("CV", "CLX", "Cargolux", "Cargolux", "Luxembourg", "Europe",
                    "https://www.cargolux.com/", "https://www.cargolux.com/",
                    List.of("CARGOAI", "WEBCARGO", "DIRECT_PARTNER_API"), List.of("SCHEDULE", "CAPACITY", "QUOTE", "BOOK", "TRACK"),
                    "Provider-network coverage or a direct carrier agreement is required for live transactional access."),
            airline("AF", "AFR", "Air France", "Air France KLM Martinair Cargo", "France", "Europe",
                    "https://wwws.airfrance.com/", "https://www.afklcargo.com/",
                    List.of("WEBCARGO", "CARGOAI", "DIRECT_PARTNER_API"), List.of("SCHEDULE", "QUOTE", "BOOK", "TRACK"),
                    "Cargo distribution provider coverage can expose live rates and eBooking without a separate direct airline adapter."),
            airline("KL", "KLM", "KLM", "Air France KLM Martinair Cargo", "Netherlands", "Europe",
                    "https://www.klm.com/", "https://www.afklcargo.com/",
                    List.of("WEBCARGO", "CARGOAI", "DIRECT_PARTNER_API"), List.of("SCHEDULE", "QUOTE", "BOOK", "TRACK"),
                    "Cargo distribution provider coverage can expose live rates and eBooking without a separate direct airline adapter."),
            airline("SQ", "SIA", "Singapore Airlines", "Singapore Airlines Cargo", "Singapore", "Asia Pacific",
                    "https://www.singaporeair.com/", "https://www.singaporeair.com/en_UK/us/cargo/",
                    List.of("CARGOAI", "WEBCARGO", "DIRECT_PARTNER_API"), List.of("SCHEDULE", "QUOTE", "BOOK", "TRACK"),
                    "Provider-network coverage or a direct cargo connectivity agreement is required for live booking."),
            airline("SV", "SVA", "Saudi Arabian Airlines", "Saudia Cargo", "Saudi Arabia", "Africa & Middle East",
                    "https://www.saudia.com/", "https://www.saudialogistics.com/",
                    List.of("CARGOAI", "WEBCARGO", "DIRECT_PARTNER_API"), List.of("SCHEDULE", "QUOTE", "BOOK", "TRACK"),
                    "Provider-network coverage or a direct cargo connectivity agreement is required for live booking."),
            airline("MS", "MSR", "Egyptair", "EgyptAir Cargo", "Egypt", "Africa & Middle East",
                    "https://www.egyptair.com/", "https://www.egyptair.com/",
                    List.of("CARGOAI", "WEBCARGO", "DIRECT_PARTNER_API"), List.of("SCHEDULE", "QUOTE", "BOOK", "TRACK"),
                    "Provider-network coverage or a direct cargo connectivity agreement is required for live booking."),
            airline("AT", "RAM", "Royal Air Maroc", "Royal Air Maroc Cargo", "Morocco", "Africa & Middle East",
                    "https://www.royalairmaroc.com/", "https://www.royalairmaroc.com/",
                    List.of("CARGOAI", "WEBCARGO", "DIRECT_PARTNER_API"), List.of("SCHEDULE", "QUOTE", "BOOK", "TRACK"),
                    "Provider-network coverage or a direct cargo connectivity agreement is required for live booking."),
            airline("MK", "MAU", "Air Mauritius", "Air Mauritius Cargo", "Mauritius", "Africa & Middle East",
                    "https://www.airmauritius.com/", "https://www.airmauritius.com/",
                    List.of("CARGOAI", "WEBCARGO", "DIRECT_PARTNER_API"), List.of("SCHEDULE", "QUOTE", "BOOK", "TRACK"),
                    "Provider-network coverage or a direct cargo connectivity agreement is required for live booking."),
            airline("DT", "DTA", "TAAG Angola Airlines", "TAAG Cargo", "Angola", "Africa & Middle East",
                    "https://www.taag.com/", "https://www.taag.com/",
                    List.of("CARGOAI", "WEBCARGO", "DIRECT_PARTNER_API"), List.of("SCHEDULE", "QUOTE", "BOOK", "TRACK"),
                    "Provider-network coverage or a direct cargo connectivity agreement is required for live booking."),
            airline("FA", "SFR", "Safair", "Safair Cargo", "South Africa", "Africa & Middle East",
                    "https://www.flysafair.co.za/", "https://www.flysafair.co.za/",
                    List.of("CARGOAI", "DIRECT_PARTNER_API"), List.of("SCHEDULE", "BOOK", "TRACK"),
                    "Provider-network coverage or a direct cargo connectivity agreement is required for live booking.")
    );
}
