"use client";

import { useCallback, useEffect, useState } from "react";
import { apiFetch, downloadApiFile } from "@/lib/api-client";
import { useAuth } from "@/lib/auth-context";

type FinancePeriod = {
  id: string;
  periodStart: string;
  periodEnd: string;
  status: "OPEN" | "CLOSED";
  closedAt?: string | null;
};
type ExportResult = {
  id: string;
  status: string;
  row_count: number;
  payload_hash: string;
};

export default function LedgerExportPage() {
  const { isLoading, accessToken, role } = useAuth();
  const [periods, setPeriods] = useState<FinancePeriod[]>([]);
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [exported, setExported] = useState<ExportResult | null>(null);
  const permitted = role === "ADMIN" || role === "FINANCE";

  const refresh = useCallback(async () => {
    const data = await apiFetch<FinancePeriod[]>("/api/platform/finance-periods", { cache: "no-store" });
    setPeriods(data);
  }, []);

  useEffect(() => {
    if (isLoading || !accessToken || !permitted) return;
    void refresh().catch((e: unknown) => setError(e instanceof Error ? e.message : "Unable to load financial periods"));
  }, [isLoading, accessToken, permitted, refresh]);

  async function perform(action: () => Promise<void>) {
    setBusy(true);
    setError("");
    setMessage("");
    try {
      await action();
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Financial operation failed");
    } finally {
      setBusy(false);
    }
  }

  if (isLoading) return <main className="page-container"><p>Loading financial control...</p></main>;
  if (!accessToken || !permitted) return <main className="page-container"><h1>Finance access required</h1><p>Only authorized finance or admin staff may close periods or export ledgers.</p></main>;

  return (
    <main className="page-container">
      <div className="page-header">
        <div>
          <h1 className="page-title">Financial periods &amp; accounting export</h1>
          <p className="page-subtitle">Only balanced, closed financial periods can be exported. Each downloaded CSV is hashed and stored as an immutable accounting-export record.</p>
        </div>
      </div>
      {error && <div role="alert" className="alert alert-error">{error}</div>}
      {message && <div role="status" className="alert alert-success">{message}</div>}
      <section className="card" style={{ marginBottom: 20, padding: 20 }}>
        <h2 className="card-title">Open a period</h2>
        <div style={{ display: "flex", flexWrap: "wrap", gap: 12, alignItems: "end" }}>
          <label>Start date<input aria-label="Start date" type="date" className="input" value={from} onChange={e => setFrom(e.target.value)} /></label>
          <label>End date<input aria-label="End date" type="date" className="input" value={to} onChange={e => setTo(e.target.value)} /></label>
          <button type="button" className="btn btn-primary" disabled={busy || !from || !to || from > to} onClick={() => void perform(async () => {
            await apiFetch("/api/platform/finance-periods", { method: "POST", body: JSON.stringify({ periodStart: from, periodEnd: to }) });
            setMessage("Financial period created.");
          })}>Create period</button>
        </div>
      </section>
      <section className="card" style={{ padding: 20 }}>
        <h2 className="card-title">Period register</h2>
        <p>Closing validates debit and credit totals separately for each currency. If the ledger is unbalanced, closing is refused.</p>
        <div style={{ overflowX: "auto" }}>
          <table className="table">
            <thead><tr><th>Start</th><th>End</th><th>Status</th><th>Actions</th></tr></thead>
            <tbody>
              {periods.map(p => <tr key={p.id}>
                <td>{p.periodStart}</td><td>{p.periodEnd}</td><td>{p.status}</td>
                <td>
                  {p.status === "OPEN" ? (
                    <button type="button" disabled={busy} className="btn" onClick={() => {
                      if (!window.confirm("Close this period? This locks it for accounting export and checks currency-level ledger balances.")) return;
                      void perform(async () => {
                        await apiFetch(`/api/enterprise-completion/finance/periods/${encodeURIComponent(p.id)}/close`, { method: "POST" });
                        setMessage("Period closed after ledger balance verification.");
                      });
                    }}>Close and validate</button>
                  ) : (
                    <button type="button" disabled={busy} className="btn" onClick={() => void perform(async () => {
                      const result = await apiFetch<ExportResult>("/api/enterprise-completion/finance/accounting-export", { method: "POST", body: JSON.stringify({ periodId: p.id, exportType: "CSV" }) });
                      setExported(result);
                      await downloadApiFile(`/api/enterprise-completion/finance/accounting-export/${encodeURIComponent(result.id)}/file`, `aal-ledger-${p.periodStart}-${p.periodEnd}.csv`);
                      setMessage(`Exported ${result.row_count} ledger entries. SHA-256: ${result.payload_hash}`);
                    })}>Export balanced CSV</button>
                  )}
                </td>
              </tr>)}
              {periods.length === 0 && <tr><td colSpan={4}>No financial periods are configured.</td></tr>}
            </tbody>
          </table>
        </div>
        {exported && <p><strong>Latest export:</strong> {exported.id} ({exported.status}). Record this identifier for audit and reconciliation.</p>}
      </section>
    </main>
  );
}
