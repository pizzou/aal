package com.logiplatform.controller;

import com.logiplatform.model.ShipmentEtaHistory;
import com.logiplatform.service.FlightDelayCheckService;
import com.logiplatform.service.ShipmentEtaTrackingService;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/shipments/{shipmentId}/eta")
public class ShipmentEtaController {
    private final FlightDelayCheckService status; private final ShipmentEtaTrackingService eta;
    public ShipmentEtaController(FlightDelayCheckService status,ShipmentEtaTrackingService eta){this.status=status;this.eta=eta;}
    @PostMapping("/refresh") public Object refresh(@PathVariable UUID shipmentId){return status.checkAndFlagDelay(shipmentId);}
    @GetMapping("/history") public List<ShipmentEtaHistory> history(@PathVariable UUID shipmentId){return eta.history(shipmentId);}
}
