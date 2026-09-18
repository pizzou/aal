package com.logiplatform.model;

/**
 * Deliberately mode-agnostic: the same vocabulary describes a truck leaving a
 * depot, a flight departing an airport, or a vessel leaving a port. A real
 * high-end system would eventually add mode-specific sub-detail (flight number
 * on DEPARTED_ORIGIN for AIR, terminal on ARRIVED_DESTINATION for SEA) via the
 * `location`/`notes` fields, without needing a different event vocabulary per mode.
 */
public enum TrackingEventType {
    BOOKED,
    PICKED_UP,
    DEPARTED_ORIGIN,
    IN_TRANSIT,
    CUSTOMS_HOLD,
    ARRIVED_DESTINATION,
    OUT_FOR_DELIVERY,
    DELIVERED,
    EXCEPTION
}
