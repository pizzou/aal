package com.logiplatform.model;

/**
 * Append-only operational timeline vocabulary. Carrier/provider events and
 * status-driven events share the same persisted audit stream.
 */
public enum TrackingEventType {
    CREATED,
    PENDING,
    PLANNING,
    BOOKED,
    PICKED_UP,
    DEPARTED_ORIGIN,
    IN_TRANSIT,
    CUSTOMS_HOLD,
    CUSTOMS_CLEARED,
    ARRIVED_DESTINATION,
    ARRIVED_AT_HUB,
    OUT_FOR_DELIVERY,
    DELIVERED,
    COMPLETED,
    ON_HOLD,
    CANCELLED,
    EXCEPTION
}
