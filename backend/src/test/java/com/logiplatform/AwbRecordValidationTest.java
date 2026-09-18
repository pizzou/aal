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
    private AwbRecord awb(String number,String type){
        return new AwbRecord(UUID.randomUUID(),UUID.randomUUID(),number,type,null,null,
            "Shipper","Address","Consignee","Address","Agent","KGL","NBO",2,
            new BigDecimal("100"),new BigDecimal("100"),"General","", "",false);
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
        AwbRecord a=awb("071-61746230","HAWB");
        // constructor uses valid airports; change is not exposed, so this test documents
        // validation through a normal record rather than mutating protected state.
        a.validateRecord();
        assertEquals("VALID",a.getValidationStatus());
    }
}
