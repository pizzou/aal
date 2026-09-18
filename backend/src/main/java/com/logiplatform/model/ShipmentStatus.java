package com.logiplatform.model;

/**
 * Operational shipment lifecycle used by Africa Logistic Aviation.
 *
 * These values preserve the operational statuses used in the AAL
 * MOTHERSHIP / Command Center workbooks instead of collapsing them into
 * only PENDING / IN_TRANSIT / DELIVERED.
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

    OUT_FOR_DELIVERY,

    DELIVERED,

    COMPLETED,

    ON_HOLD,

    CANCELLED
}