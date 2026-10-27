package com.logiplatform.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Locale;

/**
 * Currency-aware VAT calculation shared by invoice preview and invoice issuance.
 *
 * Amounts are rounded to the ISO currency's normal minor-unit precision before
 * the tax split is calculated. For an inclusive amount, the rounded VAT is the
 * difference between the gross amount and rounded taxable base, so base + VAT
 * always equals the customer-facing total. For an exclusive amount, VAT is
 * rounded first and then added to the rounded taxable base.
 */
public final class InvoiceTaxCalculator {
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal ONE = BigDecimal.ONE;

    private InvoiceTaxCalculator() {
    }

    public static Calculation calculate(
            BigDecimal enteredAmount,
            BigDecimal ratePercent,
            BigDecimal withholdingRatePercent,
            String currencyCode,
            boolean taxInclusive) {
        if (enteredAmount == null || enteredAmount.signum() < 0) {
            throw new IllegalArgumentException("Entered invoice amount must be zero or greater");
        }
        validateRate(ratePercent, "Tax rate");
        validateRate(withholdingRatePercent, "Withholding rate");
        int scale = currencyScale(currencyCode);
        BigDecimal amount = enteredAmount.setScale(scale, RoundingMode.HALF_UP);
        BigDecimal rate = ratePercent.divide(ONE_HUNDRED, 16, RoundingMode.HALF_UP);
        BigDecimal withholdingRate = withholdingRatePercent.divide(ONE_HUNDRED, 16, RoundingMode.HALF_UP);

        BigDecimal taxableBase;
        BigDecimal tax;
        BigDecimal total;
        if (taxInclusive) {
            total = amount;
            taxableBase = rate.signum() == 0
                    ? total
                    : total.divide(ONE.add(rate), scale, RoundingMode.HALF_UP);
            // Use the residual so the rounded tax and base reconcile exactly to gross.
            tax = total.subtract(taxableBase).setScale(scale, RoundingMode.HALF_UP);
        } else {
            taxableBase = amount;
            tax = taxableBase.multiply(rate).setScale(scale, RoundingMode.HALF_UP);
            total = taxableBase.add(tax).setScale(scale, RoundingMode.HALF_UP);
        }

        BigDecimal withholding = taxableBase.multiply(withholdingRate)
                .setScale(scale, RoundingMode.HALF_UP);
        BigDecimal netPayable = total.subtract(withholding).setScale(scale, RoundingMode.HALF_UP);
        return new Calculation(taxableBase, tax, withholding, total, netPayable, scale);
    }

    public static int currencyScale(String currencyCode) {
        if (currencyCode == null || !currencyCode.trim().matches("[A-Za-z]{3}")) {
            throw new IllegalArgumentException("A three-letter currency code is required");
        }
        try {
            int digits = Currency.getInstance(currencyCode.trim().toUpperCase(Locale.ROOT))
                    .getDefaultFractionDigits();
            // ISO currencies with an unspecified fraction count use the system's
            // conventional two-decimal fallback rather than an arbitrary four.
            return digits >= 0 ? digits : 2;
        } catch (IllegalArgumentException ex) {
            // Some logistics deployments use ISO-like settlement codes not known
            // to the current JDK currency table. Preserve them at conventional
            // two-decimal precision while still validating the code's shape.
            return 2;
        }
    }

    private static void validateRate(BigDecimal value, String name) {
        if (value == null || value.signum() < 0 || value.compareTo(ONE_HUNDRED) > 0) {
            throw new IllegalArgumentException(name + " must be between zero and 100 percent");
        }
    }

    public record Calculation(
            BigDecimal taxableAmount,
            BigDecimal taxAmount,
            BigDecimal withholdingAmount,
            BigDecimal totalAmount,
            BigDecimal netPayable,
            int currencyScale) {
    }
}
