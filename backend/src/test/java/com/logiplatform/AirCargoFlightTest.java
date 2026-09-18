package com.logiplatform;

import com.logiplatform.model.*;
import com.logiplatform.dto.*;
import com.logiplatform.repository.*;
import com.logiplatform.service.*;
import com.logiplatform.controller.*;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;


import static com.logiplatform.dto.ShipmentDtos.*;
import static com.logiplatform.dto.ReportingDtos.*;
import static com.logiplatform.dto.TmsDtos.*;
import static com.logiplatform.dto.GpsDtos.*;
import static com.logiplatform.dto.LoadPlanDtos.*;
import static com.logiplatform.dto.RatingDtos.*;
import static com.logiplatform.dto.PublicTrackingDtos.*;
import static com.logiplatform.dto.WarehouseDtos.*;
import static com.logiplatform.dto.SensorDtos.*;
import static com.logiplatform.dto.AuthDtos.*;

class AirCargoFlightTest {
    @Test void reservationCannotOversellCapacity(){
        AirCargoFlight f=new AirCargoFlight(UUID.randomUUID(),"ET","Ethiopian","ET900","KGL","NBO",
            Instant.now().plusSeconds(3600),Instant.now().plusSeconds(7200),
            new BigDecimal("1000"),new BigDecimal("1000"),"TEST");
        assertTrue(f.reserve(new BigDecimal("700")));
        assertFalse(f.reserve(new BigDecimal("301")));
        assertEquals(new BigDecimal("300"),f.getAvailableCapacityKg());
    }
}
