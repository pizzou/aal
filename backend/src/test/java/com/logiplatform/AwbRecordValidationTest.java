package com.logiplatform;

import com.logiplatform.model.*;
import com.logiplatform.dto.*;
import com.logiplatform.repository.*;
import com.logiplatform.service.*;
import com.logiplatform.controller.*;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
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

class AwbRecordValidationTest {
    private AwbRecord awb(String number, String type) {
        return awb(number, type, null, "KGL", "NBO");
    }

    private AwbRecord awb(String number, String type, String mawb, String origin, String destination) {
        return new AwbRecord(UUID.randomUUID(), UUID.randomUUID(), number, type, mawb,
            type.equalsIgnoreCase("HAWB") ? number : null,
            "Shipper", "Address", "Consignee", "Address", "Agent", origin, destination, 2,
            new BigDecimal("100"), new BigDecimal("100"), "General", "", "", false);
    }
    @Test void validMawbCheckDigitAccepted(){
        AwbRecord a=awb("071-61746230","MAWB");
        a.validateRecord();
        assertEquals("VALID",a.getValidationStatus());
        assertEquals("071-61746230",a.getAwbNumber());
    }
    @Test void invalidMawbCheckDigitRejected(){
        AwbRecord a=awb("071-61746231","MAWB");
        assertThrows(IllegalArgumentException.class,a::validateRecord);
    }
    @Test void invalidAirportRejected(){
        // Supply a valid parent MAWB so this assertion reaches airport validation.
        AwbRecord a = awb("HAWB-0001", "HAWB", "071-61746230", "KG", "NBO");
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, a::validateRecord);
        assertTrue(exception.getMessage().contains("airport codes"));
    }

    @Test void mawbPrefixAndEightDigitSerialAreSeparated() {
        AwbRecord a = awb("071-61746230", "MAWB");
        a.validateRecord();
        assertEquals("071", a.getAirlinePrefix());
        assertEquals("61746230", a.getAirlineSerial());
    }

    @Test void chargeableWeightUsesGreaterOfGrossAndVolumetric() {
        AwbRecord a = awb("071-61746230", "MAWB");
        a.applyExtendedDetails(null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            "NBO", "5Y", null, null, "RWF", "PPD", "G", "K", null, null,
            new BigDecimal("300"), new BigDecimal("200"), new BigDecimal("100"), "General cargo", null, "NVD", null, "NCV", null, null, null);
        a.validateRecord();
        assertEquals(new BigDecimal("1000.000"), a.getVolumetricWeightKg());
        assertEquals(new BigDecimal("1000.000"), a.getChargeableWeightKg());
        assertEquals("5Y", a.getFirstByCarrier());
    }

    @Test void partialDimensionsAreRejected() {
        AwbRecord a = awb("071-61746230", "MAWB");
        assertThrows(IllegalArgumentException.class, () -> a.applyExtendedDetails(null, null,
            null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null, null,
            new BigDecimal("100"), null, null, null, null, null, null, null, null, null, null));
    }
}
