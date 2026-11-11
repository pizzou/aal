package com.logiplatform.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Prescribed Rwanda VAT treatments for invoice selection. This class is a
 * legal-category allow-list, not a way for users to invent a new percentage.
 * Applicability must be checked against the actual supply and supporting proof.
 */
public final class RwandaInvoiceTaxPolicy {
    public static final String STANDARD = "RRA-RW-VAT18";
    public static final String EXPORT = "RRA-RW-ZERO-EXPORT";
    public static final String INBOUND_TRANSIT = "RRA-RW-ZERO-TRANSIT";
    public static final String EXEMPT = "RRA-RW-EXEMPT";
    public static final String NONVAT = "RRA-RW-NONVAT";

    private RwandaInvoiceTaxPolicy() { }

    public record Treatment(String code, String label, String description,
                            String legalBasis, BigDecimal rate, boolean requiresEvidence) {
        public Map<String, Object> asMap() {
            return Map.of("code", code, "label", label, "description", description,
                    "legalBasis", legalBasis, "rate", rate,
                    "requiresEvidence", requiresEvidence);
        }
    }

    private static final List<Treatment> TREATMENTS = List.of(
            new Treatment(STANDARD, "Standard VAT — 18%",
                    "Standard taxable goods and services in Rwanda",
                    "Law No. 049/2023, Article 4", new BigDecimal("18.0000"), false),
            new Treatment(EXPORT, "Zero-rated — eligible exports (0%)",
                    "Eligible exported goods, services and qualifying auxiliary services",
                    "Law No. 049/2023, Article 7", BigDecimal.ZERO, true),
            new Treatment(INBOUND_TRANSIT, "Zero-rated — qualifying international freight (0%)",
                    "Eligible international goods transport entering Rwanda, transit cargo and related services",
                    "Law No. 049/2023, Article 7", BigDecimal.ZERO, true),
            new Treatment(EXEMPT, "VAT-exempt supply (0 VAT)",
                    "Only supplies legally exempt under current Rwanda VAT legislation",
                    "Rwanda VAT law — exemption provisions", BigDecimal.ZERO, true),
            new Treatment(NONVAT, "No VAT — company not VAT registered",
                    "Only where Finance has verified that the company is not registered for VAT; EBM invoicing requirements may still apply",
                    "RRA VAT registration and EBM requirements", BigDecimal.ZERO, false)
    );

    public static List<Treatment> treatments() { return TREATMENTS; }

    public static Treatment require(String code, String evidenceReference) {
        String selected = code == null || code.isBlank() ? STANDARD : code.trim().toUpperCase(java.util.Locale.ROOT);
        Treatment rule = TREATMENTS.stream().filter(t -> t.code().equals(selected)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Choose a predefined Rwanda VAT treatment; custom invoice tax rates are not accepted."));
        if (rule.requiresEvidence() && (evidenceReference == null || evidenceReference.isBlank())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "A supporting export, customs or exemption document/reference is required for this treatment.");
        }
        if (evidenceReference != null && evidenceReference.trim().length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Supporting document reference must not exceed 255 characters.");
        }
        return rule;
    }
}
