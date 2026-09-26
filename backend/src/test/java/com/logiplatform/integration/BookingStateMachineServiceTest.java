package com.logiplatform.integration;

import com.logiplatform.service.control.BookingStateMachineService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

class BookingStateMachineServiceTest {
    private final BookingStateMachineService machine=new BookingStateMachineService(org.mockito.Mockito.mock(JdbcTemplate.class),org.mockito.Mockito.mock(com.logiplatform.repository.AirCargoBookingRepository.class));
    @Test void unknownCanReconcile(){assertTrue(machine.canTransition("UNKNOWN","RECONCILING"));assertTrue(machine.canTransition("RECONCILING","CONFIRMED"));assertTrue(machine.canTransition("RECONCILING","FAILED"));}
    @Test void timeoutMustNotBeCollapsedIntoFailure(){assertTrue(machine.canTransition("PENDING_PROVIDER","UNKNOWN"));assertFalse(machine.canTransition("CANCELLED","CONFIRMED"));}
}
