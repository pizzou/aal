package com.logiplatform.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Shipment lifecycle. Statuses describe the physical/operational state of a
 * consignment; transitions are deliberately guarded so APIs and internal jobs
 * cannot silently move a shipment backwards or deliver it without proof.
 */
public enum ShipmentStatus {
    PENDING,
    PLANNING,
    BOOKED,
    PICKED_UP,
    DEPARTED,
    IN_TRANSIT,
    ARRIVED,
    CUSTOMS,
    CUSTOMS_CLEARED,
    OUT_FOR_DELIVERY,
    DELIVERED,
    COMPLETED,
    ON_HOLD,
    CANCELLED;

    private static final Set<ShipmentStatus> PRE_DELIVERY = EnumSet.of(
            PENDING, PLANNING, BOOKED, PICKED_UP, DEPARTED, IN_TRANSIT,
            ARRIVED, CUSTOMS, CUSTOMS_CLEARED, OUT_FOR_DELIVERY, ON_HOLD);

    /**
     * Whether the requested transition is a valid forward or exception flow.
     * Re-applying the current status is idempotent and is intentionally allowed.
     */
    public boolean canTransitionTo(ShipmentStatus next) {
        if (next == null) return false;
        if (next == this) return true;
        if (next == CANCELLED) return this != COMPLETED && this != CANCELLED && this != DELIVERED;
        if (next == ON_HOLD) return PRE_DELIVERY.contains(this) && this != ON_HOLD;

        return switch (this) {
            case PENDING -> next == PLANNING || next == BOOKED;
            case PLANNING -> next == PENDING || next == BOOKED;
            case BOOKED -> next == PICKED_UP || next == DEPARTED || next == IN_TRANSIT || next == ARRIVED;
            case PICKED_UP -> next == DEPARTED || next == IN_TRANSIT || next == ARRIVED;
            case DEPARTED -> next == IN_TRANSIT || next == ARRIVED || next == CUSTOMS;
            case IN_TRANSIT -> next == ARRIVED || next == CUSTOMS || next == CUSTOMS_CLEARED;
            case ARRIVED -> next == IN_TRANSIT || next == CUSTOMS || next == CUSTOMS_CLEARED
                    || next == OUT_FOR_DELIVERY;
            case CUSTOMS -> next == CUSTOMS_CLEARED || next == IN_TRANSIT;
            case CUSTOMS_CLEARED -> next == IN_TRANSIT || next == OUT_FOR_DELIVERY;
            case OUT_FOR_DELIVERY -> next == DELIVERED || next == IN_TRANSIT;
            case DELIVERED -> next == COMPLETED;
            case COMPLETED, CANCELLED -> false;
            case ON_HOLD -> next == PENDING || next == PLANNING || next == BOOKED
                    || next == PICKED_UP || next == DEPARTED || next == IN_TRANSIT
                    || next == ARRIVED || next == CUSTOMS || next == CUSTOMS_CLEARED;
        };
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED;
    }

    public boolean isDeliveredOrBeyond() {
        return this == DELIVERED || this == COMPLETED;
    }
}
