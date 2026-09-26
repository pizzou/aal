package com.logiplatform.controller.v1;

import com.logiplatform.service.control.DatabaseConsistencyAuditService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/data-quality")
public class DataQualityV1Controller {
    private final DatabaseConsistencyAuditService audit;
    public DataQualityV1Controller(DatabaseConsistencyAuditService audit){this.audit=audit;}
    @GetMapping("/database-consistency") public Map<String,Object> databaseConsistency(){return audit.audit();}
}
