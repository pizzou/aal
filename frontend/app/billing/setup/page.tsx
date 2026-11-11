"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { apiFetch } from "@/lib/api-client";
import { useAuth } from "@/lib/auth-context";

type Jurisdiction = {
  jurisdiction_code?: string;
  legal_name?: string;
  tax_registration_no?: string | null;
  country_code?: string;
  address?: string | null;
  invoice_prefix?: string | null;
  currency?: string;
  active?: boolean;
  default_for_invoicing?: boolean;
  vat_registration_status?: "REGISTERED" | "NOT_REGISTERED" | "UNVERIFIED";
};

type InvoiceReadiness = {
  ready: boolean;
  message?: string;
  jurisdictionCode?: string | null;
  taxCode?: string | null;
  taxRate?: number | null;
};

type TaxRule = {
  code: string;
  tax_type: string;
  rate: number;
  inclusive: boolean;
  currency?: string | null;
  active: boolean;
};

type CompanyForm = {
  code: string;
  legalName: string;
  registrationNo: string;
  countryCode: string;
  address: string;
  invoicePrefix: string;
  currency: string;
};

const DEFAULT_COMPANY: CompanyForm = {
  code: "RW",
  legalName: "",
  registrationNo: "",
  countryCode: "RW",
  address: "",
  invoicePrefix: "AAL-INV",
  // Keep the existing AAL invoice currency as the starting suggestion;
  // the finance user must select the currency actually used by the company.
  currency: "USD",
};

function errorMessage(error: unknown): string {
  return error instanceof Error
    ? error.message
    : "Unable to contact the server. Please try again.";
}

export default function InvoiceTaxSetupPage() {
  const { role } = useAuth();
  const canEdit = role === "ADMIN" || role === "FINANCE";
  const [company, setCompany] = useState<CompanyForm>(DEFAULT_COMPANY);
  const [configuredCompany, setConfiguredCompany] =
    useState<Jurisdiction | null>(null);
  const [readiness, setReadiness] = useState<InvoiceReadiness | null>(null);
  const [activeVat, setActiveVat] = useState<TaxRule | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState(false);
  const [verified, setVerified] = useState(false);
  const [vatRegistration, setVatRegistration] = useState<
    "REGISTERED" | "NOT_REGISTERED" | ""
  >("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  async function loadSetup(): Promise<boolean> {
    setLoading(true);
    setError("");
    try {
      const jurisdictions = await apiFetch<Jurisdiction[]>(
        "/api/finance/hardening/tax-jurisdictions",
        { cache: "no-store" },
      );
      const current =
        jurisdictions.find((row) => row.active && row.default_for_invoicing) ??
        null;
      setConfiguredCompany(current);
      setVatRegistration(
        current?.vat_registration_status === "REGISTERED" ||
          current?.vat_registration_status === "NOT_REGISTERED"
          ? current.vat_registration_status
          : "",
      );
      setCompany(
        current
          ? {
              code: current.jurisdiction_code || "RW",
              legalName: current.legal_name || "",
              registrationNo: current.tax_registration_no || "",
              countryCode: current.country_code || "RW",
              address: current.address || "",
              invoicePrefix: current.invoice_prefix || "AAL-INV",
              currency: current.currency || "USD",
            }
          : DEFAULT_COMPANY,
      );

      // This is a read-only server check; setup is never silently activated.
      const billingCurrency = (current?.currency || "USD").toUpperCase();
      const result = await apiFetch<InvoiceReadiness>(
        `/api/billing/invoice-readiness?currency=${encodeURIComponent(billingCurrency)}`,
        { cache: "no-store" },
      );
      setReadiness(result);
      if (current?.jurisdiction_code) {
        const rules = await apiFetch<TaxRule[]>(
          `/api/finance/hardening/tax-rules?jurisdictionCode=${encodeURIComponent(current.jurisdiction_code)}`,
          { cache: "no-store" },
        );
        setActiveVat(
          rules.find(
            (rule) => rule.code === result.taxCode && rule.tax_type === "VAT",
          ) ??
            rules.find(
              (rule) =>
                rule.active && rule.tax_type === "VAT" && !rule.currency,
            ) ??
            null,
        );
      } else {
        setActiveVat(null);
      }
      return true;
    } catch (exception) {
      setReadiness(null);
      setActiveVat(null);
      setError(errorMessage(exception));
      return false;
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadSetup();
  }, []);

  function update<K extends keyof CompanyForm>(key: K, value: CompanyForm[K]) {
    setCompany((previous) => ({ ...previous, [key]: value }));
    setVerified(false);
    setNotice("");
  }

  async function saveCompany() {
    if (!canEdit || saving) return;
    const code = company.code.trim().toUpperCase();
    const countryCode = company.countryCode.trim().toUpperCase();
    const currency = company.currency.trim().toUpperCase();
    const prefix = company.invoicePrefix.trim().toUpperCase();
    if (!company.legalName.trim() || !code || !countryCode || !currency) {
      setError("Enter the legal company name, country and invoice currency.");
      return;
    }
    if (!/^[A-Z]{2,3}$/.test(countryCode) || !/^[A-Z]{3}$/.test(currency)) {
      setError(
        "Use a valid country code (RW) and three-letter currency (USD or RWF).",
      );
      return;
    }
    if (
      !/^[A-Z0-9._-]{1,80}$/.test(code) ||
      (prefix && !/^[A-Z0-9][A-Z0-9-]{0,38}$/.test(prefix))
    ) {
      setError(
        "Check the jurisdiction code and invoice prefix; use letters, numbers and hyphens.",
      );
      return;
    }
    if (countryCode === "RW" && !vatRegistration) {
      setError("Select whether the company is registered for VAT with RRA.");
      return;
    }
    if (!verified) {
      setError(
        "Confirm that the company details and its VAT status were verified.",
      );
      return;
    }
    setSaving(true);
    setError("");
    setNotice("");
    try {
      await apiFetch("/api/finance/hardening/tax-jurisdictions", {
        method: "POST",
        body: JSON.stringify({
          ...company,
          code,
          countryCode,
          currency,
          invoicePrefix: prefix,
          active: true,
          defaultForInvoicing: true,
        }),
      });
      if (countryCode === "RW") {
        await apiFetch("/api/finance/hardening/rwanda-vat-registration", {
          method: "POST",
          body: JSON.stringify({
            registered: vatRegistration === "REGISTERED",
          }),
        });
      }
      // Legal VAT treatments are provisioned by the backend; no rate entry.
      const verifiedOnServer = await loadSetup();
      setEditing(false);
      setVerified(false);
      if (verifiedOnServer) {
        setNotice(
          "Company invoice details saved. Check the VAT summary, then continue to an invoice preview.",
        );
      }
    } catch (exception) {
      setError(errorMessage(exception));
    } finally {
      setSaving(false);
    }
  }

  const ready = readiness?.ready === true;

  return (
    <main className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow">AAL / COMPANY SETTINGS</div>
          <h1 className="page-title">Company invoice setup</h1>
          <p className="page-subtitle">
            Set up your company once. After that, staff can create invoices
            without repeating these details.
          </p>
        </div>
        <div className="actions">
          <Link href="/billing" className="btn">
            Invoices &amp; payments
          </Link>
          <button
            type="button"
            className="btn"
            disabled={loading || saving}
            onClick={() => void loadSetup()}
          >
            Check setup again
          </button>
        </div>
      </div>

      {error && (
        <div className="alert alert-error aal-alert" role="alert">
          {error}
        </div>
      )}
      {notice && (
        <div className="alert alert-success aal-alert" role="status">
          {notice}
        </div>
      )}

      <section
        className="form-panel"
        style={{ marginBottom: 16 }}
        aria-label="Invoice setup status"
      >
        <div className="section-heading">
          <div className="section-number">1</div>
          <div>
            <h2>Is invoicing ready?</h2>
            <p>
              Checked directly against the company and VAT configuration saved
              on the server.
            </p>
          </div>
        </div>
        {loading ? (
          <p>Checking company invoice settings…</p>
        ) : error && !readiness ? (
          <p>Unable to verify the setup. Check the connection and try again.</p>
        ) : ready ? (
          <div className="alert alert-success" role="status">
            <strong>Ready for invoice previews.</strong> Company:{" "}
            {configuredCompany?.legal_name || readiness?.jurisdictionCode}.
            {readiness?.taxRate != null
              ? ` Active VAT rate: ${readiness.taxRate}%.`
              : ""}
          </div>
        ) : (
          <div className="alert alert-error" role="status">
            <strong>Setup is not complete.</strong>{" "}
            {readiness?.message ||
              "Save the company's verified invoicing details to continue."}
          </div>
        )}
        <p className="form-hint">
          This check does not issue an invoice. The system will calculate and
          verify tax again for each shipment.
        </p>
        {!canEdit && (
          <p className="form-hint">
            Only Admin and Finance can edit these settings. Ask one of them to
            complete the setup.
          </p>
        )}
      </section>

      <section
        className="form-panel"
        style={{ marginBottom: 16 }}
        aria-label="Company invoice details"
      >
        <div className="section-heading">
          <div className="section-number">2</div>
          <div>
            <h2>Company details</h2>
            <p>
              Use the company's actual registered details; they are shared
              across new invoices.
            </p>
          </div>
        </div>
        {configuredCompany && !editing ? (
          <>
            <p>
              <strong>{configuredCompany.legal_name}</strong>
            </p>
            <p className="form-hint">
              TIN: {configuredCompany.tax_registration_no || "Not entered"} ·
              VAT registration:{" "}
              {configuredCompany.vat_registration_status === "REGISTERED"
                ? "Registered with RRA"
                : configuredCompany.vat_registration_status === "NOT_REGISTERED"
                  ? "Not VAT registered"
                  : "Not verified"}{" "}
              · Country: {configuredCompany.country_code} · Currency:{" "}
              {configuredCompany.currency} · Invoice prefix:{" "}
              {configuredCompany.invoice_prefix || "Not set"}
            </p>
            {canEdit && (
              <button
                type="button"
                className="btn"
                onClick={() => {
                  setEditing(true);
                  setVerified(false);
                  setNotice("");
                }}
              >
                Edit company details
              </button>
            )}
          </>
        ) : (
          <>
            <div className="form-grid">
              <label className="field field-span-2">
                <span>Registered company name *</span>
                <input
                  value={company.legalName}
                  placeholder="Enter the name on your registration certificate"
                  disabled={!canEdit || saving}
                  onChange={(event) => update("legalName", event.target.value)}
                />
              </label>
              <label className="field">
                <span>Tax registration / TIN (if applicable)</span>
                <input
                  value={company.registrationNo}
                  placeholder="Company's verified number"
                  disabled={!canEdit || saving}
                  onChange={(event) =>
                    update("registrationNo", event.target.value)
                  }
                />
              </label>
              <label className="field">
                <span>Invoice currency *</span>
                <input
                  list="invoice-currency-options"
                  value={company.currency}
                  maxLength={3}
                  disabled={!canEdit || saving}
                  onChange={(event) =>
                    update("currency", event.target.value.toUpperCase())
                  }
                />
                <datalist id="invoice-currency-options">
                  <option value="USD" />
                  <option value="RWF" />
                  <option value="EUR" />
                </datalist>
                <small>
                  Use the company's normal invoice currency, e.g. USD or RWF.
                </small>
              </label>
              <label className="field">
                <span>Invoice number prefix</span>
                <input
                  value={company.invoicePrefix}
                  placeholder="AAL-INV"
                  disabled={!canEdit || saving}
                  onChange={(event) =>
                    update("invoicePrefix", event.target.value)
                  }
                />
              </label>
            </div>
            <details style={{ marginTop: 12 }}>
              <summary style={{ cursor: "pointer", fontWeight: 600 }}>
                More company settings (optional)
              </summary>
              <div className="form-grid" style={{ marginTop: 12 }}>
                <label className="field">
                  <span>Country code</span>
                  <input
                    value={company.countryCode}
                    maxLength={3}
                    disabled={!canEdit || saving}
                    onChange={(event) =>
                      update("countryCode", event.target.value.toUpperCase())
                    }
                  />
                  <small>RW means Rwanda.</small>
                </label>
                <label className="field">
                  <span>Jurisdiction code</span>
                  <input
                    value={company.code}
                    maxLength={80}
                    disabled={!canEdit || saving}
                    onChange={(event) =>
                      update("code", event.target.value.toUpperCase())
                    }
                  />
                  <small>
                    Keep RW for a new Rwanda company; preserve the existing code
                    when editing.
                  </small>
                </label>
                <label className="field field-span-2">
                  <span>Registered address</span>
                  <input
                    value={company.address}
                    disabled={!canEdit || saving}
                    onChange={(event) => update("address", event.target.value)}
                  />
                </label>
              </div>
            </details>
            {company.countryCode.toUpperCase() === "RW" && (
              <label className="field" style={{ marginTop: 14 }}>
                <span>RRA VAT registration (verify once) *</span>
                <select
                  value={vatRegistration}
                  disabled={!canEdit || saving}
                  onChange={(event) => {
                    setVatRegistration(
                      event.target.value as typeof vatRegistration,
                    );
                    setVerified(false);
                  }}
                >
                  <option value="">Select verified company status</option>
                  <option value="REGISTERED">
                    Company is VAT registered with RRA
                  </option>
                  <option value="NOT_REGISTERED">
                    Company is not VAT registered
                  </option>
                </select>
                <small>
                  Check the company's official RRA registration. A TIN alone
                  does not establish VAT registration.
                </small>
              </label>
            )}
            <label className="field" style={{ marginTop: 14 }}>
              <span>
                <input
                  type="checkbox"
                  checked={verified}
                  disabled={!canEdit || saving}
                  onChange={(event) => setVerified(event.target.checked)}
                  style={{ marginRight: 8 }}
                />
                I checked the company's legal details and confirmed its VAT
                status with the authorized finance person.
              </span>
            </label>
            <div className="actions" style={{ marginTop: 14 }}>
              <button
                type="button"
                className="btn btn-primary"
                disabled={
                  !canEdit ||
                  loading ||
                  saving ||
                  !verified ||
                  !company.legalName.trim()
                }
                onClick={() => void saveCompany()}
              >
                {saving ? "Saving company…" : "Save company setup"}
              </button>
              {configuredCompany && (
                <button
                  type="button"
                  className="btn"
                  disabled={saving}
                  onClick={() => {
                    setEditing(false);
                    setVerified(false);
                    void loadSetup();
                  }}
                >
                  Cancel
                </button>
              )}
            </div>
          </>
        )}
      </section>

      <section
        className="form-panel"
        style={{ marginBottom: 16 }}
        aria-label="VAT configuration"
      >
        <div className="section-heading">
          <div className="section-number">3</div>
          <div>
            <h2>VAT settings</h2>
            <p>
              RRA statutory choices are built in; staff never type VAT
              percentages when invoicing.
            </p>
          </div>
        </div>
        {ready && activeVat ? (
          <p>
            Verified default:{" "}
            <strong>
              {readiness?.taxCode === "RRA-RW-NONVAT"
                ? "No VAT (not VAT registered)"
                : "18% standard VAT"}
            </strong>
            . Eligible zero-rated and exempt services are chosen from the
            statutory list on each invoice.
          </p>
        ) : (
          <p className="form-hint">
            First verify the company's RRA VAT registration above. The backend
            will activate the applicable statutory default.
          </p>
        )}
        <p className="form-hint">
          RRA VAT treatment options are preset: standard 18%, eligible
          zero-rated exports or inbound/transit freight, and legally exempt
          supplies. Non-VAT-registered companies may not charge 18% VAT. All
          invoices still need to comply with RRA EBM/electronic invoicing
          obligations.
        </p>
        {canEdit && company.countryCode.toUpperCase() !== "RW" && (
          <Link className="btn" href="/billing/hardening">
            Manage local tax rules
          </Link>
        )}
      </section>

      <div className="actions" style={{ marginTop: 16 }}>
        {ready ? (
          <Link className="btn btn-primary" href="/billing">
            Continue to customer invoicing →
          </Link>
        ) : (
          <span className="form-hint">
            Complete and verify company setup before creating an invoice.
          </span>
        )}
      </div>
    </main>
  );
}
