package com.logiplatform;

import com.logiplatform.service.EnterpriseCompletionService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class EnterpriseCompletionServiceTest {
    @Test
    void checklistContainsAllMasterPhasesAndNoDuplicatePhaseIds() {
        EnterpriseCompletionService service =
                new EnterpriseCompletionService(mock(JdbcTemplate.class), "test-jwt-secret-that-is-at-least-32-bytes", "");

        List<Map<String, Object>> phases = service.checklist();

        assertEquals(19, phases.size());
        assertEquals("0", phases.get(0).get("phase"));
        assertEquals("18", phases.get(18).get("phase"));

        long distinct = phases.stream()
                .map(p -> String.valueOf(p.get("phase")))
                .distinct()
                .count();
        assertEquals(phases.size(), distinct);

        assertTrue(phases.stream().allMatch(p -> p.get("items") instanceof List<?>));
        assertTrue(phases.stream().anyMatch(p ->
                "5".equals(p.get("phase"))
                        && String.valueOf(p.get("status")).contains("EXTERNAL")));
    }
}
