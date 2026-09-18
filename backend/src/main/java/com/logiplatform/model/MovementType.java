package com.logiplatform.model;

public enum MovementType {
    IN(1),               // stock received into the warehouse
    OUT(-1),             // stock removed (e.g. picked for a shipment)
    ADJUSTMENT_IN(1),    // correction after a physical count found MORE stock than recorded
    ADJUSTMENT_OUT(-1);  // correction after a physical count found LESS stock than recorded

    private final int sign;

    MovementType(int sign) {
        this.sign = sign;
    }

    public int signedQuantity(int magnitude) {
        return sign * magnitude;
    }
}
