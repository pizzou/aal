package com.logiplatform.service;

import com.logiplatform.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Test the server-owned reference contract without a live database.
 * Full concurrent allocation and RLS behavior still require PostgreSQL integration tests.
 */
class CommercialDocumentSequenceTest {
    private static class SequenceJdbc extends JdbcTemplate {
        final List<String> sql = new ArrayList<>();
        @Override
        public int update(String query, Object... args) {
            sql.add(query);
            return 1;
        }
        @Override
        public <T> T queryForObject(String query, Class<T> requiredType, Object... args) {
            sql.add(query);
            if (requiredType == UUID.class) return requiredType.cast(UUID.randomUUID());
            if (requiredType == Long.class) return requiredType.cast(query.contains("RETURNING last_value") ? 7L : 6L);
            throw new IllegalArgumentException("Unexpected query: " + query);
        }
    }
    private final UUID tenant = UUID.randomUUID();
    @BeforeEach void setup() { TenantContext.setTenantId(tenant); }
    @AfterEach void cleanup() { TenantContext.clear(); }

    @Test void everyCommercialReferenceIsIssuedByTheServer() {
        SequenceJdbc db = new SequenceJdbc();
        FinanceDocumentSequenceService sequences = new FinanceDocumentSequenceService(db);
        int year = java.time.LocalDate.now().getYear();
        assertEquals("AAL-CUS-" + year + "-000007", sequences.nextCustomerNumber());
        assertEquals("AAL-QT-" + year + "-000007", sequences.nextQuoteNumber());
        assertEquals("AAL-TSK-" + year + "-000007", sequences.nextTaskNumber());
        assertEquals("AAL-EXP-" + year + "-000007", sequences.nextExpenseNumber());
        assertEquals("AAL-PTN-" + year + "-000007", sequences.nextPartnerNumber());
        assertEquals(5, db.sql.stream().filter(s -> s.contains("RETURNING last_value")).count());
        assertEquals(5, db.sql.stream().filter(s -> s.contains("FOR UPDATE")).count());
        assertEquals(5, db.sql.stream().filter(s -> s.contains("MAX(")).count());
    }

    @Test void tenantContextIsRequiredBeforeIssuingAReference() {
        TenantContext.clear();
        FinanceDocumentSequenceService sequences = new FinanceDocumentSequenceService(new SequenceJdbc());
        assertThrows(IllegalStateException.class, sequences::nextQuoteNumber);
    }
}
