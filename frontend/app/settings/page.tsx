"use client";

import Link from "next/link";
import { useEffect, useMemo, useState } from "react";
import { ApiError, settingsApi, AalSystemSetting } from "@/lib/api-client";
import { useAuth } from "@/lib/auth-context";
import { useRouter } from "next/navigation";

const sections = [
  ["GENERAL", "Company identity, default currency and operating preferences."],
  [
    "OPERATIONS",
    "Default logistics behaviour, tracking and control-tower preferences.",
  ],
  [
    "NOTIFICATIONS",
    "Email, shipment events, invoice alerts and operational notifications.",
  ],
  ["SECURITY", "Authentication, session and access-control preferences."],
  [
    "INTEGRATIONS",
    "External provider readiness and integration configuration.",
  ],
  ["FINANCE", "Billing, FX and financial-control defaults."],
] as const;

const defaults: Record<string, Array<[string, string, string]>> = {
  GENERAL: [
    ["COMPANY_NAME", "Aviation Africa Logistics", "Company name"],
    ["DEFAULT_CURRENCY", "USD", "Default currency"],
    ["TIMEZONE", "Africa/Kigali", "Operating timezone"],
    ["DATE_FORMAT", "DD/MM/YYYY", "Date format"],
  ],
  OPERATIONS: [
    ["DEFAULT_MODE", "ROAD", "Default transport mode"],
    ["TRACKING_ENABLED", "true", "Public tracking enabled"],
    ["ETA_ALERT_THRESHOLD_HOURS", "6", "ETA alert threshold (hours)"],
    ["CONTROL_TOWER_REFRESH_SECONDS", "60", "Dashboard refresh interval"],
  ],
  NOTIFICATIONS: [
    ["EMAIL_ENABLED", "true", "Email notifications"],
    ["STATUS_NOTIFICATIONS", "true", "Shipment status notifications"],
    ["ETA_NOTIFICATIONS", "true", "ETA change notifications"],
    ["INVOICE_NOTIFICATIONS", "true", "Invoice notifications"],
  ],
  SECURITY: [
    ["SESSION_HOURS", "8", "Session lifetime (hours)"],
    ["MFA_REQUIRED", "false", "Require MFA for staff"],
    ["LOGIN_LOCKOUT_THRESHOLD", "5", "Failed-login threshold"],
    ["PUBLIC_TRACKING_EXPIRY_DAYS", "30", "Public tracking expiry"],
  ],
  INTEGRATIONS: [
    ["AIRLINE_API", "CONFIG_REQUIRED", "Airline API"],
    ["OCEAN_CARRIER_API", "CONFIG_REQUIRED", "Ocean carrier API"],
    ["CUSTOMS_API", "CONFIG_REQUIRED", "Customs authority"],
    ["SMS_PROVIDER", "CONFIG_REQUIRED", "SMS provider"],
  ],
  FINANCE: [
    ["BASE_CURRENCY", "USD", "Base accounting currency"],
    ["TAX_MODE", "CONFIGURE", "Tax handling"],
    ["FX_SOURCE", "MANUAL", "FX rate source"],
    ["FINANCIAL_PERIOD_LOCK", "true", "Lock closed periods"],
  ],
};

function errorMessage(e: unknown) {
  return e instanceof ApiError || e instanceof Error
    ? e.message
    : "Unable to save setting.";
}

export default function SettingsPage() {
  const { accessToken, role, isLoading } = useAuth();
  const router = useRouter();
  const [active, setActive] = useState("GENERAL");
  const [values, setValues] = useState<Record<string, string>>({});
  const [stored, setStored] = useState<AalSystemSetting[]>([]);
  const [busy, setBusy] = useState<string | null>(null);
  const [message, setMessage] = useState("");

  useEffect(() => {
    if (
      !isLoading &&
      (!accessToken ||
        !["ADMIN", "MANAGER", "FINANCE", "OPERATIONS"].includes(role || ""))
    )
      router.replace("/dashboard");
  }, [isLoading, accessToken, role, router]);
  useEffect(() => {
    if (!accessToken) return;
    settingsApi
      .list()
      .then((rows) => {
        setStored(rows);
        const next: Record<string, string> = {};
        rows.forEach((r) => {
          next[`${r.group}.${r.key}`] = r.value ?? "";
        });
        setValues(next);
      })
      .catch((e) => setMessage(errorMessage(e)));
  }, [accessToken]);

  const fields = useMemo(() => defaults[active] || [], [active]);
  const display = (group: string, key: string, fallback: string) =>
    values[`${group}.${key}`] ?? fallback;

  async function save(group: string, key: string) {
    const value = values[`${group}.${key}`] ?? "";
    setBusy(key);
    setMessage("");
    try {
      const saved = await settingsApi.save(group, key, value);
      setStored((current) => [
        ...current.filter((x) => !(x.group === group && x.key === key)),
        saved,
      ]);
      setValues((current) => ({
        ...current,
        [`${group}.${key}`]: saved.value ?? "",
      }));
      setMessage(`${key.replaceAll("_", " ")} saved.`);
    } catch (e) {
      setMessage(errorMessage(e));
    } finally {
      setBusy(null);
    }
  }

  if (isLoading || !accessToken) return null;

  return (
    <main className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow">SYSTEM ADMINISTRATION</div>
          <h1 className="page-title">Settings</h1>
          <p className="page-subtitle">
            One canonical workspace for AAL company, operations, notifications,
            security, integrations and finance configuration.
          </p>
        </div>
        <div className="actions">
          <Link className="btn" href="/users">
            Users & access
          </Link>
          <Link className="btn" href="/audit">
            Audit trail
          </Link>
        </div>
      </div>
      {message && (
        <div className="alert alert-info" style={{ marginBottom: 16 }}>
          {message}
        </div>
      )}
      <div className="grid grid-4" style={{ alignItems: "start" }}>
        <section className="card">
          <div className="stack-list">
            {sections.map(([key, label]) => (
              <button
                key={key}
                className={`nav-link ${active === key ? "active" : ""}`}
                style={{
                  width: "100%",
                  textAlign: "left",
                  border: 0,
                  cursor: "pointer",
                }}
                onClick={() => setActive(key)}
              >
                <span className="nav-link-copy">
                  <strong>{key}</strong>
                  <small>{label}</small>
                </span>
              </button>
            ))}
          </div>
        </section>
        <section className="card" style={{ gridColumn: "span 3" }}>
          <div className="page-head">
            <div>
              <div className="eyebrow">{active}</div>
              <h2 className="card-title">Configuration</h2>
              <p className="card-muted">
                Values are tenant-scoped and stored by the AAL platform.
              </p>
            </div>
          </div>
          <div className="grid grid-2">
            {fields.map(([key, fallback, label]) => (
              <div className="card" key={key}>
                <label className="field">
                  <span>{label}</span>
                  <input
                    className="input"
                    value={display(active, key, fallback)}
                    onChange={(e) =>
                      setValues((v) => ({
                        ...v,
                        [`${active}.${key}`]: e.target.value,
                      }))
                    }
                  />
                </label>
                <div className="actions" style={{ marginTop: 10 }}>
                  <button
                    className="btn btn-primary"
                    disabled={busy === key}
                    onClick={() => void save(active, key)}
                  >
                    {busy === key ? "Saving…" : "Save"}
                  </button>
                </div>
              </div>
            ))}
          </div>
        </section>
      </div>
      <section className="card" style={{ marginTop: 16 }}>
        <h2 className="card-title">Operational control centres</h2>
        <div className="grid grid-4">
          <Link
            className="card"
            href="/multimodal"
            style={{ textDecoration: "none", color: "inherit" }}
          >
            <strong>Multimodal control</strong>
            <div className="card-muted">
              Unified shipment journey across air, road, rail and ocean.
            </div>
          </Link>
          <Link
            className="card"
            href="/documents"
            style={{ textDecoration: "none", color: "inherit" }}
          >
            <strong>Documents & customs</strong>
            <div className="card-muted">
              Document repository, access and customs workflow.
            </div>
          </Link>
          <Link
            className="card"
            href="/billing"
            style={{ textDecoration: "none", color: "inherit" }}
          >
            <strong>Billing & finance</strong>
            <div className="card-muted">
              Invoices, payments, expenses, aging and reconciliation.
            </div>
          </Link>
          <Link
            className="card"
            href="/account/security"
            style={{ textDecoration: "none", color: "inherit" }}
          >
            <strong>Account security</strong>
            <div className="card-muted">
              Password, authentication and staff access controls.
            </div>
          </Link>
        </div>
      </section>
    </main>
  );
}
