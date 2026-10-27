"use client";

import Link from "next/link";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { apiFetch, shipmentsApi, type Shipment } from "@/lib/api-client";
import { useAuth } from "@/lib/auth-context";

type SupplierBill = {
  id: string;
  shipmentId: string;
  supplierName: string;
  vendorInvoiceReference: string;
  amount: number;
  amountPaid: number;
  balance: number;
  currency: string;
  billDate: string;
  dueDate: string | null;
  description: string | null;
  status: "DRAFT" | "APPROVED";
  createdBy: string;
  approvedBy: string | null;
};
type SupplierPayment = {
  id: string;
  billId: string;
  amount: number;
  currency: string;
  paymentMethod: string;
  paymentReference: string;
  paidAt: string;
};
const n = (amount: number, currency: string) =>
  `${currency} ${Number(amount).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 4 })}`;
const api = "/api/finance/supplier-bills";

export default function SupplierBillsPage() {
  const { accessToken, role, isLoading } = useAuth();
  const allowed = role === "ADMIN" || role === "FINANCE";
  const [bills, setBills] = useState<SupplierBill[]>([]);
  const [shipments, setShipments] = useState<Shipment[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [shipmentId, setShipmentId] = useState("");
  const [supplierName, setSupplierName] = useState("");
  const [reference, setReference] = useState("");
  const [amount, setAmount] = useState("");
  const [currency, setCurrency] = useState("USD");
  const [billDate, setBillDate] = useState(new Date().toISOString().slice(0, 10));
  const [dueDate, setDueDate] = useState("");
  const [description, setDescription] = useState("");
  const [selectedBill, setSelectedBill] = useState<SupplierBill | null>(null);
  const [paidAmount, setPaidAmount] = useState("");
  const [paymentMethod, setPaymentMethod] = useState("BANK_TRANSFER");
  const [paymentReference, setPaymentReference] = useState("");
  const [paymentHistory, setPaymentHistory] = useState<SupplierPayment[]>([]);
  const pendingSettlementKey = useRef<string | null>(null);
  useEffect(() => { pendingSettlementKey.current = null; }, [selectedBill?.id]);

  const refresh = useCallback(async () => {
    const data = await apiFetch<SupplierBill[]>(api, { cache: "no-store" });
    setBills(data);
    setSelectedBill(current => current ? data.find(b => b.id === current.id) || null : null);
  }, []);

  useEffect(() => {
    if (isLoading || !allowed || !accessToken) return;
    void Promise.allSettled([refresh(), shipmentsApi.list({ size: 100 })]).then(results => {
      if (results[1].status === "fulfilled") setShipments(results[1].value.content);
      if (results[0].status === "rejected") setError("Unable to load supplier payable records.");
      setLoading(false);
    });
  }, [isLoading, allowed, accessToken, refresh]);

  async function perform(fn: () => Promise<void>) {
    setBusy(true);
    setError("");
    setMessage("");
    try { await fn(); await refresh(); }
    catch (err: unknown) { setError(err instanceof Error ? err.message : "Operation failed"); }
    finally { setBusy(false); }
  }

  const totals = useMemo(() => {
    const map = new Map<string, {billed: number; paid: number; owed: number}>();
    for (const bill of bills.filter(b => b.status === "APPROVED")) {
      const item = map.get(bill.currency) || { billed: 0, paid: 0, owed: 0 };
      item.billed += Number(bill.amount);
      item.paid += Number(bill.amountPaid);
      item.owed += Number(bill.balance);
      map.set(bill.currency, item);
    }
    return [...map.entries()];
  }, [bills]);

  if (isLoading) return <main className="page-container"><p>Loading finance...</p></main>;
  if (!allowed || !accessToken) return <main className="page-container"><h1>Finance access required</h1><p>Supplier invoices and settlements require finance or administrator authority.</p></main>;

  return <main className="page-container">
    <header className="page-header">
      <div><h1 className="page-title">Supplier invoices &amp; payments</h1>
        <p className="page-subtitle">Separate supplier costs (payables) from bank/cash settlement. Each invoice needs independent approval.</p></div>
      <div style={{ display: "flex", gap: 8 }}>
        <Link className="btn" href="/billing">Customer billing</Link>
        <Link className="btn" href="/finance/client-cutover">Reconciliation</Link>
      </div>
    </header>
    {error && <div role="alert" className="alert alert-error">{error}</div>}
    {message && <div role="status" className="alert alert-success">{message}</div>}
    <section className="card" style={{ padding: 20, marginBottom: 20 }}>
      <h2 className="card-title">New supplier invoice</h2>
      <form onSubmit={e => { e.preventDefault(); void perform(async () => {
        await apiFetch(api, { method: "POST", body: JSON.stringify({
          shipmentId, supplierName: supplierName.trim(), vendorInvoiceReference: reference.trim(),
          amount: Number(amount), currency, billDate, dueDate: dueDate || null, description,
        }) });
        setReference(""); setAmount(""); setDescription("");
        setMessage("Supplier bill saved as DRAFT. A second authorized staff member must approve before settlement.");
      }); }}>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit,minmax(210px,1fr))", gap: 12 }}>
          <label>Shipment<br/><select className="input" required value={shipmentId} onChange={e => setShipmentId(e.target.value)}>
            <option value="">Choose shipment</option>
            {shipments.map(s => <option key={s.id} value={s.id}>{s.referenceCode || s.id} — {s.clientName || "Customer"}</option>)}
          </select></label>
          <label>Supplier legal name<br/><input className="input" required maxLength={200} value={supplierName} onChange={e => setSupplierName(e.target.value)}/></label>
          <label>Supplier invoice number<br/><input className="input" required maxLength={120} value={reference} onChange={e => setReference(e.target.value)}/></label>
          <label>Amount<br/><input className="input" required type="number" min="0.0001" step="0.0001" value={amount} onChange={e => setAmount(e.target.value)}/></label>
          <label>Currency<br/><input className="input" required maxLength={3} value={currency} onChange={e => setCurrency(e.target.value.toUpperCase())}/></label>
          <label>Bill date<br/><input className="input" required type="date" value={billDate} onChange={e => setBillDate(e.target.value)}/></label>
          <label>Due date<br/><input className="input" type="date" min={billDate} value={dueDate} onChange={e => setDueDate(e.target.value)}/></label>
          <label>Cost description<br/><input className="input" maxLength={4000} value={description} onChange={e => setDescription(e.target.value)}/></label>
        </div>
        <p className="text-sm">For previously imported shipments with supplier cash already posted as an expense, reconcile the legacy posting first. The system blocks double-counting.</p>
        <button disabled={busy || !shipmentId} className="btn btn-primary" type="submit">{busy ? "Saving..." : "Save draft supplier invoice"}</button>
      </form>
    </section>
    <section className="card" style={{ padding: 20, marginBottom: 20 }}>
      <h2 className="card-title">Approved supplier payables by currency</h2>
      <div style={{ display: "flex", flexWrap: "wrap", gap: 20 }}>
        {totals.map(([c,t]) => <div key={c}><strong>{c}</strong><p>Incurred: {n(t.billed,c)}</p><p>Settled: {n(t.paid,c)}</p><p><strong>Owed: {n(t.owed,c)}</strong></p></div>)}
        {totals.length === 0 && <p>No approved supplier invoices yet.</p>}
      </div>
    </section>
    <section className="card" style={{ padding: 20 }}>
      <h2 className="card-title">Supplier-bill register</h2>
      <div style={{ overflowX: "auto" }}><table className="table"><thead><tr>
        <th>Supplier / document</th><th>Shipment</th><th>Bill date</th><th>Status</th><th>Cost</th><th>Paid</th><th>Outstanding</th><th>Action</th>
      </tr></thead><tbody>{bills.map(b => <tr key={b.id}>
        <td>{b.supplierName}<br/><small>{b.vendorInvoiceReference}</small></td>
        <td>{shipments.find(s=>s.id===b.shipmentId)?.referenceCode || b.shipmentId}</td>
        <td>{b.billDate}</td><td>{b.status}</td>
        <td>{n(b.amount,b.currency)}</td><td>{n(b.amountPaid,b.currency)}</td><td>{n(b.balance,b.currency)}</td>
        <td>{b.status === "DRAFT" ? <button className="btn" disabled={busy} onClick={() => {
          if (!window.confirm("Approve supplier invoice and book its full cost as a payable? A different authorized staff member must have prepared it.")) return;
          void perform(async () => { await apiFetch(`${api}/${b.id}/approve`, { method: "POST" }); setMessage("Invoice approved; expense and payable posted."); });
        }}>Approve</button> : <button className="btn" onClick={() => {
          setSelectedBill(b); setPaidAmount(b.balance.toFixed(2));
          void apiFetch<SupplierPayment[]>(`${api}/${b.id}/payments`).then(setPaymentHistory).catch(() => setPaymentHistory([]));
        }}>Payments</button>}</td>
      </tr>)}{!bills.length && <tr><td colSpan={8}>No supplier bills recorded.</td></tr>}</tbody></table></div>
    </section>
    {selectedBill && <section className="card" style={{ padding: 20, marginTop: 20 }}>
      <h2 className="card-title">Supplier payment — {selectedBill.vendorInvoiceReference}</h2>
      <p>Outstanding: <strong>{n(selectedBill.balance, selectedBill.currency)}</strong></p>
      <form onSubmit={e => { e.preventDefault(); void perform(async () => {
        const result = await apiFetch<SupplierPayment>(`${api}/${selectedBill.id}/payments`, {
          method: "POST", body: JSON.stringify({amount: Number(paidAmount), currency: selectedBill.currency,
            paymentMethod, paymentReference: paymentReference.trim(),
            idempotencyKey: pendingSettlementKey.current ?? (pendingSettlementKey.current = crypto.randomUUID()) })
        });
        pendingSettlementKey.current = null;
        setPaymentReference(""); setPaidAmount("");
        setPaymentHistory(history => [...history, result]);
        setMessage(`Payment ${result.id} recorded with reference ${result.paymentReference}.`);
      }); }}>
        <div style={{ display: "flex", flexWrap: "wrap", gap: 12 }}>
          <label>Amount<br/><input className="input" required type="number" min="0.0001" step="0.0001" max={selectedBill.balance} value={paidAmount} onChange={e => setPaidAmount(e.target.value)}/></label>
          <label>Method<br/><select className="input" value={paymentMethod} onChange={e => setPaymentMethod(e.target.value)}>
            <option value="BANK_TRANSFER">Bank transfer</option><option value="MOBILE_MONEY">Mobile money</option><option value="CASH">Cash</option>
            <option value="CARD">Card</option><option value="OTHER">Other</option></select></label>
          <label>Bank/mobile money receipt reference<br/><input className="input" required maxLength={200} value={paymentReference} onChange={e => setPaymentReference(e.target.value)}/></label>
        </div>
        <div style={{ display: "flex", gap: 12, marginTop: 12 }}>
          <button className="btn btn-primary" type="submit" disabled={busy || selectedBill.balance <= 0}>Record settlement</button>
          <button className="btn" type="button" onClick={() => setSelectedBill(null)}>Close</button>
        </div>
      </form>
      <h3>Payment history</h3>
      <ul>{paymentHistory.map(p => <li key={p.id}>{new Date(p.paidAt).toLocaleString()} — {n(p.amount,p.currency)} ({p.paymentMethod}, {p.paymentReference})</li>)}</ul>
    </section>}
  </main>;
}
