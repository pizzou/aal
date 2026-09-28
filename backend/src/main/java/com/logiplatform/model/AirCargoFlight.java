package com.logiplatform.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Auditable air-cargo flight/offer snapshot.
 *
 * <p>The provider identity is deliberately stored separately from {@code source}.
 * This is required for multi-airline connectivity because the same airline flight
 * can be received through different providers, while a generic source such as
 * EXTERNAL must not erase the actual provider identity.</p>
 */
@Entity
@Table(
        name = "air_cargo_flights",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_air_flight",
                columnNames = {
                        "tenant_id",
                        "provider_code",
                        "carrier_code",
                        "flight_number",
                        "departure_time"
                }))
public class AirCargoFlight {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "carrier_code", nullable = false, length = 40)
    private String carrierCode;

    @Column(name = "carrier_name")
    private String carrierName;

    @Column(name = "flight_number", nullable = false, length = 80)
    private String flightNumber;

    @Column(name = "origin_code", nullable = false, length = 10)
    private String originCode;

    @Column(name = "destination_code", nullable = false, length = 10)
    private String destinationCode;

    @Column(name = "departure_time", nullable = false)
    private Instant departureTime;

    @Column(name = "arrival_time")
    private Instant arrivalTime;

    @Column(name = "total_capacity_kg", nullable = false, precision = 18, scale = 3)
    private BigDecimal totalCapacityKg;

    @Column(name = "available_capacity_kg", nullable = false, precision = 18, scale = 3)
    private BigDecimal availableCapacityKg;

    @Column(name = "status", nullable = false, length = 50)
    private String status = "SCHEDULED";

    /**
     * Where the snapshot originated from operationally, for example INTERNAL,
     * INGESTED, EXTERNAL, CARGOAI, QATAR or LHCARGO.
     */
    @Column(name = "source", nullable = false, length = 80)
    private String source = "EXTERNAL";

    /**
     * Stable provider identity used for routing booking, tracking, cancellation
     * and reconciliation back to the provider that supplied the offer.
     */
    @Column(name = "provider_code", nullable = false, length = 80)
    private String providerCode = "INTERNAL";

    @Column(name = "provider_reference", length = 255)
    private String providerReference;

    @Column(name = "rate_id", length = 100)
    private String rateId;

    @Column(name = "rate_name", length = 100)
    private String rateName;

    @Column(name = "currency", length = 10)
    private String currency;

    @Column(name = "total_price", precision = 18, scale = 3)
    private BigDecimal totalPrice;

    @Column(name = "bookable", nullable = false)
    private boolean bookable;

    @Column(name = "available_reason", length = 500)
    private String availableReason;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected AirCargoFlight() {
        // JPA only.
    }

    /**
     * Backward-compatible constructor used by existing AAL call sites.
     * The source is also used as the provider when it is a concrete provider
     * code; generic/internal source values map to INTERNAL.
     */
    public AirCargoFlight(
            UUID tenantId,
            String carrierCode,
            String carrierName,
            String flightNumber,
            String originCode,
            String destinationCode,
            Instant departureTime,
            Instant arrivalTime,
            BigDecimal totalCapacityKg,
            BigDecimal availableCapacityKg,
            String source) {
        this(
                tenantId,
                carrierCode,
                carrierName,
                flightNumber,
                originCode,
                destinationCode,
                departureTime,
                arrivalTime,
                totalCapacityKg,
                availableCapacityKg,
                source,
                providerFromSource(source));
    }

    /**
     * Explicit provider-aware constructor for real airline/provider offers.
     */
    public AirCargoFlight(
            UUID tenantId,
            String carrierCode,
            String carrierName,
            String flightNumber,
            String originCode,
            String destinationCode,
            Instant departureTime,
            Instant arrivalTime,
            BigDecimal totalCapacityKg,
            BigDecimal availableCapacityKg,
            String source,
            String providerCode) {

        validateIdentity(tenantId, carrierCode, flightNumber, originCode, destinationCode, departureTime);
        validateCapacity(totalCapacityKg, availableCapacityKg);

        this.tenantId = tenantId;
        this.carrierCode = normalizeRequired(carrierCode, "carrierCode");
        this.carrierName = normalizeOptional(carrierName);
        this.flightNumber = normalizeRequired(flightNumber, "flightNumber");
        this.originCode = normalizeAirport(originCode, "originCode");
        this.destinationCode = normalizeAirport(destinationCode, "destinationCode");
        this.departureTime = departureTime;
        this.arrivalTime = arrivalTime;
        this.totalCapacityKg = totalCapacityKg;
        this.availableCapacityKg = availableCapacityKg;
        this.source = normalizeSource(source);
        this.providerCode = normalizeProvider(providerCode, this.source);

        // A flight becomes bookable only when the provider returns a confirmed
        // live offer/rate. Do not mark it bookable merely because its source is
        // external.
        this.bookable = false;
        this.availableReason = null;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getCarrierCode() {
        return carrierCode;
    }

    public String getCarrierName() {
        return carrierName;
    }

    public String getFlightNumber() {
        return flightNumber;
    }

    public String getOriginCode() {
        return originCode;
    }

    public String getDestinationCode() {
        return destinationCode;
    }

    public Instant getDepartureTime() {
        return departureTime;
    }

    public Instant getArrivalTime() {
        return arrivalTime;
    }

    public BigDecimal getTotalCapacityKg() {
        return totalCapacityKg;
    }

    public BigDecimal getAvailableCapacityKg() {
        return availableCapacityKg;
    }

    public String getStatus() {
        return status;
    }

    public String getSource() {
        return source;
    }

    /**
     * Returns the actual integration/provider code, e.g. CARGOAI, QATAR or
     * LHCARGO. This is intentionally not derived from the carrier code.
     */
    public String getProviderCode() {
        return providerCode;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public String getRateId() {
        return rateId;
    }

    public String getRateName() {
        return rateName;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getTotalPrice() {
        return totalPrice;
    }

    public boolean isBookable() {
        return bookable;
    }

    public String getAvailableReason() {
        return availableReason;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    /**
     * Updates provider offer/rate metadata returned by a live provider.
     * Existing provider identity is preserved unless it is still unknown.
     */
    public void setProviderOffer(
            String providerReference,
            String rateId,
            String rateName,
            String currency,
            BigDecimal totalPrice,
            boolean bookable,
            String availableReason) {

        this.providerReference = normalizeOptional(providerReference);
        this.rateId = normalizeOptional(rateId);
        this.rateName = normalizeOptional(rateName);
        this.currency = normalizeOptional(currency);
        this.totalPrice = normalizeMoney(totalPrice);
        this.bookable = bookable && isLiveProvider();
        this.availableReason = normalizeOptional(availableReason);
        touch();
    }

    /**
     * Same as setProviderOffer(...), but allows a provider adapter to explicitly
     * establish the provider identity in the same operation.
     */
    public void setProviderOffer(
            String providerCode,
            String providerReference,
            String rateId,
            String rateName,
            String currency,
            BigDecimal totalPrice,
            boolean bookable,
            String availableReason) {

        assignProviderCode(providerCode);
        setProviderOffer(
                providerReference,
                rateId,
                rateName,
                currency,
                totalPrice,
                bookable,
                availableReason);
    }

    /**
     * Refreshes capacity/schedule data while retaining the actual integration
     * provider whenever the new source is generic (for example EXTERNAL).
     */
    public void refreshCapacity(
            BigDecimal total,
            BigDecimal available,
            Instant arrival,
            String source) {

        validateCapacity(total, available);

        totalCapacityKg = total;
        availableCapacityKg = available;
        arrivalTime = arrival;

        if (source != null && !source.isBlank()) {
            String normalizedSource = normalizeSource(source);
            this.source = normalizedSource;

            String inferredProvider = providerFromSource(normalizedSource);
            if (!"INTERNAL".equals(inferredProvider)) {
                this.providerCode = inferredProvider;
            } else if (providerCode == null || providerCode.isBlank()) {
                this.providerCode = "INTERNAL";
            }
        }

        // Capacity refresh alone must not turn an unbookable offer into a
        // bookable offer. Keep existing offer state intact.
        touch();
    }

    /**
     * Explicitly assigns the provider identity. Intended for provider adapters,
     * synchronization services and migrations, not arbitrary controller input.
     */
    public void assignProviderCode(String providerCode) {
        this.providerCode = normalizeProvider(providerCode, this.source);
        if (!isLiveProvider()) {
            this.bookable = false;
        }
        touch();
    }

    /**
     * Reserves capacity atomically from the current entity snapshot.
     */
    public boolean reserve(BigDecimal kg) {
        if (kg == null
                || kg.signum() <= 0
                || availableCapacityKg == null
                || availableCapacityKg.compareTo(kg) < 0) {
            return false;
        }

        availableCapacityKg = availableCapacityKg.subtract(kg);
        touch();
        return true;
    }

    public void release(BigDecimal kg) {
        if (kg == null || kg.signum() <= 0 || availableCapacityKg == null || totalCapacityKg == null) {
            return;
        }

        availableCapacityKg = availableCapacityKg.add(kg);
        if (availableCapacityKg.compareTo(totalCapacityKg) > 0) {
            availableCapacityKg = totalCapacityKg;
        }
        touch();
    }

    @PrePersist
    @PreUpdate
    private void normalizeBeforeWrite() {
        source = normalizeSource(source);
        providerCode = normalizeProvider(providerCode, source);

        if (bookable && !isLiveProvider()) {
            bookable = false;
        }

        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
    }

    private boolean isLiveProvider() {
        return providerCode != null
                && !providerCode.isBlank()
                && !"INTERNAL".equalsIgnoreCase(providerCode);
    }

    private void touch() {
        updatedAt = Instant.now();
    }

    private static void validateIdentity(
            UUID tenantId,
            String carrierCode,
            String flightNumber,
            String originCode,
            String destinationCode,
            Instant departureTime) {

        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (isBlank(carrierCode)) {
            throw new IllegalArgumentException("carrierCode is required");
        }
        if (isBlank(flightNumber)) {
            throw new IllegalArgumentException("flightNumber is required");
        }
        if (isBlank(originCode)) {
            throw new IllegalArgumentException("originCode is required");
        }
        if (isBlank(destinationCode)) {
            throw new IllegalArgumentException("destinationCode is required");
        }
        if (departureTime == null) {
            throw new IllegalArgumentException("departureTime is required");
        }
    }

    private static void validateCapacity(BigDecimal total, BigDecimal available) {
        if (total == null
                || available == null
                || total.signum() < 0
                || available.signum() < 0
                || available.compareTo(total) > 0) {
            throw new IllegalArgumentException("Invalid flight capacity");
        }
    }

    private static String normalizeRequired(String value, String field) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeAirport(String value, String field) {
        String normalized = normalizeRequired(value, field);
        if (!normalized.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException(field + " must be a 3-letter airport code");
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim();
    }

    private static String normalizeSource(String value) {
        return value == null || value.isBlank()
                ? "EXTERNAL"
                : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeProvider(String value, String source) {
        if (value != null && !value.isBlank()) {
            return value.trim().toUpperCase(Locale.ROOT);
        }
        return providerFromSource(source);
    }

    private static String providerFromSource(String value) {
        String normalized = value == null || value.isBlank()
                ? "INTERNAL"
                : value.trim().toUpperCase(Locale.ROOT);

        return switch (normalized) {
            case "INGESTED",
                 "EXTERNAL",
                 "AAL_PLANNING",
                 "INTERNAL",
                 "INTERNAL_CAPACITY" -> "INTERNAL";
            default -> normalized;
        };
    }

    private static BigDecimal normalizeMoney(BigDecimal value) {
        return value == null ? null : value.max(BigDecimal.ZERO);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
