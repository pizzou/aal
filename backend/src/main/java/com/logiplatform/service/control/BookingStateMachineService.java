package com.logiplatform.service.control;

import com.logiplatform.model.AirCargoBooking;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.logiplatform.repository.AirCargoBookingRepository;

import java.util.*;

@Service
public class BookingStateMachineService {
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "DRAFT", Set.of("REQUESTED", "FAILED"),
            "REQUESTED", Set.of("PENDING_PROVIDER", "FAILED", "UNKNOWN"),
            "PENDING_PROVIDER", Set.of("CONFIRMED", "FAILED", "UNKNOWN", "AMENDMENT_PENDING", "CANCELLATION_PENDING"),
            "UNKNOWN", Set.of("RECONCILING", "CONFIRMED", "FAILED", "CANCELLED"),
            "RECONCILING", Set.of("CONFIRMED", "FAILED", "UNKNOWN"),
            "CONFIRMED", Set.of("AMENDMENT_PENDING", "CANCELLATION_PENDING", "CANCELLED"),
            "AMENDMENT_PENDING", Set.of("CONFIRMED", "UNKNOWN", "FAILED"),
            "CANCELLATION_PENDING", Set.of("CANCELLED", "UNKNOWN", "FAILED"),
            "FAILED", Set.of("REQUESTED", "PENDING_PROVIDER", "RECONCILING"),
            "CANCELLED", Set.of());
    private final JdbcTemplate db;
    private final AirCargoBookingRepository bookings;

    public BookingStateMachineService(@Qualifier("tenantJdbcTemplate") JdbcTemplate db,
            AirCargoBookingRepository bookings) {
        this.db = db;
        this.bookings = bookings;
    }

    @Transactional
    public void transition(AirCargoBooking booking, String target, String reason, String correlationId) {
        String from = booking.getStatus() == null ? "DRAFT" : booking.getStatus().toUpperCase(Locale.ROOT);
        String to = target.toUpperCase(Locale.ROOT);
        if (from.equals(to))
            return;
        if (!ALLOWED.getOrDefault(from, Set.of()).contains(to))
            throw new IllegalStateException("Invalid booking state transition: " + from + " -> " + to);
        db.update("UPDATE air_cargo_bookings SET status=?,updated_at=now() WHERE tenant_id=? AND id=?", to,
                TenantContext.getTenantId(), booking.getId());
        booking.setStateInternal(to);
        db.update(
                "INSERT INTO booking_state_transitions(id,tenant_id,booking_id,from_state,to_state,reason,correlation_id) VALUES(gen_random_uuid(),?,?,?,?,?,?)",
                TenantContext.getTenantId(), booking.getId(), from, to, reason, correlationId);
    }

    public boolean canTransition(String from, String to) {
        return ALLOWED.getOrDefault(from.toUpperCase(Locale.ROOT), Set.of()).contains(to.toUpperCase(Locale.ROOT));
    }
}
