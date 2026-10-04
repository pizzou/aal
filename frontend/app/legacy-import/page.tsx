"use client";

import Link from "next/link";
import { ChangeEvent, useState } from "react";
import { aalExcelImportApi, AalExcelImportResult } from "@/lib/api-client";

export default function LegacyImportPage() {
  const [files, setFiles] = useState<File[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [result, setResult] = useState<AalExcelImportResult | null>(null);

  function onFiles(event: ChangeEvent<HTMLInputElement>) {
    setError("");
    setResult(null);
    setFiles(Array.from(event.target.files ?? []));
  }

  async function submit() {
    if (!files.length) {
      setError("Select at least one .xlsx or .xlsm workbook.");
      return;
    }

    setBusy(true);
    setError("");
    setResult(null);
    try {
      const imported = await aalExcelImportApi.importWorkbooks(files);
      setResult(imported);
    } catch (e) {
      setError(e instanceof Error ? e.message : "The Excel migration failed.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow">DATA MIGRATION</div>
          <h1 className="page-title">Legacy Excel migration</h1>
          <p className="page-subtitle">
            Move the client&apos;s existing operational records into the same
            database used for daily work. Upload both workbooks together; the
            importer reads columns from the workbook itself, not from a fixed
            filename or fixed worksheet name.
          </p>
        </div>
        <div className="actions">
          <Link className="btn" href="/shipments">Shipment register</Link>
          <Link className="btn" href="/reports">Management reports</Link>
        </div>
      </div>

      <section className="aal-hero-panel">
        <div>
          <span className="aal-hero-kicker">LEGACY → SYSTEM</span>
          <h2>Import the client&apos;s historical Excel data</h2>
          <p>
            The shipment register contains the daily MOTHERSHIP fields and the
            Command Center fields. Related workbook sheets such as invoices,
            clients, partners, tasks, expenses and quotations are imported into
            their corresponding application records. Calculated Excel columns
            such as chargeable weight, remaining balance and net income are
            calculated by the application from the imported source values.
          </p>
        </div>
      </section>

      <section className="card" style={{ marginTop: 20 }}>
        <h2>Select workbooks</h2>
        <p className="page-subtitle">
          You can select the MOTHERSHIP workbook, the Command Center workbook,
          or both. The same workbook can safely be uploaded again; completed
          files are recognized by their SHA-256 fingerprint.
        </p>

        <input
          type="file"
          accept=".xlsx,.xlsm"
          multiple
          onChange={onFiles}
          disabled={busy}
        />

        {files.length > 0 && (
          <ul style={{ marginTop: 16 }}>
            {files.map((file) => (
              <li key={`${file.name}-${file.size}`}>{file.name}</li>
            ))}
          </ul>
        )}

        {error && <p role="alert" style={{ marginTop: 16 }}>{error}</p>}

        <button
          className="btn btn-primary"
          type="button"
          onClick={submit}
          disabled={busy || files.length === 0}
          style={{ marginTop: 20 }}
        >
          {busy ? "Importing…" : "Import Excel data"}
        </button>
      </section>

      {result && (
        <section className="card" style={{ marginTop: 20 }}>
          <h2>Migration completed</h2>
          <div className="aal-kpi-grid" style={{ marginTop: 16 }}>
            <Metric label="Shipments" value={result.shipments} />
            <Metric label="Invoices" value={result.invoices} />
            <Metric label="Clients" value={result.clients} />
            <Metric label="Partners" value={result.partners} />
            <Metric label="Tasks" value={result.tasks} />
            <Metric label="Expenses" value={result.expenses} />
            <Metric label="Quotations" value={result.quotations} />
          </div>
          <p className="page-subtitle" style={{ marginTop: 18 }}>
            The imported records are now available through the normal Shipment
            Register, Billing, Customers, Tasks, Expenses and Management
            Reports screens. Duplicate shipment references are merged rather
            than creating a second shipment.
          </p>
        </section>
      )}
    </main>
  );
}

function Metric({ label, value }: { label: string; value: number }) {
  return (
    <div className="aal-metric-card">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}
