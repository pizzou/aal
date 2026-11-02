"use client";

import Link from "next/link";
import { useEffect, useMemo, useState } from "react";
import { useAuth } from "@/lib/auth-context";
import { commercialApi, type InvoiceRecord } from "@/lib/api-client";

/** A focused collections register; all cash posting still uses the canonical
 * billing payment endpoint and its idempotency/receipt safeguards. */
export default function PaymentsPage() {
  const { accessToken, isLoading } = useAuth();
  const [invoices, setInvoices] = useState<InvoiceRecord[]>([]);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState("");
  const [filter, setFilter] = useState<"OPEN" | "ALL">("OPEN");
  useEffect(() => {
    if (!accessToken) return;
    let active = true;
    setLoading(true);
    commercialApi.invoices()
      .then((rows) => { if (active) setInvoices(rows); })
      .catch((e) => { if (active) setError(e instanceof Error ? e.message : "Invoices are temporarily unavailable"); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [accessToken]);
  const rows = useMemo(() => invoices.filter((invoice) =>
    (filter === "ALL" || invoice.balance > 0) &&
    (!search.trim() || `${invoice.invoiceNo} ${invoice.client || ""}`.toLowerCase().includes(search.trim().toLowerCase()))
  ), [invoices, search, filter]);
  if (isLoading || !accessToken) return null;
  return <main className="page">
    <div className="page-head"><div><div className="eyebrow">WORKFLOW · STEP 6 OF 7</div>
      <h1 className="page-title">Customer payments</h1>
      <p className="page-subtitle">Record actual customer funds against an issued invoice. Every collection needs a transaction reference and creates a receipt; never record cash on the shipment form.</p>
    </div><div className="actions"><Link className="btn" href="/billing">← Step 5 · Invoice</Link><Link className="btn btn-primary" href="/reports">Step 7 · Reports →</Link></div></div>
    {error && <div className="alert alert-error" role="alert">{error}</div>}
    <div className="card" style={{ marginTop: 20 }}>
      <div className="actions" style={{ justifyContent: "space-between", marginBottom: 16 }}>
        <label className="field"><span>Search invoice or customer</span><input value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Invoice number / customer" /></label>
        <label className="field"><span>Show</span><select value={filter} onChange={(e) => setFilter(e.target.value as "OPEN" | "ALL")}><option value="OPEN">Open balances</option><option value="ALL">All invoices</option></select></label>
      </div>
      <div className="table-wrap"><table className="table"><thead><tr><th>Invoice</th><th>Customer</th><th>Currency</th><th>Invoiced</th><th>Received</th><th>Outstanding</th><th>Action</th></tr></thead><tbody>
        {rows.map((inv) => <tr key={inv.id}>
          <td><strong>{inv.invoiceNo}</strong></td><td>{inv.client || "—"}</td><td>{inv.currency}</td>
          <td>{Number(inv.invoiceAmount || 0).toLocaleString()}</td><td>{Number(inv.amountPaid || 0).toLocaleString()}</td>
          <td>{Number(inv.balance || 0).toLocaleString()}</td>
          <td>{inv.balance > 0 ? <Link className="btn btn-primary btn-small" href={`/billing?invoiceId=${encodeURIComponent(inv.id)}#record-collection`}>Receive payment →</Link> : <span className="status status-success">Fully paid</span>}</td>
        </tr>)}
        {!loading && !rows.length && <tr><td colSpan={7}>No matching invoices. Create an invoice after confirmed delivery/POD.</td></tr>}
      </tbody></table></div>
    </div>
  </main>;
}
