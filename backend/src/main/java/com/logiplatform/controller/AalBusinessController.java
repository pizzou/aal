package com.logiplatform.controller;

import com.logiplatform.dto.AalBusinessDtos.Cockpit;
import com.logiplatform.dto.AalBusinessDtos.ShipmentFinancial;
import com.logiplatform.service.AalBusinessEngineService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/aal/business")
public class AalBusinessController {
    private final AalBusinessEngineService engine;
    public AalBusinessController(AalBusinessEngineService engine){this.engine=engine;}

    @GetMapping("/cockpit")
    public ResponseEntity<Cockpit> cockpit(@RequestParam(required=false) LocalDate from,
                                           @RequestParam(required=false) LocalDate to,
                                           @RequestParam(required=false) String currency){
        return ResponseEntity.ok(engine.cockpit(from,to,currency));
    }

    @GetMapping("/shipments/{id}/financial")
    public ResponseEntity<ShipmentFinancial> financial(@PathVariable UUID id){
        return ResponseEntity.ok(engine.shipmentFinancial(id));
    }
}
