"use client";

import Link from "next/link";
import { ChangeEvent, DragEvent, useMemo, useState } from "react";
import {
  ApiError,
  aalExcelImportApi,
  AalExcelImportResult,
} from "@/lib/api-client";

const MAX_FILE_BYTES = 50 * 1024 * 1024;
const MAX_TOTAL_BYTES = 55 * 1024 * 1024;
const ACCEPTED = /\.(xls|xlsx|xlsm)$/i;

export default function LegacyImportPage() {
  const [files, setFiles] = useState<File[]>([]);
  const [busy, setBusy] = useState(false);
  const [dragging, setDragging] = useState(false);
  const [error, setError] = useState("");
  const [result, setResult] = useState<AalExcelImportResult | null>(null);

  const totalBytes = useMemo(
    () => files.reduce((sum, file) => sum + file.size, 0),
    [files],
  );

  function acceptFiles(next: File[]) {
    setError("");
    setResult(null);

    const invalid = next.find((file) => !ACCEPTED.test(file.name));
    if (invalid) {
      setFiles([]);
      setError(
        `${invalid.name} is not a supported workbook. Use .xls, .xlsx or .xlsm.`,
      );
      return;
    }

    const oversized = next.find((file) => file.size > MAX_FILE_BYTES);
    if (oversized) {
      setFiles([]);
      setError(
        `${oversized.name} exceeds the 50 MB limit. Reduce the workbook size and try again.`,
      );
      return;
    }

    const total = next.reduce((sum, file) => sum + file.size, 0);
    if (total > MAX_TOTAL_BYTES) {
      setFiles([]);
      setError(
        "The selected workbooks exceed the 55 MB request limit. Upload them in smaller batches.",
      );
      return;
    }

    if (next.length === 0) {
      setFiles([]);
      return;
    }

    setFiles(next);
  }

  function onFiles(event: ChangeEvent<HTMLInputElement>) {
    acceptFiles(Array.from(event.target.files ?? []));
  }

  function onDrop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault();
    setDragging(false);
    acceptFiles(Array.from(event.dataTransfer.files ?? []));
  }

  async function submit() {
    if (!files.length) {
      setError("Select at least one AAL workbook before starting the import.");
      return;
    }

    setBusy(true);
    setError("");
    setResult(null);

    try {
      const imported = await aalExcelImportApi.importWorkbooks(files);
      const importedTotal = Object.values(imported).reduce(
        (sum, value) => sum + Number(value || 0),
        0,
      );

      if (importedTotal === 0) {
        setError(
          "The workbook was processed, but no records were imported. This usually means the workbook headers do not match a supported AAL sheet. No silent success is reported.",
        );
      } else {
        setResult(imported);
      }
    } catch (e) {
      setError(
        e instanceof ApiError
          ? e.message
          : e instanceof Error
            ? e.message
            : "The Excel migration failed. Please retry or check the backend import log.",
      );
    } finally {
      setBusy(false);
    }
  }

  function removeFile(name: string, size: number) {
    setFiles((current) =>
      current.filter((file) => !(file.name === name && file.size === size)),
    );
    setError("");
    setResult(null);
  }

  return (
    <main className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow">DATA MIGRATION · CONTROLLED IMPORT</div>
          <h1 className="page-title">Legacy Excel migration</h1>
          <p className="page-subtitle">
            Bring historical AAL operational and commercial records into the
            controlled logistics database. The importer validates the workbook,
            identifies supported sheets from their headers and reports a
            definitive result instead of silently succeeding.
          </p>
        </div>
        <div className="actions">
          <Link className="btn" href="/shipments">
            Shipment register
          </Link>
          <Link className="btn" href="/reports">
            Management reports
          </Link>
        </div>
      </div>

      <section className="aal-hero-panel">
        <div>
          <span className="aal-hero-kicker">LEGACY → CANONICAL DATA</span>
          <h2>Safe, repeatable workbook migration</h2>
          <p>
            Supports AAL MOTHERSHIP, Command Center and related workbook sheets
            such as quotations, invoices, clients, partners, tasks and expenses.
            Duplicate workbook fingerprints are handled safely, and duplicate
            shipment references are merged rather than creating another
            operational shipment.
          </p>
        </div>
      </section>

      <section className="card" style={{ marginTop: 20 }}>
        <div
          onDragOver={(event) => {
            event.preventDefault();
            setDragging(true);
          }}
          onDragLeave={() => setDragging(false)}
          onDrop={onDrop}
          style={{
            border: "1px dashed var(--border, #d7deea)",
            borderRadius: 18,
            padding: "34px 24px",
            textAlign: "center",
            background: dragging
              ? "rgba(7, 26, 82, 0.05)"
              : "rgba(248, 250, 253, 0.75)",
            transition: "all .15s ease",
          }}
        >
          <div className="eyebrow">STEP 01 · SELECT SOURCE</div>
          <h2 style={{ marginTop: 8 }}>Drop AAL workbooks here</h2>
          <p
            className="page-subtitle"
            style={{ maxWidth: 700, margin: "8px auto 18px" }}
          >
            Accepted formats: .xls, .xlsx and .xlsm · maximum 50 MB per
            workbook. You may upload the MOTHERSHIP and Command Center workbooks
            together.
          </p>
          <label
            className="btn btn-primary"
            style={{ display: "inline-flex", cursor: "pointer" }}
          >
            Choose Excel files
            <input
              type="file"
              accept=".xls,.xlsx,.xlsm"
              multiple
              onChange={onFiles}
              disabled={busy}
              style={{ display: "none" }}
            />
          </label>
        </div>

        {files.length > 0 && (
          <div style={{ marginTop: 20 }}>
            <div className="eyebrow">SELECTED SOURCES</div>
            <div style={{ display: "grid", gap: 10, marginTop: 10 }}>
              {files.map((file) => (
                <div
                  key={`${file.name}-${file.size}`}
                  style={{
                    display: "flex",
                    alignItems: "center",
                    justifyContent: "space-between",
                    gap: 12,
                    padding: "12px 14px",
                    border: "1px solid var(--border, #e1e6ef)",
                    borderRadius: 12,
                  }}
                >
                  <div>
                    <strong>{file.name}</strong>
                    <div className="page-subtitle" style={{ marginTop: 2 }}>
                      {formatBytes(file.size)}
                    </div>
                  </div>
                  <button
                    className="btn"
                    type="button"
                    onClick={() => removeFile(file.name, file.size)}
                    disabled={busy}
                  >
                    Remove
                  </button>
                </div>
              ))}
            </div>
            <div className="page-subtitle" style={{ marginTop: 10 }}>
              {files.length} workbook{files.length === 1 ? "" : "s"} ·{" "}
              {formatBytes(totalBytes)} selected
            </div>
          </div>
        )}

        {error && (
          <div
            className="alert alert-error"
            role="alert"
            style={{ marginTop: 18 }}
          >
            <strong>Import not completed.</strong>
            <div style={{ marginTop: 4 }}>{error}</div>
          </div>
        )}

        <div
          style={{ display: "flex", justifyContent: "flex-end", marginTop: 20 }}
        >
          <button
            className="btn btn-primary"
            type="button"
            onClick={submit}
            disabled={busy || files.length === 0}
          >
            {busy ? "Validating & importing…" : "Start controlled import"}
          </button>
        </div>
      </section>

      {result && (
        <section className="card" style={{ marginTop: 20 }}>
          <div className="eyebrow">STEP 02 · VERIFIED RESULT</div>
          <h2 style={{ marginTop: 6 }}>Migration completed</h2>
          <p className="page-subtitle">
            The server returned a valid import receipt. The records are now
            available through the normal operational and reporting modules.
          </p>
          <div className="aal-kpi-grid" style={{ marginTop: 16 }}>
            <Metric label="Shipments" value={result.shipments} />
            <Metric label="Invoices" value={result.invoices} />
            <Metric label="Clients" value={result.clients} />
            <Metric label="Partners" value={result.partners} />
            <Metric label="Tasks" value={result.tasks} />
            <Metric label="Expenses" value={result.expenses} />
            <Metric label="Quotations" value={result.quotations} />
          </div>
          <div
            style={{
              marginTop: 18,
              padding: 14,
              borderRadius: 12,
              background: "rgba(20, 120, 80, .06)",
              border: "1px solid rgba(20, 120, 80, .16)",
            }}
          >
            <strong>Next step:</strong> open the Shipment Register or Management
            Reports and verify the imported operational and financial totals.
          </div>
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

function formatBytes(value: number) {
  if (value < 1024 * 1024) return `${Math.max(1, Math.round(value / 1024))} KB`;
  return `${(value / (1024 * 1024)).toFixed(1)} MB`;
}
