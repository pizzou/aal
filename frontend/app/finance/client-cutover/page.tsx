"use client";
import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { apiFetch } from "@/lib/api-client";
import { useAuth } from "@/lib/auth-context";

type CurrencyCheck = {
  currency: string;
  issuedInvoiceOutstanding: number;
  receivableInLedger: number;
  receivableDifference: number;
  supplierBillsOutstanding: number;
  payableInLedger: number;
  payableDifference: number;
  debitTotal: number;
  creditTotal: number;
  journalDifference: number;
  customerCashReceipts: number;
  supplierBillPayments: number;
  legacySupplierCashExpenses: number;
  status: string;
};
type Snapshot = { asOf: string; status: string; byCurrency: CurrencyCheck[]; caveat: string };
type CashMovement = {source: "CUSTOMER_RECEIPT" | "SUPPLIER_SETTLEMENT"; recordedAt: string;
  party: string; documentReference: string; shipmentReference: string; amount: number;
  currency: string; paymentMethod: string; paymentReference: string};
const money = (n: number) => Number(n).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 4 });

export default function ClientCutoverPage() {
  const { accessToken, role, isLoading } = useAuth();
  const allowed = role === "ADMIN" || role === "FINANCE";
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState("");
  const [transactions, setTransactions] = useState<CashMovement[]>([]);
  const [registerError, setRegisterError] = useState("");
  const [from, setFrom] = useState(new Date().toISOString().slice(0,7) + "-01");
  const [to, setTo] = useState(new Date().toISOString().slice(0,10));
  const refresh = useCallback(async () => {
    setBusy(true); setError("");
    try { setSnapshot(await apiFetch<Snapshot>("/api/finance/client-cutover/reconciliation", { cache: "no-store" })); }
    catch (e: unknown) { setError(e instanceof Error ? e.message : "Reconciliation unavailable"); }
    finally { setBusy(false); }
  }, []);
  useEffect(() => { if (allowed && accessToken && !isLoading) void refresh(); }, [allowed, accessToken, isLoading, refresh]);
  const loadRegister = useCallback(async () => {
    setRegisterError("");
    try { setTransactions(await apiFetch<CashMovement[]>(
      `/api/finance/client-cutover/payments?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
      {cache: "no-store"})); }
    catch (e: unknown) { setRegisterError(e instanceof Error ? e.message : "Cannot load payment register"); }
  }, [from, to]);
  useEffect(() => { if (allowed && accessToken && !isLoading) void loadRegister(); }, [allowed, accessToken, isLoading, loadRegister]);
  if (isLoading) return <main className="page-container">Loading...</main>;
  if (!allowed || !accessToken) return <main className="page-container"><h1>Finance access required</h1></main>;
  return <main className="page-container">
    <header className="page-header"><div>
      <h1 className="page-title">Excel cutover reconciliation</h1>
      <p className="page-subtitle">Current signed-off-ready checks: receivables, payables and ledger balance, separately by currency.</p>
    </div><Link className="btn" href="/finance/supplier-bills">Supplier payables</Link></header>
    {error && <div className="alert alert-error" role="alert">{error}</div>}
    <section className="card" style={{ padding: 20 }}>
      <div style={{ display: "flex", justifyContent: "space-between", gap: 12, flexWrap: "wrap" }}>
        <div><h2 className="card-title">Financial control results</h2>
          <p><strong>Status:</strong> {snapshot?.status || "No results"} {snapshot?.asOf && `• ${snapshot.asOf}`}</p></div>
        <button className="btn btn-primary" disabled={busy} onClick={() => void refresh()}>{busy ? "Checking..." : "Run current checks"}</button>
      </div>
      <p><strong>Important:</strong> A balance match is not evidence that legacy receipts, supplier payments or the bank balance were imported correctly.</p>
      <div style={{ overflowX: "auto" }}><table className="table"><thead><tr>
        <th>Currency</th><th>Issued customer receivable</th><th>AR ledger</th><th>AR difference</th>
        <th>Supplier payable</th><th>AP ledger</th><th>AP difference</th><th>GL difference</th><th>Client collections</th>
        <th>Supplier settlements</th><th>Legacy supplier cash-as-cost</th><th>Check</th>
      </tr></thead><tbody>{snapshot?.byCurrency.map(c => <tr key={c.currency}>
        <td><strong>{c.currency}</strong></td><td>{money(c.issuedInvoiceOutstanding)}</td><td>{money(c.receivableInLedger)}</td>
        <td>{money(c.receivableDifference)}</td><td>{money(c.supplierBillsOutstanding)}</td>
        <td>{money(c.payableInLedger)}</td><td>{money(c.payableDifference)}</td>
        <td>{money(c.journalDifference)}</td><td>{money(c.customerCashReceipts)}</td>
        <td>{money(c.supplierBillPayments)}</td><td>{money(c.legacySupplierCashExpenses)}</td>
        <td>{c.status}</td>
      </tr>)}{!snapshot?.byCurrency.length && <tr><td colSpan={12}>No accounting transactions found.</td></tr>}</tbody></table></div>
      <p>{snapshot?.caveat}</p>
    </section>
    <section className="card" style={{ padding: 20, marginTop: 20 }}>
      <h2 className="card-title">Dated customer receipts &amp; supplier settlements</h2>
      <p>Only individually recorded transactions appear here. Legacy totals entered in Excel are not presented as invented receipts or bank transactions.</p>
      <div style={{ display: "flex", gap: 12, flexWrap: "wrap", alignItems: "end", marginBottom: 12 }}>
        <label>From <input className="input" type="date" value={from} onChange={e => setFrom(e.target.value)} /></label>
        <label>To <input className="input" type="date" value={to} onChange={e => setTo(e.target.value)} /></label>
        <button className="btn" type="button" disabled={!from || !to || from > to} onClick={() => void loadRegister()}>Refresh register</button>
      </div>
      {registerError && <div role="alert" className="alert alert-error">{registerError}</div>}
      <div style={{ overflowX: "auto" }}><table className="table"><thead><tr>
        <th>Recorded</th><th>Flow</th><th>Customer / supplier</th><th>Document</th>
        <th>Shipment</th><th>Amount</th><th>Method</th><th>Evidence reference</th>
      </tr></thead><tbody>{transactions.map((p,i) => <tr key={`${p.source}-${i}-${p.recordedAt}`}>
        <td>{new Date(p.recordedAt).toLocaleString()}</td><td>{p.source}</td><td>{p.party}</td>
        <td>{p.documentReference}</td><td>{p.shipmentReference}</td>
        <td>{p.currency} {money(p.amount)}</td><td>{p.paymentMethod}</td><td>{p.paymentReference || "Not supplied (legacy)"}</td>
      </tr>)}{transactions.length===0 && <tr><td colSpan={8}>No individually recorded payments found for the selected dates.</td></tr>}</tbody></table></div>
    </section>
    <section className="card" style={{ padding: 20, marginTop: 20 }}>
      <h2 className="card-title">Still required before switching off Excel</h2>
      <ol><li>Resolve and sign the August MOTHERSHIP versus August Finance discrepancies.</li>
        <li>Link each outstanding customer balance to a real issued invoice or approved opening balance.</li>
        <li>Verify each supplier cost and settlement against the vendor invoice and bank evidence.</li>
        <li>Complete staging integration tests, role testing, signed client acceptance and backup restore drill.</li>
      </ol>
    </section>
  </main>;
}
