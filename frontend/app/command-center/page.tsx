"use client";

import Link from "next/link";
import { useState } from "react";
import { useRouter } from "next/navigation";
import { ApiError, commandCenterApi } from "@/lib/api-client";
import { useAuth } from "@/lib/auth-context";

const rules = [
  ["Chargeable weight", "MAX(Gross Weight, Volumetric Weight)"],
  ["Total cost", "Supplier Cost + Other Cost"],
  ["Gross profit", "Client Revenue − Total Cost"],
  ["Margin", "Gross Profit ÷ Client Revenue"],
  ["Receivable", "Amount Billed − Amount Paid"],
];

export default function CommandCenterImportPage() {
  const { accessToken, isLoading } = useAuth();
  const router = useRouter();
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  if (isLoading || !accessToken) {
    if (!isLoading && !accessToken) router.push("/login");
    return null;
  }

  async function importWorkbook(file: File | undefined) {
    if (!file) return;
    setBusy(true);
    setError("");
    setMessage("");
    try {
      const result = await commandCenterApi.importExcel(file);
      setMessage(
        `Import completed: ${result.shipments ?? 0} shipments, ${result.quotations ?? 0} quotations, ${result.invoices ?? 0} invoices and supporting records.`,
      );
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Workbook import failed");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow">EXCEL DATA MIGRATION</div>
          <h1 className="page-title">Excel Migration Center</h1>
          <p className="page-subtitle">
            Controlled migration from the AAL MOTHERSHIP and Command Center
            workbooks into the production shipment, commercial and finance
            records.
          </p>
        </div>
        <label className="btn btn-primary">
          {busy ? "Importing…" : "Import .xlsx / .xlsm"}
          <input
            type="file"
            accept=".xlsx,.xlsm"
            hidden
            disabled={busy}
            onChange={(e) => importWorkbook(e.target.files?.[0])}
          />
        </label>
      </div>

      {message && (
        <div className="alert alert-success" style={{ marginBottom: 14 }}>
          {message}
        </div>
      )}
      {error && (
        <div className="alert alert-error" style={{ marginBottom: 14 }}>
          {error}
        </div>
      )}

      <section className="grid grid-3">
        <div className="card">
          <h2 className="card-title">MOTHERSHIP</h2>
          <p className="card-muted">
            Monthly shipment history, AWB, client, route, weight, billed amount,
            collections, supplier payments, other expenses and net income.
          </p>
          <Link className="btn" href="/shipments" style={{ marginTop: 12 }}>
            Open shipment register
          </Link>
        </div>
        <div className="card">
          <h2 className="card-title">COMMAND CENTER</h2>
          <p className="card-muted">
            Quotations, invoices, clients, partners, tasks and expenses are
            imported into their canonical application records.
          </p>
          <Link className="btn" href="/commercial" style={{ marginTop: 12 }}>
            Open commercial control
          </Link>
        </div>
        <div className="card">
          <h2 className="card-title">ACCOUNTING</h2>
          <p className="card-muted">
            Invoices and payments are posted through the single finance posting
            boundary. Supplier cash and approved expenses create auditable
            ledger movements.
          </p>
          <Link className="btn" href="/billing" style={{ marginTop: 12 }}>
            Open finance control
          </Link>
        </div>
      </section>

      <section className="card" style={{ marginTop: 16 }}>
        <div className="page-head" style={{ marginBottom: 10 }}>
          <div>
            <h2 className="card-title">Server-controlled workbook rules</h2>
            <div className="card-muted">
              Calculated columns are never trusted from browser or spreadsheet
              values.
            </div>
          </div>
        </div>
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Metric</th>
                <th>Authoritative calculation</th>
              </tr>
            </thead>
            <tbody>
              {rules.map(([name, formula]) => (
                <tr key={name}>
                  <td>
                    <strong>{name}</strong>
                  </td>
                  <td>
                    <code>{formula}</code>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="grid grid-2" style={{ marginTop: 16 }}>
        <div className="card">
          <h2 className="card-title">Import safety</h2>
          <ul className="card-muted" style={{ lineHeight: 1.8 }}>
            <li>Tenant-scoped records only.</li>
            <li>
              Duplicate shipment, quote and invoice identifiers are skipped; an
              identical workbook is idempotent and will not create another copy.
            </li>
            <li>
              Invoice collections are represented as payment records with
              idempotency keys.
            </li>
            <li>Financial values remain BigDecimal on the server.</li>
            <li>Calculated values are regenerated from source inputs.</li>
            <li>
              Legacy workbook data is imported into the canonical Shipment
              Register, Sales & Quotations and Billing & Receivables modules.
            </li>
            <li>
              The original workbook SHA-256 is recorded so the same file can be
              safely re-submitted without duplication.
            </li>
          </ul>
        </div>
        <div className="card">
          <h2 className="card-title">After import</h2>
          <div className="actions">
            <Link className="btn" href="/dashboard">
              Control tower
            </Link>
            <Link className="btn" href="/commercial">
              Customers & sales
            </Link>
            <Link className="btn" href="/billing">
              Receivables
            </Link>
            <Link className="btn" href="/reports">
              Management reports
            </Link>
          </div>
        </div>
      </section>
    </main>
  );
}
