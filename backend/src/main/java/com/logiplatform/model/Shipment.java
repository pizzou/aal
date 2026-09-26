package com.logiplatform.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "shipments")
public class Shipment {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "reference_code", nullable = false)
    private String referenceCode;

    @Column(name = "origin_address", nullable = false)
    private String originAddress;

    @Column(name = "destination_address", nullable = false)
    private String destinationAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ShipmentStatus status = ShipmentStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "transport_mode", nullable = false)
    private TransportMode transportMode = TransportMode.ROAD;

    @Column(name = "carrier_name")
    private String carrierName;

    /**
     * AWB / BL / container / tracking reference.
     */
    @Column(name = "carrier_reference_number")
    private String carrierReferenceNumber;

    /**
     * Public tracking access token.
     */
    @Column(name = "tracking_token", nullable = false, updatable = false)
    private UUID trackingToken = UUID.randomUUID();

    @Column(name = "weight_kg")
    private Integer weightKg;

    @Column(name = "notification_email")
    private String notificationEmail;

    @Column(name = "flight_number")
    private String flightNumber;

    /**
     * Date Opened from the AAL Command Center workbook.
     */
    @Column(name = "date_opened")
    private LocalDate dateOpened;

    /*
     * ========================================================================
     * AAL COMMAND CENTER / MOTHERSHIP FIELDS
     * ========================================================================
     */

    @Column(name = "client_name")
    private String clientName;

    @Column(name = "contact")
    private String contact;

    @Column(name = "commodity")
    private String commodity;

    @Column(name = "origin_country")
    private String originCountry;

    @Column(name = "origin_city_port")
    private String originCityPort;

    @Column(name = "destination_country")
    private String destinationCountry;

    @Column(name = "destination_city_port")
    private String destinationCityPort;

    @Column(
            name = "gross_weight_kg",
            precision = 18,
            scale = 3
    )
    private BigDecimal grossWeightKg;

    @Column(
            name = "volumetric_weight_kg",
            precision = 18,
            scale = 3
    )
    private BigDecimal volumetricWeightKg;

    @Column(
            name = "chargeable_weight_kg",
            precision = 18,
            scale = 3
    )
    private BigDecimal chargeableWeightKg;

    @Column(name = "packages")
    private Integer packages;

    @Column(name = "operator_name")
    private String operatorName;

    @Column(name = "service_type")
    private String serviceType;

    @Column(name = "etd")
    private Instant etd;

    @Column(name = "eta")
    private Instant eta;

    @Column(name = "actual_departure")
    private Instant actualDeparture;

    @Column(name = "actual_arrival")
    private Instant actualArrival;

    /*
     * ========================================================================
     * FINANCIAL INPUTS
     * ========================================================================
     */

    @Column(
            name = "supplier_cost",
            precision = 19,
            scale = 4
    )
    private BigDecimal supplierCost;

    @Column(
            name = "other_cost",
            precision = 19,
            scale = 4
    )
    private BigDecimal otherCost;

    @Column(
            name = "amount_paid_by_client",
            precision = 19,
            scale = 4
    )
    private BigDecimal amountPaidByClient;

    @Column(
            name = "amount_paid_to_supply",
            precision = 19,
            scale = 4
    )
    private BigDecimal amountPaidToSupply;

    @Column(
            name = "other_expenses",
            precision = 19,
            scale = 4
    )
    private BigDecimal otherExpenses;

    @Column(name = "payment_status")
    private String paymentStatus;

    @Column(name = "owner_name")
    private String ownerName;

    @Column(name = "invoice_no")
    private String invoiceNo;

    @Column(name = "next_action")
    private String nextAction;

    @Column(name = "next_action_date")
    private LocalDate nextActionDate;

    @Column(
            name = "notes",
            columnDefinition = "text"
    )
    private String notes;

    @Column(name = "currency")
    private String currency;

    @Column(name = "airline_used")
    private String airlineUsed;

    /** Canonical customer revenue / billed amount. */
    @Column(
            name = "amount_billed_to_client",
            precision = 19,
            scale = 4
    )
    private BigDecimal amountBilledToClient;

    @Version
    @Column(
            name = "version",
            nullable = false
    )
    private long version;

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt = Instant.now();

    @Column(
            name = "updated_at",
            nullable = false
    )
    private Instant updatedAt = Instant.now();

    protected Shipment() {
    }

    public Shipment(
            UUID tenantId,
            String referenceCode,
            String originAddress,
            String destinationAddress,
            TransportMode transportMode,
            String carrierName,
            String carrierReferenceNumber
    ) {
        this.tenantId = tenantId;
        this.referenceCode = referenceCode;
        this.originAddress = originAddress;
        this.destinationAddress = destinationAddress;
        this.transportMode =
                transportMode != null
                        ? transportMode
                        : TransportMode.ROAD;
        this.carrierName = carrierName;
        this.carrierReferenceNumber =
                carrierReferenceNumber;
    }

    public void updateStatus(
            ShipmentStatus newStatus
    ) {
        if (newStatus == null) {
            throw new IllegalArgumentException(
                    "Shipment status cannot be null"
            );
        }

        this.status = newStatus;
        this.updatedAt = Instant.now();
    }

    /**
     * Safely maps a workbook status into the domain lifecycle.
     */
    public void setOperationalStatus(
            String rawStatus
    ) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return;
        }

        String normalized =
                rawStatus
                        .trim()
                        .toUpperCase()
                        .replace(" ", "_")
                        .replace("-", "_");

        try {
            this.status =
                    ShipmentStatus.valueOf(
                            normalized
                    );

            this.updatedAt = Instant.now();

        } catch (IllegalArgumentException ignored) {
            /*
             * Do not corrupt the shipment because a spreadsheet contains a
             * custom label unknown to the application.
             *
             * The original workbook value remains available through the
             * imported operational record where applicable.
             */
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getReferenceCode() {
        return referenceCode;
    }

    public String getOriginAddress() {
        return originAddress;
    }

    public String getDestinationAddress() {
        return destinationAddress;
    }

    public ShipmentStatus getStatus() {
        return status;
    }

    public TransportMode getTransportMode() {
        return transportMode;
    }

    public String getCarrierName() {
        return carrierName;
    }

    public String getCarrierReferenceNumber() {
        return carrierReferenceNumber;
    }

    public UUID getTrackingToken() {
        return trackingToken;
    }

    public Integer getWeightKg() {
        return weightKg;
    }

    public void setWeightKg(
            Integer weightKg
    ) {
        if (weightKg != null && weightKg < 0) {
            throw new IllegalArgumentException(
                    "weightKg must not be negative"
            );
        }

        this.weightKg = weightKg;
        this.updatedAt = Instant.now();
    }

    public String getNotificationEmail() {
        return notificationEmail;
    }

    public void setNotificationEmail(
            String notificationEmail
    ) {
        this.notificationEmail =
                notificationEmail;

        this.updatedAt = Instant.now();
    }

    public String getFlightNumber() {
        return flightNumber;
    }

    public void setFlightNumber(
            String flightNumber
    ) {
        this.flightNumber = flightNumber;
        this.updatedAt = Instant.now();
    }

    public LocalDate getDateOpened() {
        return dateOpened;
    }

    public void setDateOpened(
            LocalDate dateOpened
    ) {
        this.dateOpened = dateOpened;
        this.updatedAt = Instant.now();
    }

    public String getClientName() {
        return clientName;
    }

    public String getContact() {
        return contact;
    }

    public String getCommodity() {
        return commodity;
    }

    public String getOriginCountry() {
        return originCountry;
    }

    public String getOriginCityPort() {
        return originCityPort;
    }

    public String getDestinationCountry() {
        return destinationCountry;
    }

    public String getDestinationCityPort() {
        return destinationCityPort;
    }

    public BigDecimal getGrossWeightKg() {
        return grossWeightKg;
    }

    public BigDecimal getVolumetricWeightKg() {
        return volumetricWeightKg;
    }

    public BigDecimal getChargeableWeightKg() {
        return chargeableWeightKg;
    }

    public Integer getPackages() {
        return packages;
    }

    public String getOperatorName() {
        return operatorName;
    }

    public String getServiceType() {
        return serviceType;
    }

    public Instant getEtd() {
        return etd;
    }

    public Instant getEta() {
        return eta;
    }

    public Instant getActualDeparture() { return actualDeparture; }

    public Instant getActualArrival() { return actualArrival; }

    public BigDecimal getSupplierCost() {
        return supplierCost;
    }

    public BigDecimal getOtherCost() {
        return otherCost;
    }

    public BigDecimal getClientRevenue() {
        return amountBilledToClient;
    }

    public BigDecimal getAmountPaidByClient() {
        return amountPaidByClient;
    }

    public BigDecimal getAmountPaidToSupply() {
        return amountPaidToSupply;
    }

    public BigDecimal getOtherExpenses() {
        return otherExpenses;
    }

    public String getPaymentStatus() {
        return paymentStatus;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public String getInvoiceNo() {
        return invoiceNo;
    }

    public String getNextAction() {
        return nextAction;
    }

    public LocalDate getNextActionDate() {
        return nextActionDate;
    }

    public String getNotes() {
        return notes;
    }

    public String getCurrency() {
        return currency;
    }

    public void updateFlightTracking(Instant newEtd, Instant newEta, Instant actualDeparture, Instant actualArrival, String providerStatus) {
        this.etd = newEtd;
        this.eta = newEta;
        if (actualDeparture != null) {
            this.actualDeparture = actualDeparture;
            this.status = ShipmentStatus.IN_TRANSIT;
        }
        if (actualArrival != null) {
            this.actualArrival = actualArrival;
            this.status = ShipmentStatus.ARRIVED;
        }
        this.updatedAt = Instant.now();
    }

    public String getAirlineUsed() {
        return airlineUsed;
    }

    public BigDecimal getAmountBilledToClient() {
        return amountBilledToClient;
    }

    /**
     * Updates all fields represented by the AAL Command Center shipment
     * worksheet.
     *
     * Calculated values are deliberately NOT accepted from the browser:
     *
     *   chargeable weight
     *   amount remaining
     *   total cost
     *   gross profit
     *   net income
     *   margin
     *
     * are calculated by the server.
     */
    public void updateCommandCenterFields(
            String clientName,
            String contact,
            String commodity,
            String originCountry,
            String originCityPort,
            String destinationCountry,
            String destinationCityPort,
            BigDecimal grossWeightKg,
            BigDecimal volumetricWeightKg,
            Integer packages,
            String airlineUsed,
            String serviceType,
            String operatorName,
            BigDecimal supplierCost,
            BigDecimal otherCost,
            BigDecimal clientRevenue,
            BigDecimal amountPaidByClient,
            BigDecimal amountPaidToSupply,
            BigDecimal otherExpenses,
            String paymentStatus,
            String ownerName,
            String invoiceNo,
            Instant etd,
            Instant eta,
            String nextAction,
            LocalDate nextActionDate,
            String notes,
            String currency
    ) {

        validateNonNegative(
                grossWeightKg,
                "grossWeightKg"
        );

        validateNonNegative(
                volumetricWeightKg,
                "volumetricWeightKg"
        );

        validateNonNegative(
                supplierCost,
                "supplierCost"
        );

        validateNonNegative(
                otherCost,
                "otherCost"
        );

        validateNonNegative(
                clientRevenue,
                "clientRevenue"
        );

        validateNonNegative(
                amountPaidByClient,
                "amountPaidByClient"
        );

        validateNonNegative(
                amountPaidToSupply,
                "amountPaidToSupply"
        );

        validateNonNegative(
                otherExpenses,
                "otherExpenses"
        );

        if (packages != null && packages < 0) {
            throw new IllegalArgumentException(
                    "packages must not be negative"
            );
        }

        if (amountPaidByClient != null
                && clientRevenue != null
                && amountPaidByClient.compareTo(
                        clientRevenue
                ) > 0) {

            throw new IllegalArgumentException(
                    "Amount paid by client cannot exceed amount billed"
            );
        }

        this.clientName = clientName;
        this.contact = contact;
        this.commodity = commodity;

        this.originCountry = originCountry;
        this.originCityPort = originCityPort;

        this.destinationCountry =
                destinationCountry;

        this.destinationCityPort =
                destinationCityPort;

        this.grossWeightKg =
                grossWeightKg;

        this.volumetricWeightKg =
                volumetricWeightKg;

        this.chargeableWeightKg =
                computeChargeable(
                        grossWeightKg,
                        volumetricWeightKg
                );

        this.packages = packages;

        this.airlineUsed = airlineUsed;
        this.serviceType = serviceType;
        this.operatorName = operatorName;

        this.supplierCost = supplierCost;
        this.otherCost = otherCost;

        this.amountBilledToClient = clientRevenue;

        this.amountPaidByClient =
                amountPaidByClient;

        this.amountPaidToSupply =
                amountPaidToSupply;

        this.otherExpenses =
                otherExpenses;

        this.paymentStatus =
                paymentStatus;

        this.ownerName =
                ownerName;

        this.invoiceNo =
                invoiceNo;

        this.etd = etd;
        this.eta = eta;

        this.nextAction =
                nextAction;

        this.nextActionDate =
                nextActionDate;

        this.notes = notes;

        this.currency =
                currency;

        this.updatedAt =
                Instant.now();
    }

    private static void validateNonNegative(
            BigDecimal value,
            String name
    ) {
        if (value != null
                && value.signum() < 0) {

            throw new IllegalArgumentException(
                    name + " must not be negative"
            );
        }
    }

    private static BigDecimal computeChargeable(
            BigDecimal gross,
            BigDecimal volume
    ) {
        if (gross == null
                && volume == null) {
            return null;
        }

        if (gross == null) {
            return volume;
        }

        if (volume == null) {
            return gross;
        }

        return gross.max(volume);
    }

    /**
     * MOTHERSHIP:
     *
     * AMOUNT REMAINING =
     * AMOUNT BILLED TO CLIENT - AMOUNT PAID BY CLIENT
     */
    public BigDecimal getAmountRemaining() {

        if (amountBilledToClient == null) {
            return null;
        }

        return amountBilledToClient.subtract(
                nz(amountPaidByClient)
        );
    }

    /**
     * Command Center total cost.
     *
     * The workbook defines Total Cost as Supplier Cost + Other Cost.
     * MOTHERSHIP Other Expenses remain separate for Net Income.
     */
    public BigDecimal getTotalCost() {

        return nz(supplierCost)
                .add(nz(otherCost));
    }

    /**
     * Command Center gross profit:
     *
     * Client Revenue - Total Cost.
     */
    public BigDecimal getGrossProfit() {

        if (amountBilledToClient == null) {
            return null;
        }

        return amountBilledToClient
                .subtract(getTotalCost());
    }

    /**
     * MOTHERSHIP Net Income logic:
     *
     * Amount Billed
     * - Amount Paid To Supply
     * - Other Expenses
     *
     * This intentionally follows the client's workbook.
     */
    public BigDecimal getNetIncome() {

        if (amountBilledToClient == null) {
            return null;
        }

        return amountBilledToClient
                .subtract(nz(amountPaidToSupply))
                .subtract(nz(otherExpenses));
    }

    public BigDecimal getMarginPercent() {

        BigDecimal revenue =
                amountBilledToClient;

        BigDecimal grossProfit =
                getGrossProfit();

        if (revenue == null
                || revenue.signum() == 0
                || grossProfit == null) {

            return null;
        }

        return grossProfit
                .multiply(BigDecimal.valueOf(100))
                .divide(
                        revenue,
                        4,
                        java.math.RoundingMode.HALF_UP
                );
    }

    private static BigDecimal nz(
            BigDecimal value
    ) {
        return value == null
                ? BigDecimal.ZERO
                : value;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}