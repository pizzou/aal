"use client";

import Link from "next/link";
import { useEffect, useMemo, useState } from "react";
import { apiFetch, commercialApi, downloadApiFile, shipmentsApi } from "@/lib/api-client";

type Invoice = {
  id: string;
  invoiceNo?: string;
  invoice_no?: string;
  client?: string | null;
  currency?: string;
  invoiceAmount?: number;
  invoice_amount?: number;
  lifecycleStatus?: string;
  lifecycle_status?: string;
  status?: string;
  amountPaid?: number;
  amount_paid?: number;
  dueDate?: string | null;
  due_date?: string | null;
};
type Shipment = { id: string; referenceCode?: string; clientName?: string | null; currency?: string; amountBilledToClient?: number | null };
type FinanceNote = { id: string; note_no?: string; noteNo?: string; note_type?: string; noteType?: string; invoice_no?: string; invoiceNo?: string; client?: string; amount?: number; currency?: string; status?: string; reason?: string; created_at?: string; createdAt?: string };
type Jurisdiction = { jurisdiction_code?: string; jurisdictionCode?: string; legal_name?: string; legalName?: string; tax_registration_no?: string | null; taxRegistrationNo?: string | null; country_code?: string; countryCode?: string; invoice_prefix?: string | null; invoicePrefix?: string | null; currency?: string; active?: boolean; default_for_invoicing?: boolean; defaultForInvoicing?: boolean };
type TaxRule = { id: string; code: string; tax_name?: string; taxName?: string; rate: number; withholding_rate?: number; withholdingRate?: number; currency?: string | null; valid_from?: string; inclusive?: boolean; tax_type?: string; jurisdiction_code?: string };
type Statement = { client: string; from: string; to: string; invoices: Record<string, unknown>[]; payments: Record<string, unknown>[]; notes: Record<string, unknown>[]; transactionHistory: Record<string, unknown>[]; openingBalanceByCurrency: Record<string, number>; outstandingByCurrency: Record<string, number> };

const base = "/api/finance/hardening";
const today = () => new Date().toISOString().slice(0, 10);
const monthStart = () => `${today().slice(0, 7)}-01`;
function asArray<T>(v: unknown): T[] { return Array.isArray(v) ? v as T[] : []; }
function invoiceIdLabel(i: Invoice) { return `${i.invoiceNo ?? i.invoice_no ?? "Invoice"} — ${i.client ?? "Customer"}`; }
function life(i: Invoice) { return String(i.lifecycleStatus ?? i.lifecycle_status ?? i.status ?? "ISSUED").toUpperCase().replaceAll(" ", "_"); }
function invoiceNo(i: Invoice) { return i.invoiceNo ?? i.invoice_no ?? i.id; }
function customerNames(invoices: Invoice[]) { return [...new Set(invoices.map(i => i.client?.trim()).filter((x): x is string => Boolean(x)))].sort(); }
function money(value: unknown, currency = "") { const n = Number(value ?? 0); return `${currency ? `${currency} ` : ""}${Number.isFinite(n) ? n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }) : "—"}`; }
function dateQuery(client: string, from: string, to: string) { const q = new URLSearchParams({ client }); if (from) q.set("from", from); if (to) q.set("to", to); return q.toString(); }

export default function FinancialHardeningPage() {
  const [invoices, setInvoices] = useState<Invoice[]>([]);
  const [shipments, setShipments] = useState<Shipment[]>([]);
  const [notes, setNotes] = useState<FinanceNote[]>([]);
  const [jurisdictions, setJurisdictions] = useState<Jurisdiction[]>([]);
  const [rules, setRules] = useState<TaxRule[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");

  const [statementClient, setStatementClient] = useState("");
  const [statementFrom, setStatementFrom] = useState(monthStart());
  const [statementTo, setStatementTo] = useState(today());
  const [statementEmail, setStatementEmail] = useState("");
  const [statement, setStatement] = useState<Statement | null>(null);

  const [draftShipment, setDraftShipment] = useState("");
  const [draftDue, setDraftDue] = useState("");
  const [draftOwner, setDraftOwner] = useState("");
  const [selectedInvoiceId, setSelectedInvoiceId] = useState("");
  const [nextStatus, setNextStatus] = useState("SENT");
  const [lifecycleReason, setLifecycleReason] = useState("");
  const [lifecycleHistory, setLifecycleHistory] = useState<Record<string, unknown>[]>([]);
  const [taxJurisdictionCode, setTaxJurisdictionCode] = useState("");
  const [taxCode, setTaxCode] = useState("");

  const [noteType, setNoteType] = useState("CREDIT");
  const [noteInvoiceId, setNoteInvoiceId] = useState("");
  const [noteAmount, setNoteAmount] = useState("");
  const [noteCurrency, setNoteCurrency] = useState("USD");
  const [noteReason, setNoteReason] = useState("");
  const [voidReason, setVoidReason] = useState("");

  const [jurCode, setJurCode] = useState("RW");
  const [jurLegalName, setJurLegalName] = useState("");
  const [jurRegistration, setJurRegistration] = useState("");
  const [jurCountry, setJurCountry] = useState("RW");
  const [jurAddress, setJurAddress] = useState("");
  const [jurPrefix, setJurPrefix] = useState("AAL-INV");
  const [jurCurrency, setJurCurrency] = useState("RWF");
  const [jurActive, setJurActive] = useState(true);
  const [jurDefault, setJurDefault] = useState(true);

  const [ruleCode, setRuleCode] = useState("VAT");
  const [ruleName, setRuleName] = useState("Value Added Tax");
  const [ruleRate, setRuleRate] = useState("18");
  const [ruleWithholding, setRuleWithholding] = useState("0");
  const [ruleCurrency, setRuleCurrency] = useState("RWF");
  const [ruleValidFrom, setRuleValidFrom] = useState(today());
  const [ruleValidUntil, setRuleValidUntil] = useState("");
  const [ruleType, setRuleType] = useState("VAT");
  const [ruleInclusive, setRuleInclusive] = useState(false);
  const [ruleActive, setRuleActive] = useState(true);

  const selectedInvoice = invoices.find(i => i.id === selectedInvoiceId);
  const draftInvoices = invoices.filter(i => life(i) === "DRAFT");
  const customers = useMemo(() => customerNames(invoices), [invoices]);
  const activeJurisdictions = jurisdictions.filter(j => j.active);
  const selectedJurisdiction = taxJurisdictionCode || activeJurisdictions[0]?.jurisdiction_code || activeJurisdictions[0]?.jurisdictionCode || "";

  async function load() {
    setLoading(true); setError("");
    const results = await Promise.allSettled([
      commercialApi.invoices(), shipmentsApi.list(),
      apiFetch<FinanceNote[]>(`${base}/finance-notes`, { cache: "no-store" }),
      apiFetch<Jurisdiction[]>(`${base}/tax-jurisdictions`, { cache: "no-store" }),
    ]);
    const fails: string[] = [];
    if (results[0].status === "fulfilled") setInvoices(asArray<Invoice>(results[0].value));
    else fails.push("Invoices could not be loaded");
    if (results[1].status === "fulfilled") {
      const value = results[1].value as unknown as { content?: Shipment[] } | Shipment[];
      setShipments(asArray<Shipment>(Array.isArray(value) ? value : value?.content));
    } else fails.push("Shipments could not be loaded");
    if (results[2].status === "fulfilled") setNotes(asArray<FinanceNote>(results[2].value));
    else fails.push("Finance notes are unavailable");
    if (results[3].status === "fulfilled") {
      const rows = asArray<Jurisdiction>(results[3].value); setJurisdictions(rows);
      const active = rows.find(j => j.active);
      if (active) {
        const code = active.jurisdiction_code ?? active.jurisdictionCode ?? "";
        setTaxJurisdictionCode(prev => prev || code);
        setJurCode(prev => prev === "RW" && code ? code : prev);
        setJurLegalName(prev => prev || active.legal_name || active.legalName || "");
        setJurRegistration(prev => prev || active.tax_registration_no || active.taxRegistrationNo || "");
        setJurCountry(prev => prev === "RW" && (active.country_code || active.countryCode) ? (active.country_code || active.countryCode)! : prev);
        setJurCurrency(prev => prev === "RWF" && active.currency ? active.currency : prev);
      }
    } else fails.push("Tax jurisdictions are unavailable");
    if (fails.length) setError(fails.join(". "));
    setLoading(false);
  }

  useEffect(() => { void load(); }, []);
  useEffect(() => { if (selectedInvoice?.currency) setNoteCurrency(selectedInvoice.currency); }, [selectedInvoice?.id, selectedInvoice?.currency]);
  useEffect(() => {
    if (!selectedJurisdiction) { setRules([]); return; }
    apiFetch<TaxRule[]>(`${base}/tax-rules?jurisdictionCode=${encodeURIComponent(selectedJurisdiction)}&onDate=${today()}`, { cache: "no-store" })
      .then(rows => setRules(asArray<TaxRule>(rows))).catch(() => setRules([]));
  }, [selectedJurisdiction]);

  async function runAction(action: () => Promise<unknown>, success: string, reload = true) {
    setBusy(true); setError(""); setMessage("");
    try { await action(); setMessage(success); if (reload) await load(); }
    catch (e) { setError(e instanceof Error ? e.message : "The request failed"); }
    finally { setBusy(false); }
  }

  async function createDraft() {
    if (!draftShipment || !draftDue) { setError("Select a shipment and due date for the draft invoice."); return; }
    await runAction(async () => {
      const created = await apiFetch<Invoice>(`/api/billing/shipments/${draftShipment}/invoice/draft`, { method: "POST", body: JSON.stringify({ dueDate: draftDue, owner: draftOwner || undefined }) });
      setSelectedInvoiceId(created.id); setDraftShipment(""); setDraftDue(""); setDraftOwner("");
    }, "Draft invoice created. Apply a tax rule if required, then issue it.");
  }

  async function transition() {
    if (!selectedInvoiceId) { setError("Select an invoice first."); return; }
    await runAction(async () => { await apiFetch(`${base}/invoices/${selectedInvoiceId}/lifecycle`, { method: "POST", body: JSON.stringify({ status: nextStatus, reason: lifecycleReason }) }); }, `Invoice lifecycle changed to ${nextStatus}.`);
    setLifecycleReason("");
  }

  async function showHistory() {
    if (!selectedInvoiceId) { setError("Select an invoice first."); return; }
    await runAction(async () => { const rows = await apiFetch<Record<string, unknown>[]>(`${base}/invoices/${selectedInvoiceId}/lifecycle-history`, { cache: "no-store" }); setLifecycleHistory(asArray(rows)); }, "Lifecycle history loaded.", false);
  }

  async function applyTax() {
    if (!selectedInvoiceId || !selectedJurisdiction || !taxCode.trim()) { setError("Select a draft invoice, jurisdiction and tax code."); return; }
    await runAction(async () => { await apiFetch(`${base}/invoices/${selectedInvoiceId}/tax`, { method: "POST", body: JSON.stringify({ jurisdictionCode: selectedJurisdiction, taxCode: taxCode.trim(), onDate: today() }) }); }, "Tax calculated and locked on the draft invoice.");
  }

  async function createNote() {
    if (!noteInvoiceId || !noteAmount || Number(noteAmount) <= 0 || !noteReason.trim()) { setError("Choose an invoice and enter a positive amount and reason."); return; }
    await runAction(async () => { await apiFetch(`${base}/finance-notes`, { method: "POST", body: JSON.stringify({ noteType, invoiceId: noteInvoiceId, amount: Number(noteAmount), currency: noteCurrency, reason: noteReason.trim() }) }); }, `${noteType} note created as a draft. A different authorized user must approve it.`);
    setNoteAmount(""); setNoteReason("");
  }

  async function createJurisdiction() {
    await runAction(async () => {
      const item = await apiFetch<Jurisdiction>(`${base}/tax-jurisdictions`, { method: "POST", body: JSON.stringify({ code: jurCode.trim(), legalName: jurLegalName.trim(), registrationNo: jurRegistration.trim() || null, countryCode: jurCountry.trim(), address: jurAddress.trim() || null, invoicePrefix: jurPrefix.trim() || null, currency: jurCurrency.trim(), active: jurActive, defaultForInvoicing: jurDefault }) });
      if (jurActive) setTaxJurisdictionCode(item.jurisdiction_code ?? item.jurisdictionCode ?? jurCode.toUpperCase());
    }, "Tax jurisdiction saved.");
  }

  async function createRule() {
    if (!selectedJurisdiction) { setError("Save a jurisdiction before creating a rule."); return; }
    await runAction(async () => { await apiFetch(`${base}/tax-rules`, { method: "POST", body: JSON.stringify({ jurisdictionCode: selectedJurisdiction, code: ruleCode.trim(), taxName: ruleName.trim(), rate: Number(ruleRate), withholdingRate: Number(ruleWithholding), currency: ruleCurrency.trim() || null, validFrom: ruleValidFrom, validUntil: ruleValidUntil || null, taxType: ruleType, inclusive: ruleInclusive, appliesTo: "INVOICE", active: ruleActive }) }); }, "Tax rule saved.");
  }

  async function viewStatement() {
    if (!statementClient.trim()) { setError("Enter a customer name for the account statement."); return; }
    await runAction(async () => { setStatement(await apiFetch<Statement>(`${base}/customer-statement?${dateQuery(statementClient.trim(), statementFrom, statementTo)}`, { cache: "no-store" })); }, "Customer statement loaded.", false);
  }
  async function downloadStatement() {
    if (!statementClient.trim()) { setError("Enter a customer name for the account statement."); return; }
    await runAction(async () => { await downloadApiFile(`${base}/customer-statement/pdf?${dateQuery(statementClient.trim(), statementFrom, statementTo)}`, "customer-statement.pdf"); }, "Customer statement PDF downloaded.", false);
  }
  async function emailStatement() {
    if (!statementClient.trim()) { setError("Enter a customer name for the account statement."); return; }
    await runAction(async () => { await apiFetch(`${base}/customer-statement/email`, { method: "POST", body: JSON.stringify({ client: statementClient.trim(), from: statementFrom || null, to: statementTo || null, recipientEmail: statementEmail.trim() || null }) }); }, "Statement email request accepted.", false);
  }

  return (
    <main className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow">AAL / FINANCIAL CONTROLS</div>
          <h1 className="page-title">Financial Hardening</h1>
          <p className="page-subtitle">Invoice lifecycle, customer statements, controlled credit/debit notes and configurable tax rules.</p>
        </div>
        <div className="actions"><Link className="btn" href="/billing">Back to billing</Link><button className="btn" onClick={() => void load()} disabled={loading || busy}>Refresh</button></div>
      </div>
      {error && <div className="alert alert-error aal-alert" role="alert">{error}</div>}
      {message && <div className="alert alert-success aal-alert" role="status">{message}</div>}
      {loading && <div className="card">Loading financial records…</div>}

      <section className="form-panel" style={{ marginBottom: 18 }}>
        <div className="section-heading"><div className="section-number">01</div><div><h2>Customer account statements</h2><p>PDF/email statements include dated invoice, payment and approved-note activity, opening balance and current outstanding balances by currency.</p></div></div>
        <div className="form-grid">
          <label className="field field-span-2"><span>Customer</span><input list="statement-customers" value={statementClient} onChange={e => setStatementClient(e.target.value)} placeholder="Enter customer/company name" /><datalist id="statement-customers">{customers.map(c => <option key={c} value={c} />)}</datalist></label>
          <label className="field"><span>From</span><input type="date" value={statementFrom} onChange={e => setStatementFrom(e.target.value)} /></label>
          <label className="field"><span>To</span><input type="date" value={statementTo} onChange={e => setStatementTo(e.target.value)} /></label>
          <label className="field field-span-2"><span>Email recipient (optional)</span><input type="email" value={statementEmail} onChange={e => setStatementEmail(e.target.value)} placeholder="Leave blank to use the customer contact email" /></label>
        </div>
        <div className="actions" style={{ marginTop: 12 }}><button className="btn btn-primary" disabled={busy} onClick={() => void viewStatement()}>View statement</button><button className="btn" disabled={busy} onClick={() => void downloadStatement()}>Download PDF</button><button className="btn" disabled={busy} onClick={() => void emailStatement()}>Email statement</button></div>
        {statement && <div style={{ marginTop: 18 }}>
          <h3>{statement.client} · {statement.from} — {statement.to}</h3>
          <div className="kpi-grid" style={{ marginTop: 10 }}>
            <div className="card kpi"><div className="kpi-label">Opening balance</div>{Object.entries(statement.openingBalanceByCurrency ?? {}).map(([c, n]) => <div key={c} className="kpi-value" style={{ fontSize: 17 }}>{money(n, c)}</div>)}</div>
            <div className="card kpi"><div className="kpi-label">Current outstanding</div>{Object.entries(statement.outstandingByCurrency ?? {}).map(([c, n]) => <div key={c} className="kpi-value" style={{ fontSize: 17 }}>{money(n, c)}</div>)}</div>
            <div className="card kpi"><div className="kpi-label">Transactions in period</div><div className="kpi-value">{statement.transactionHistory?.length ?? 0}</div><div className="kpi-meta">Invoice, payment and approved-note entries</div></div>
          </div>
          <div className="table-wrap" style={{ marginTop: 12 }}><table><thead><tr><th>Date</th><th>Type</th><th>Reference</th><th>Description</th><th>Currency</th><th>Debit</th><th>Credit</th></tr></thead><tbody>
            {(statement.transactionHistory ?? []).map((r, i) => <tr key={`${String(r.reference ?? i)}-${i}`}><td>{String(r.transaction_date ?? "")}</td><td>{String(r.transaction_type ?? "")}</td><td>{String(r.reference ?? "")}</td><td>{String(r.description ?? "")}</td><td>{String(r.currency ?? "")}</td><td>{money(r.debit_amount)}</td><td>{money(r.credit_amount)}</td></tr>)}
            {!statement.transactionHistory?.length && <tr><td colSpan={7}>No transactions in the selected period.</td></tr>}
          </tbody></table></div>
        </div>}
      </section>

      <section className="form-panel" style={{ marginBottom: 18 }}>
        <div className="section-heading"><div className="section-number">02</div><div><h2>Invoice lifecycle and tax</h2><p>Drafts can be taxed and reviewed before issue. Voiding/cancelling issued invoices requires an available unpaid balance and creates a reversing journal entry.</p></div></div>
        <div className="form-grid">
          <label className="field field-span-2"><span>Create a draft from shipment</span><select value={draftShipment} onChange={e => setDraftShipment(e.target.value)}><option value="">Select shipment…</option>{shipments.map(s => <option key={s.id} value={s.id}>{s.referenceCode ?? s.id} — {s.clientName ?? "Customer"} — {money(s.amountBilledToClient, s.currency ?? "USD")}</option>)}</select></label>
          <label className="field"><span>Draft due date</span><input type="date" value={draftDue} onChange={e => setDraftDue(e.target.value)} /></label>
          <label className="field"><span>Owner</span><input value={draftOwner} onChange={e => setDraftOwner(e.target.value)} placeholder="Responsible person" /></label>
        </div>
        <div className="actions" style={{ marginTop: 10 }}><button className="btn btn-primary" disabled={busy} onClick={() => void createDraft()}>Create draft invoice</button></div>
        <div className="form-grid" style={{ marginTop: 18 }}>
          <label className="field field-span-2"><span>Invoice</span><select value={selectedInvoiceId} onChange={e => { setSelectedInvoiceId(e.target.value); setLifecycleHistory([]); }}><option value="">Select invoice…</option>{invoices.map(i => <option key={i.id} value={i.id}>{invoiceIdLabel(i)} · {life(i)}</option>)}</select></label>
          <label className="field"><span>New lifecycle state</span><select value={nextStatus} onChange={e => setNextStatus(e.target.value)}>{["ISSUED","SENT","PARTIALLY_PAID","PAID","OVERDUE","VOID","CANCELLED"].map(s => <option key={s} value={s}>{s}</option>)}</select></label>
          <label className="field field-span-2"><span>Lifecycle reason (required for void/cancel)</span><input value={lifecycleReason} onChange={e => setLifecycleReason(e.target.value)} placeholder="Document the reason for this transition" /></label>
          <label className="field"><span>Tax jurisdiction</span><select value={selectedJurisdiction} onChange={e => setTaxJurisdictionCode(e.target.value)}><option value="">Select jurisdiction…</option>{jurisdictions.map(j => { const c = j.jurisdiction_code ?? j.jurisdictionCode ?? ""; return <option key={c} value={c}>{c} — {j.legal_name ?? j.legalName ?? ""}{j.active ? " (active)" : " (inactive)"}</option>; })}</select></label>
          <label className="field"><span>Tax code</span><select value={taxCode} onChange={e => setTaxCode(e.target.value)}><option value="">Select tax code…</option>{rules.map(r => <option key={r.id} value={r.code}>{r.code} — {r.tax_name ?? r.taxName ?? r.code} ({r.rate}%)</option>)}</select></label>
        </div>
        {selectedInvoice && <div className="card" style={{ marginTop: 12 }}><strong>{invoiceNo(selectedInvoice)}</strong><span style={{ marginLeft: 12 }}>Status: {life(selectedInvoice)}</span><span style={{ marginLeft: 12 }}>Amount: {money(selectedInvoice.invoiceAmount ?? selectedInvoice.invoice_amount, selectedInvoice.currency ?? "USD")}</span></div>}
        <div className="actions" style={{ marginTop: 12 }}><button className="btn btn-primary" disabled={busy || !selectedInvoiceId} onClick={() => void transition()}>Apply lifecycle transition</button><button className="btn" disabled={busy || !selectedInvoiceId} onClick={() => void showHistory()}>View lifecycle history</button><button className="btn" disabled={busy || !selectedInvoiceId || !selectedInvoice || life(selectedInvoice) !== "DRAFT" || !taxCode || !selectedJurisdiction} onClick={() => void applyTax()}>Apply tax to draft</button></div>
        {lifecycleHistory.length > 0 && <div className="table-wrap" style={{ marginTop: 12 }}><table><thead><tr><th>Changed at</th><th>From</th><th>To</th><th>Reason</th><th>Changed by</th></tr></thead><tbody>{lifecycleHistory.map((r,i)=><tr key={`${String(r.changed_at ?? i)}-${i}`}><td>{String(r.changed_at ?? "")}</td><td>{String(r.from_status ?? "—")}</td><td>{String(r.to_status ?? "")}</td><td>{String(r.reason ?? "")}</td><td>{String(r.changed_by ?? "")}</td></tr>)}</tbody></table></div>}
        <div className="card" style={{ marginTop: 12 }}><strong>Current drafts</strong>{draftInvoices.length === 0 ? <p>No draft invoices are waiting for review.</p> : <p>{draftInvoices.map(i => invoiceIdLabel(i)).join(" · ")}</p>}</div>
      </section>

      <section className="form-panel" style={{ marginBottom: 18 }}>
        <div className="section-heading"><div className="section-number">03</div><div><h2>Credit and debit notes</h2><p>Notes are prepared as drafts, require approval by a different user and post to the ledger only when approved. Approved notes are permanent.</p></div></div>
        <div className="form-grid">
          <label className="field"><span>Note type</span><select value={noteType} onChange={e => setNoteType(e.target.value)}><option value="CREDIT">Credit note</option><option value="DEBIT">Debit note</option></select></label>
          <label className="field field-span-2"><span>Linked invoice</span><select value={noteInvoiceId} onChange={e => setNoteInvoiceId(e.target.value)}><option value="">Select invoice…</option>{invoices.map(i => <option key={i.id} value={i.id}>{invoiceIdLabel(i)} · {life(i)}</option>)}</select></label>
          <label className="field"><span>Gross adjustment amount (includes tax for a taxable invoice)</span><input type="number" min="0.01" step="0.01" value={noteAmount} onChange={e => setNoteAmount(e.target.value)} /></label>
          <label className="field"><span>Currency</span><input maxLength={3} value={noteCurrency} onChange={e => setNoteCurrency(e.target.value.toUpperCase())} /></label>
          <label className="field field-span-2"><span>Reason / supporting explanation</span><input value={noteReason} onChange={e => setNoteReason(e.target.value)} placeholder="Describe why the commercial adjustment is needed" /></label>
        </div>
        <div className="actions" style={{ marginTop: 10 }}><button className="btn btn-primary" disabled={busy} onClick={() => void createNote()}>Create draft note</button><input aria-label="Reason for voiding a draft note" value={voidReason} onChange={e => setVoidReason(e.target.value)} placeholder="Reason to void selected draft" style={{ maxWidth: 300 }} /></div>
        <div className="table-wrap" style={{ marginTop: 12 }}><table><thead><tr><th>Note no.</th><th>Type</th><th>Invoice</th><th>Amount</th><th>Status</th><th>Reason</th><th>Workflow</th></tr></thead><tbody>
          {notes.map(n => { const id = n.id; const status = String(n.status ?? "").toUpperCase(); return <tr key={id}><td>{n.note_no ?? n.noteNo ?? id}</td><td>{n.note_type ?? n.noteType ?? ""}</td><td>{n.invoice_no ?? n.invoiceNo ?? "—"}</td><td>{money(n.amount, n.currency)}</td><td>{status}</td><td>{n.reason ?? ""}</td><td><div className="actions">{status === "DRAFT" && <><button className="btn" disabled={busy} onClick={() => void runAction(async () => { await apiFetch(`${base}/finance-notes/${id}/approve`, { method: "POST" }); }, "Finance note approved and posted.")}>Approve</button><button className="btn" disabled={busy || !voidReason.trim()} onClick={() => void runAction(async () => { await apiFetch(`${base}/finance-notes/${id}/void`, { method: "POST", body: JSON.stringify({ reason: voidReason.trim() }) }); setVoidReason(""); }, "Draft note voided.")}>Void</button></>}</div></td></tr>; })}
          {!notes.length && <tr><td colSpan={7}>No finance notes recorded.</td></tr>}
        </tbody></table></div>
      </section>

      <section className="form-panel" style={{ marginBottom: 18 }}>
        <div className="section-heading"><div className="section-number">04</div><div><h2>Tax jurisdiction configuration</h2><p>Configure the invoicing entity, registration data and numbering prefix. The tax jurisdiction settings require jurisdiction-specific professional review before live invoicing.</p></div></div>
        <div className="form-grid">
          <label className="field"><span>Jurisdiction code</span><input maxLength={80} value={jurCode} onChange={e => setJurCode(e.target.value.toUpperCase())} /></label>
          <label className="field field-span-2"><span>Legal entity name</span><input value={jurLegalName} onChange={e => setJurLegalName(e.target.value)} /></label>
          <label className="field"><span>Tax registration no.</span><input value={jurRegistration} onChange={e => setJurRegistration(e.target.value)} /></label>
          <label className="field"><span>Country code (ISO alpha-2/3)</span><input maxLength={3} value={jurCountry} onChange={e => setJurCountry(e.target.value.toUpperCase())} /></label>
          <label className="field"><span>Invoice number prefix</span><input value={jurPrefix} onChange={e => setJurPrefix(e.target.value.toUpperCase())} /></label>
          <label className="field"><span>Default currency</span><input maxLength={3} value={jurCurrency} onChange={e => setJurCurrency(e.target.value.toUpperCase())} /></label>
          <label className="field field-span-2"><span>Registered address</span><input value={jurAddress} onChange={e => setJurAddress(e.target.value)} /></label>
          <label className="field"><span>Make active</span><select value={jurActive ? "true" : "false"} onChange={e => { const active = e.target.value === "true"; setJurActive(active); if (!active) setJurDefault(false); }}><option value="true">Active</option><option value="false">Inactive</option></select></label>
          <label className="field"><span>Default invoice-numbering jurisdiction</span><select value={jurDefault ? "true" : "false"} disabled={!jurActive} onChange={e => setJurDefault(e.target.value === "true")}><option value="true">Yes — use prefix for new invoices</option><option value="false">No</option></select></label>
        </div>
        <div className="actions" style={{ marginTop: 10 }}><button className="btn btn-primary" disabled={busy || !jurCode.trim() || !jurLegalName.trim()} onClick={() => void createJurisdiction()}>Save jurisdiction</button></div>
        <div className="table-wrap" style={{ marginTop: 12 }}><table><thead><tr><th>Code</th><th>Legal name</th><th>Tax registration</th><th>Country</th><th>Prefix</th><th>Currency</th><th>Status</th><th>Invoice numbering</th></tr></thead><tbody>{jurisdictions.map((j,i)=><tr key={j.jurisdiction_code ?? j.jurisdictionCode ?? i}><td>{j.jurisdiction_code ?? j.jurisdictionCode}</td><td>{j.legal_name ?? j.legalName}</td><td>{j.tax_registration_no ?? j.taxRegistrationNo ?? "—"}</td><td>{j.country_code ?? j.countryCode}</td><td>{j.invoice_prefix ?? j.invoicePrefix ?? "—"}</td><td>{j.currency}</td><td>{j.active ? "ACTIVE" : "INACTIVE"}</td><td>{j.default_for_invoicing || j.defaultForInvoicing ? "DEFAULT PREFIX" : "—"}</td></tr>)}{!jurisdictions.length && <tr><td colSpan={8}>No tax jurisdiction configured.</td></tr>}</tbody></table></div>
      </section>

      <section className="form-panel" style={{ marginBottom: 18 }}>
        <div className="section-heading"><div className="section-number">05</div><div><h2>VAT and tax rules</h2><p>Rules are versioned by effective date and jurisdiction. Tax is posted to a separate tax-payable ledger account; rules with withholding are blocked from invoice application until withholding-certificate settlement is implemented.</p></div></div>
        <div className="form-grid">
          <label className="field"><span>Jurisdiction</span><select value={selectedJurisdiction} onChange={e => setTaxJurisdictionCode(e.target.value)}><option value="">Select…</option>{jurisdictions.map(j => { const c = j.jurisdiction_code ?? j.jurisdictionCode ?? ""; return <option key={c} value={c}>{c} — {j.legal_name ?? j.legalName}</option>; })}</select></label>
          <label className="field"><span>Tax code</span><input value={ruleCode} onChange={e => setRuleCode(e.target.value.toUpperCase())} /></label>
          <label className="field field-span-2"><span>Tax name</span><input value={ruleName} onChange={e => setRuleName(e.target.value)} /></label>
          <label className="field"><span>Tax rate (%)</span><input type="number" min="0" max="100" step="0.0001" value={ruleRate} onChange={e => setRuleRate(e.target.value)} /></label>
          <label className="field"><span>Withholding rate (%)</span><input type="number" min="0" max="100" step="0.0001" value={ruleWithholding} onChange={e => setRuleWithholding(e.target.value)} /></label>
          <label className="field"><span>Rule currency (optional)</span><input maxLength={3} value={ruleCurrency} onChange={e => setRuleCurrency(e.target.value.toUpperCase())} /></label>
          <label className="field"><span>Tax type</span><select value={ruleType} onChange={e => setRuleType(e.target.value)}>{["VAT","GST","SALES_TAX","WITHHOLDING","OTHER"].map(t=><option key={t}>{t}</option>)}</select></label>
          <label className="field"><span>Effective from</span><input type="date" value={ruleValidFrom} onChange={e => setRuleValidFrom(e.target.value)} /></label>
          <label className="field"><span>Effective until (optional)</span><input type="date" value={ruleValidUntil} onChange={e => setRuleValidUntil(e.target.value)} /></label>
          <label className="field"><span>Tax is inclusive?</span><select value={ruleInclusive ? "true" : "false"} onChange={e => setRuleInclusive(e.target.value === "true")}><option value="false">Exclusive (add tax)</option><option value="true">Inclusive (extract tax)</option></select></label>
          <label className="field"><span>Rule status</span><select value={ruleActive ? "true" : "false"} onChange={e => setRuleActive(e.target.value === "true")}><option value="true">Active</option><option value="false">Inactive</option></select></label>
        </div>
        <div className="actions" style={{ marginTop: 10 }}><button className="btn btn-primary" disabled={busy || !selectedJurisdiction || !ruleCode.trim() || !ruleName.trim()} onClick={() => void createRule()}>Save tax rule</button><button className="btn" disabled={busy || !selectedJurisdiction} onClick={() => void apiFetch<TaxRule[]>(`${base}/tax-rules?jurisdictionCode=${encodeURIComponent(selectedJurisdiction)}&onDate=${today()}`, { cache: "no-store" }).then(rows => setRules(asArray<TaxRule>(rows))).catch(e => setError(e instanceof Error ? e.message : "Unable to load tax rules"))}>Refresh rules</button></div>
        <div className="table-wrap" style={{ marginTop: 12 }}><table><thead><tr><th>Code</th><th>Name</th><th>Type</th><th>Rate</th><th>Withholding</th><th>Valid from</th><th>Inclusive</th><th>Status</th></tr></thead><tbody>{rules.map(r=><tr key={r.id}><td>{r.code}</td><td>{r.tax_name ?? r.taxName}</td><td>{r.tax_type ?? "VAT"}</td><td>{r.rate}%</td><td>{r.withholding_rate ?? r.withholdingRate ?? 0}%</td><td>{r.valid_from ?? ""}</td><td>{r.inclusive ? "Yes" : "No"}</td><td>ACTIVE</td></tr>)}{!rules.length && <tr><td colSpan={8}>No active rules were returned for this jurisdiction and date.</td></tr>}</tbody></table></div>
      </section>
      <p className="card-muted">This screen manages application-level financial controls. Production use still requires a verified database migration, jurisdiction-specific tax review, real email delivery checks and reconciliation in the target environment.</p>
    </main>
  );
}
