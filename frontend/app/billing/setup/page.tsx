"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
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
};
type TaxRule = {
  id: string;
  code: string;
  tax_name: string;
  rate: number;
  withholding_rate: number;
  valid_from: string;
  valid_until?: string | null;
  currency?: string | null;
  tax_type: string;
  inclusive: boolean;
  active: boolean;
};
type Readiness = {
  ready: boolean;
  message: string;
  jurisdictionCode?: string | null;
  taxCode?: string | null;
  taxRate?: number | null;
};

const base = "/api/finance/hardening";
const message = (e: unknown) => e instanceof Error ? e.message : "Unable to contact the finance service.";

export default function InvoiceTaxSetupPage() {
  const router = useRouter();
  const { role } = useAuth();
  const allowed = ["ADMIN", "FINANCE"].includes(role || "");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");
  const [status, setStatus] = useState<Readiness | null>(null);
  const [company, setCompany] = useState({
    code: "", legalName: "", registrationNo: "", countryCode: "",
    address: "", invoicePrefix: "AAL-INV", currency: "USD",
  });
  const [confirmed, setConfirmed] = useState(false);
  const [jurisdictions, setJurisdictions] = useState<Jurisdiction[]>([]);
  const [rule, setRule] = useState<TaxRule | null>(null);
  const [inclusive, setInclusive] = useState(false);

  async function refresh() {
    setLoading(true);
    setError("");
    try {
      const rows = await apiFetch<Jurisdiction[]>(`${base}/tax-jurisdictions`, { cache: "no-store" });
      setJurisdictions(rows);
      const active = rows.find(j => j.active && j.default_for_invoicing);
      // Readiness is currency-specific. Verify the actual configured issuer
      // currency instead of mixing different effective tax rules.
      const checkedCurrency = (active?.currency || company.currency || "USD").trim().toUpperCase();
      const readiness = await apiFetch<Readiness>(
        `/api/billing/invoice-readiness?currency=${encodeURIComponent(checkedCurrency)}`,
        { cache: "no-store" },
      );
      setStatus(readiness);
      if (active) {
        setCompany({
          code: active.jurisdiction_code ?? "", legalName: active.legal_name ?? "",
          registrationNo: active.tax_registration_no ?? "", countryCode: active.country_code ?? "",
          address: active.address ?? "", invoicePrefix: active.invoice_prefix ?? "AAL-INV",
          currency: active.currency ?? "USD",
        });
        const rules = await apiFetch<TaxRule[]>(
          `${base}/tax-rules?jurisdictionCode=${encodeURIComponent(active.jurisdiction_code ?? "")}`,
          { cache: "no-store" },
        );
        const selected = rules.find(r => r.code === readiness.taxCode)
          ?? rules.find(r => r.tax_type === "VAT" && Number(r.rate) === 18 && !r.currency)
          ?? null;
        setRule(selected);
        setInclusive(Boolean(selected?.inclusive));
      } else {
        setRule(null);
      }
    } catch (e) {
      setError(message(e));
      setStatus(null);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void refresh(); }, []);

  async function saveJurisdiction() {
    if (!allowed) return;
    if (!confirmed) {
      setError("Confirm the company's legal details and authority to charge 18% VAT before enabling invoicing.");
      return;
    }
    if (!company.code.trim() || !company.legalName.trim() || !company.countryCode.trim()) {
      setError("Enter the legal company name, jurisdiction code and country.");
      return;
    }
    setSaving(true);
    setError(""); setSuccess("");
    try {
      await apiFetch(`${base}/tax-jurisdictions`, {
        method: "POST",
        body: JSON.stringify({ ...company,
          code: company.code.trim().toUpperCase(), countryCode: company.countryCode.trim().toUpperCase(),
          currency: company.currency.trim().toUpperCase(),
          active: true, defaultForInvoicing: true,
        }),
      });
      setSuccess("Company invoice setup saved. Verify the effective VAT rule and review the invoice preview before billing.");
      await refresh();
    } catch (e) { setError(message(e)); }
    finally { setSaving(false); }
  }

  async function saveInclusive() {
    if (!allowed || !rule || !status?.ready || !confirmed) {
      setError("Confirm the company's applicable VAT treatment before changing its tax rule.");
      return;
    }
    setSaving(true); setError(""); setSuccess("");
    try {
      await apiFetch(`${base}/tax-rules`, {
        method: "POST",
        body: JSON.stringify({
          jurisdictionCode: status.jurisdictionCode,
          code: rule.code, taxName: rule.tax_name, rate: Number(rule.rate),
          withholdingRate: Number(rule.withholding_rate ?? 0),
          currency: rule.currency ?? null,
          validFrom: rule.valid_from, validUntil: rule.valid_until ?? null,
          taxType: rule.tax_type, inclusive,
          appliesTo: "INVOICE", active: true,
        }),
      });
      setSuccess("Tax-inclusive/exclusive treatment saved. Refresh the invoice preview before creating an invoice.");
      await refresh();
    } catch (e) { setError(message(e)); }
    finally { setSaving(false); }
  }

  const field = (label: string, name: keyof typeof company, placeholder?: string) => (
    <label className="field" key={name}>
      <span>{label}</span>
      <input
        value={company[name]}
        placeholder={placeholder ?? ""}
        onChange={e => { setConfirmed(false); setCompany(old => ({...old, [name]: e.target.value})); }}
        disabled={!allowed || saving}
      />
    </label>
  );

  return (
    <main className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow">AAL / FINANCE / ONE-TIME SETUP</div>
          <h1 className="page-title">Invoice Tax Setup</h1>
          <p className="page-subtitle">
            Configure the legal invoice issuer and applicable VAT treatment <strong>before</strong> creating customer invoices.
          </p>
        </div>
        <div className="actions">
          <Link className="btn" href="/billing">Billing &amp; Receivables</Link>
          <button className="btn" disabled={loading || saving} onClick={() => void refresh()}>Refresh status</button>
        </div>
      </div>

      {error && <div className="alert alert-error aal-alert" role="alert">{error}</div>}
      {success && <div className="alert alert-success aal-alert" role="status">{success}</div>}

      <section className="form-panel" style={{marginBottom: 18}} aria-label="Invoice tax readiness">
        <div className="section-heading">
          <div className="section-number">01</div>
          <div><h2>Check billing readiness</h2><p>Read-only check from the actual backend. It does not create invoices or tax rules.</p></div>
        </div>
        {loading ? <p>Checking invoice configuration…</p>
          : status?.ready ? <div className="alert alert-success" role="status">
            Invoice tax setup is active: {status.jurisdictionCode} · {status.taxCode} · {status.taxRate}%.
            Verify the treatment for each service before billing.
          </div>
          : <div className="alert alert-error" role="alert">
            {status?.message ?? "Invoice setup could not be verified. Do not issue invoices until finance has confirmed it."}
          </div>}
        {!allowed && <p className="form-hint">Ask an administrator or authorized finance user to complete this setup. Your current role cannot update tax configuration.</p>}
      </section>

      <section className="form-panel" style={{marginBottom: 18}}>
        <div className="section-heading">
          <div className="section-number">02</div>
          <div><h2>Company invoicing identity</h2><p>Use the company's verified legal and tax information. Never enter placeholder registration details.</p></div>
        </div>
        <div className="form-grid">
          {field("Jurisdiction code (e.g. RW)", "code")}
          {field("Registered legal company name", "legalName")}
          {field("Tax registration number (if applicable)", "registrationNo")}
          {field("Country code (ISO)", "countryCode")}
          {field("Invoice prefix", "invoicePrefix")}
          {field("Default currency", "currency")}
          {field("Registered company address", "address")}
        </div>
        <p className="form-hint">
          The current backend uses a standard 18% VAT invoice rule when a default jurisdiction is activated.
          Only activate this setup if the company is authorized to charge that tax on these invoices.
          Exempt, zero-rated or non-VAT-registered cases require appropriate tax configuration and review first.
        </p>
        <label className="field" style={{marginTop: 12}}>
          <span>
            <input type="checkbox" checked={confirmed} disabled={!allowed || saving}
              onChange={e => setConfirmed(e.target.checked)} style={{marginRight: 8}} />
            I have verified the legal entity details and confirmed that the 18% VAT rule is applicable.
          </span>
        </label>
        <div className="actions" style={{marginTop: 12}}>
          <button className="btn btn-primary" type="button"
            disabled={!allowed || saving || !confirmed || !company.legalName.trim() || !company.code.trim()}
            onClick={() => void saveJurisdiction()}>
            {saving ? "Saving…" : "Save company and activate invoice setup"}
          </button>
        </div>
      </section>

      {status?.ready && rule && (
        <section className="form-panel" style={{marginBottom: 18}}>
          <div className="section-heading">
            <div className="section-number">03</div>
            <div><h2>Review tax treatment</h2><p>Review the active rule before issuing invoices. Changes must be approved by finance.</p></div>
          </div>
          <p>Selected rule: <strong>{rule.code}</strong>, {rule.rate}% · effective from {rule.valid_from}</p>
          <label className="field">
            <span>Are shipment charges entered inclusive or exclusive of VAT?</span>
            <select value={inclusive ? "true" : "false"} disabled={!allowed || saving}
              onChange={e => {setConfirmed(false); setInclusive(e.target.value === "true");}}>
              <option value="false">Exclusive — VAT added to customer amount</option>
              <option value="true">Inclusive — VAT extracted from customer amount</option>
            </select>
          </label>
          <button className="btn btn-primary" style={{marginTop: 12}} disabled={!allowed || saving || !confirmed}
            onClick={() => void saveInclusive()}>Save verified tax treatment</button>
        </section>
      )}
      {!!jurisdictions.length && <p className="form-hint">Configured jurisdictions: {jurisdictions.length}. Only one may be active and default for invoice numbering.</p>}
      <div className="actions" style={{marginTop: 18}}>
        <button type="button" className="btn btn-primary"
          disabled={!status?.ready || loading || saving}
          onClick={() => router.push("/billing")}>Continue to customer invoicing →</button>
        <Link className="btn" href="/billing/hardening">Advanced finance controls</Link>
      </div>
    </main>
  );
}
