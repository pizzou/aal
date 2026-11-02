"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  ApiError,
  OperationalException,
  operationsApi,
} from "@/lib/api-client";
import { useAuth } from "@/lib/auth-context";

const STATUSES = ["OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED"];
const SEVERITIES = ["LOW", "MEDIUM", "HIGH", "CRITICAL"];

function messageFrom(error: unknown): string {
  return error instanceof Error
    ? error.message
    : "The server could not complete this request.";
}

export default function ExceptionsPage() {
  const { accessToken, isLoading, role } = useAuth();
  const router = useRouter();
  const [status, setStatus] = useState("OPEN");
  const [rows, setRows] = useState<OperationalException[]>([]);
  const [loading, setLoading] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [resolution, setResolution] = useState("");
  const [actionTaken, setActionTaken] = useState("");
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");
  const [creating, setCreating] = useState(false);
  const [newException, setNewException] = useState({
    type: "DELAY",
    severity: "MEDIUM",
    owner: "",
    shipmentId: "",
    description: "",
  });

  const allowed = ["ADMIN", "MANAGER", "OPERATIONS"].includes(role ?? "");
  const reload = useCallback(
    async (signal?: { cancelled: boolean }) => {
      setLoading(true);
      setError("");
      try {
        const result = await operationsApi.exceptions(status);
        if (!signal?.cancelled) setRows(Array.isArray(result) ? result : []);
      } catch (reason) {
        if (!signal?.cancelled)
          setError(`Unable to load incidents: ${messageFrom(reason)}`);
      } finally {
        if (!signal?.cancelled) setLoading(false);
      }
    },
    [status],
  );

  useEffect(() => {
    if (!isLoading && !accessToken) router.replace("/login");
  }, [isLoading, accessToken, router]);

  useEffect(() => {
    if (!accessToken || !allowed) return;
    const state = { cancelled: false };
    void reload(state);
    return () => {
      state.cancelled = true;
    };
  }, [accessToken, allowed, reload]);

  async function resolveIncident(event: React.FormEvent) {
    event.preventDefault();
    if (!selectedId || busyId) return;
    if (!resolution.trim() || !actionTaken.trim()) {
      setError(
        "Enter both the corrective action and the actual resolution before closing this incident.",
      );
      return;
    }
    setBusyId(selectedId);
    setError("");
    setSuccess("");
    try {
      await operationsApi.resolveException(selectedId, {
        resolution: resolution.trim(),
        actionTaken: actionTaken.trim(),
      });
      setSelectedId(null);
      setResolution("");
      setActionTaken("");
      setSuccess("Incident resolved and the action was saved to its record.");
      await reload();
    } catch (reason) {
      setError(
        `Resolution not confirmed: ${messageFrom(reason)}. Check this incident before trying again.`,
      );
    } finally {
      setBusyId(null);
    }
  }

  async function openIncident(event: React.FormEvent) {
    event.preventDefault();
    if (busyId) return;
    if (!newException.description.trim()) {
      setError("Describe the incident before submitting.");
      return;
    }
    setBusyId("create");
    setError("");
    setSuccess("");
    try {
      await operationsApi.openException({
        type: newException.type.trim().toUpperCase(),
        severity: newException.severity,
        owner: newException.owner.trim() || null,
        shipmentId: newException.shipmentId.trim() || null,
        description: newException.description.trim(),
      });
      setNewException({
        type: "DELAY",
        severity: "MEDIUM",
        owner: "",
        shipmentId: "",
        description: "",
      });
      setCreating(false);
      setStatus("OPEN");
      setSuccess(
        "Incident recorded. Assign the owner and verify recovery before resolving it.",
      );
      if (status === "OPEN") await reload();
    } catch (reason) {
      setError(
        `Incident could not be confirmed: ${messageFrom(reason)}. Check the list before resubmitting.`,
      );
    } finally {
      setBusyId(null);
    }
  }

  if (isLoading || !accessToken)
    return (
      <main className="page">
        <p>Loading exceptions…</p>
      </main>
    );
  if (!allowed)
    return (
      <main className="page">
        <h1>Exception management</h1>
        <p>Your role cannot manage incidents.</p>
      </main>
    );

  return (
    <main className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow">AAL / OPERATIONS</div>
          <h1 className="page-title">Exception management</h1>
          <p className="page-subtitle">
            Record incidents, assign responsibility and document actual recovery
            before marking an incident resolved.
          </p>
        </div>
        <div className="actions">
          <button
            type="button"
            className="btn"
            onClick={() => void reload()}
            disabled={loading}
          >
            Refresh
          </button>
          <button
            type="button"
            className="btn btn-primary"
            onClick={() => {
              setCreating(!creating);
              setError("");
            }}
            disabled={Boolean(busyId)}
          >
            {creating ? "Cancel new incident" : "+ Report incident"}
          </button>
        </div>
      </div>

      {error && (
        <div className="alert alert-error" role="alert">
          {error}
        </div>
      )}
      {success && (
        <div className="alert alert-success" role="status">
          {success}
        </div>
      )}

      {creating && (
        <section className="form-panel" aria-label="Report an incident">
          <h2>New operational incident</h2>
          <form onSubmit={openIncident} className="form-grid">
            <label className="field">
              <span>Type</span>
              <input
                maxLength={80}
                required
                value={newException.type}
                onChange={(e) =>
                  setNewException((v) => ({ ...v, type: e.target.value }))
                }
              />
            </label>
            <label className="field">
              <span>Severity</span>
              <select
                value={newException.severity}
                onChange={(e) =>
                  setNewException((v) => ({ ...v, severity: e.target.value }))
                }
              >
                {SEVERITIES.map((s) => (
                  <option key={s} value={s}>
                    {s}
                  </option>
                ))}
              </select>
            </label>
            <label className="field">
              <span>Responsible owner (optional)</span>
              <input
                maxLength={160}
                value={newException.owner}
                onChange={(e) =>
                  setNewException((v) => ({ ...v, owner: e.target.value }))
                }
              />
            </label>
            <label className="field">
              <span>Shipment UUID (optional)</span>
              <input
                value={newException.shipmentId}
                onChange={(e) =>
                  setNewException((v) => ({ ...v, shipmentId: e.target.value }))
                }
                placeholder="Select from shipment register"
              />
            </label>
            <label className="field">
              <span>What happened?</span>
              <textarea
                rows={3}
                maxLength={8000}
                required
                value={newException.description}
                onChange={(e) =>
                  setNewException((v) => ({
                    ...v,
                    description: e.target.value,
                  }))
                }
              />
            </label>
            <div className="actions">
              <button
                type="submit"
                className="btn btn-primary"
                disabled={Boolean(busyId)}
              >
                {busyId === "create" ? "Saving…" : "Record incident"}
              </button>
            </div>
          </form>
        </section>
      )}

      <section className="card">
        <div className="actions" style={{ marginBottom: 16 }}>
          <label className="field">
            <span>Status</span>
            <select
              value={status}
              onChange={(e) => {
                setStatus(e.target.value);
                setSelectedId(null);
                setError("");
              }}
            >
              {STATUSES.map((item) => (
                <option key={item} value={item}>
                  {item.replaceAll("_", " ")}
                </option>
              ))}
            </select>
          </label>
          <p className="page-subtitle">
            Latest 200 incidents in the selected status. Use the shipment
            register to inspect related movements.
          </p>
        </div>
        {loading && <p role="status">Loading incidents…</p>}
        {!loading && !rows.length && (
          <p>
            No {status.toLowerCase().replaceAll("_", " ")} incidents to display.
          </p>
        )}
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Severity / type</th>
                <th>Incident</th>
                <th>Owner</th>
                <th>Opened</th>
                <th>Action</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((item) => (
                <tr key={item.id}>
                  <td>
                    <span
                      className={`status ${["HIGH", "CRITICAL"].includes(item.severity) ? "status-danger" : "status-warning"}`}
                    >
                      {item.severity}
                    </span>
                    <p>{item.type}</p>
                  </td>
                  <td>
                    <p>{item.description}</p>
                    {item.shipmentId && (
                      <Link
                        href={`/shipments/${encodeURIComponent(item.shipmentId)}`}
                      >
                        Open related shipment
                      </Link>
                    )}
                    {item.resolution && (
                      <p>
                        <strong>Resolution:</strong> {item.resolution}
                      </p>
                    )}
                  </td>
                  <td>{item.owner || "Unassigned"}</td>
                  <td>
                    {item.createdAt
                      ? new Date(item.createdAt).toLocaleString()
                      : "—"}
                  </td>
                  <td>
                    {!["RESOLVED", "CLOSED"].includes(item.status) ? (
                      <button
                        className="btn"
                        type="button"
                        disabled={Boolean(busyId)}
                        onClick={() => {
                          setSelectedId(
                            item.id === selectedId ? null : item.id,
                          );
                          setError("");
                        }}
                      >
                        {item.id === selectedId
                          ? "Cancel"
                          : "Document resolution"}
                      </button>
                    ) : (
                      <span>{item.status}</span>
                    )}
                    {selectedId === item.id && (
                      <form
                        onSubmit={resolveIncident}
                        style={{ minWidth: 250 }}
                      >
                        <label className="field">
                          <span>Corrective action taken</span>
                          <textarea
                            rows={2}
                            required
                            maxLength={4000}
                            value={actionTaken}
                            onChange={(e) => setActionTaken(e.target.value)}
                          />
                        </label>
                        <label className="field">
                          <span>Confirmed resolution</span>
                          <textarea
                            rows={2}
                            required
                            maxLength={4000}
                            value={resolution}
                            onChange={(e) => setResolution(e.target.value)}
                          />
                        </label>
                        <button
                          className="btn btn-primary"
                          disabled={Boolean(busyId)}
                          type="submit"
                        >
                          {busyId === item.id
                            ? "Saving…"
                            : "Confirm resolution"}
                        </button>
                      </form>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </main>
  );
}
