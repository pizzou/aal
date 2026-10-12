"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth-context";
import { ApiError, auditApi } from "@/lib/api-client";

type AuditRow = {
  id: string;
  createdAt: string;
  action: string;
  resourceType: string;
  resourceId?: string | null;
  method: string;
  path: string;
  userId?: string | null;
  userName?: string | null;
  userEmail?: string | null;
  success: boolean;
  statusCode?: number | null;
  ipAddress?: string | null;
};

export default function AuditPage() {
  const { accessToken, isLoading } = useAuth();
  const router = useRouter();
  const [rows, setRows] = useState<AuditRow[]>([]);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!isLoading && !accessToken) router.replace("/login");
  }, [isLoading, accessToken, router]);

  useEffect(() => {
    if (!accessToken) return;
    let active = true;
    setLoading(true);
    setError("");
    auditApi
      .list()
      .then((page) => {
        if (active) setRows(Array.isArray(page.content) ? page.content : []);
      })
      .catch((e) => {
        if (active)
          setError(
            e instanceof ApiError ? e.message : "Unable to load audit trail",
          );
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [accessToken]);

  if (isLoading || !accessToken) return null;

  return (
    <main className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow">SECURITY & GOVERNANCE</div>
          <h1 className="page-title">Audit Trail</h1>
          <p className="page-subtitle">
            Tenant-scoped record of authenticated controller actions, outcomes
            and request metadata. Secrets and request bodies are intentionally
            excluded.
          </p>
        </div>
        <button
          className="btn"
          disabled={loading}
          onClick={() => window.location.reload()}
        >
          {loading ? "Refreshing…" : "Refresh audit trail"}
        </button>
      </div>
      {error && (
        <div className="alert alert-error" role="alert">
          {error}
        </div>
      )}
      <section className="card" style={{ marginTop: 14 }}>
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Time</th>
                <th>Action</th>
                <th>Resource</th>
                <th>Method / path</th>
                <th>User</th>
                <th>Result</th>
                <th>IP</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td>
                    {r.createdAt ? new Date(r.createdAt).toLocaleString() : "—"}
                  </td>
                  <td>
                    <strong>{r.action || "—"}</strong>
                  </td>
                  <td>
                    {r.resourceType || "—"}
                    {r.resourceId ? <small>{r.resourceId}</small> : null}
                  </td>
                  <td>
                    {r.method || "—"}
                    <small>{r.path || "—"}</small>
                  </td>
                  <td>
                    <strong>
                      {r.userName ||
                        r.userEmail ||
                        (r.userId
                          ? "User identity unavailable"
                          : "System / unauthenticated")}
                    </strong>
                    {r.userEmail && r.userName ? (
                      <small>{r.userEmail}</small>
                    ) : null}
                    {r.userId ? <small>{r.userId}</small> : null}
                  </td>
                  <td>
                    <span
                      className={
                        r.success
                          ? "status status-success"
                          : "status status-danger"
                      }
                    >
                      {r.statusCode ?? (r.success ? "Success" : "Failed")}
                    </span>
                  </td>
                  <td>{r.ipAddress || "—"}</td>
                </tr>
              ))}
              {!rows.length && (
                <tr>
                  <td colSpan={7} className="empty">
                    {loading
                      ? "Loading audit records…"
                      : "No audit records found for this tenant."}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>
    </main>
  );
}
