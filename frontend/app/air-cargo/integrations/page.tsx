"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { airCargoApi, ApiError } from "@/lib/api-client";
import { useAuth } from "@/lib/auth-context";
import { useRouter } from "next/navigation";
import Icon from "@/components/Icon";

type ProviderCapabilityKey =
  | "scheduleSearch"
  | "liveCapacity"
  | "booking"
  | "amendment"
  | "cancellation"
  | "flightStatus"
  | "awbSubmission"
  | "webhooks";

type Provider = {
  code?: string;
  configured?: boolean;
  active?: boolean;
  scheduleSearch?: boolean;
  liveCapacity?: boolean;
  booking?: boolean;
  amendment?: boolean;
  cancellation?: boolean;
  flightStatus?: boolean;
  awbSubmission?: boolean;
  webhooks?: boolean;
  oauth2?: boolean;
  apiKey?: boolean;
  standards?: string[];
  status?: string;
  configurationIssues?: string[];
};

type Health = {
  selectionMode?: string;
  activeProvider?: string;
  mode?: string;
  configured?: boolean;
  liveSearchConfigured?: boolean;
  liveAirlineAvailability?: boolean;
  liveCapacity?: boolean;
  liveBooking?: boolean;
  liveTracking?: boolean;
  liveCancellation?: boolean;
  webhooks?: boolean;
  providers?: Provider[];
  deadLetters?: number;
};

type VerificationProviderResult = {
  provider?: string;
  status?: string;
  offerCount?: number;
  latencyMs?: number;
  error?: string;
};

type VerificationResult = {
  providerCount?: number;
  reachableProviderCount?: number;
  providers?: VerificationProviderResult[];
};

const roles = ["ADMIN", "MANAGER", "OPERATIONS", "AIR_CARGO"];

const capabilities: ReadonlyArray<readonly [ProviderCapabilityKey, string]> = [
  ["scheduleSearch", "Search"],
  ["liveCapacity", "Capacity"],
  ["booking", "Booking"],
  ["amendment", "Amend"],
  ["cancellation", "Cancel"],
  ["flightStatus", "Tracking"],
  ["awbSubmission", "e-AWB"],
  ["webhooks", "Webhooks"],
];

function capability(value?: boolean) {
  return value ? "Available" : "Not configured";
}

function providerName(code?: string) {
  switch ((code || "").toUpperCase()) {
    case "CARGOAI":
      return "CargoAi CargoCONNECT";
    case "QATAR":
      return "Qatar Airways Cargo";
    case "LHCARGO":
      return "Lufthansa Cargo";
    case "GENERIC_HTTP":
      return "Generic HTTP provider";
    default:
      return code || "Unknown provider";
  }
}

function providerDescription(code?: string) {
  switch ((code || "").toUpperCase()) {
    case "CARGOAI":
      return "Aggregation path for live schedules, rates, eBooking and tracking. Coverage depends on the AAL CargoCONNECT account and contracted airline coverage.";
    case "QATAR":
      return "Direct Qatar availability/rate adapter. The current AAL adapter intentionally does not invent a private booking contract.";
    case "LHCARGO":
      return "Direct shipment-tracking adapter. SmartBooking requires Lufthansa Cargo partner provisioning and tenant-specific credentials.";
    default:
      return "Custom provider adapter. Configure only against the provider's documented production API contract.";
  }
}

export default function AirCargoIntegrationsPage() {
  const { accessToken, role, isLoading } = useAuth();
  const router = useRouter();
  const [health, setHealth] = useState<Health | null>(null);
  const [loading, setLoading] = useState(true);
  const [testing, setTesting] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [origin, setOrigin] = useState("KGL");
  const [destination, setDestination] = useState("NBO");
  const [weight, setWeight] = useState("100");
  const [testResult, setTestResult] = useState<VerificationResult | null>(null);

  useEffect(() => {
    if (!isLoading && (!accessToken || !roles.includes(role || ""))) {
      router.replace("/dashboard");
    }
  }, [isLoading, accessToken, role, router]);

  async function load() {
    if (!accessToken) return;
    setLoading(true);
    setError("");
    try {
      setHealth((await airCargoApi.integrationHealth()) as Health);
    } catch (e) {
      setError(
        e instanceof ApiError
          ? e.message
          : "Unable to load airline integration health.",
      );
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    if (!isLoading && accessToken && roles.includes(role || "")) {
      void load();
    }
  }, [isLoading, accessToken, role]);

  const providers = useMemo(() => health?.providers ?? [], [health]);
  const configured = providers.filter((p) => p.configured).length;
  const searchable = providers.filter(
    (p) => p.configured && p.scheduleSearch,
  ).length;
  const bookable = providers.filter((p) => p.configured && p.booking).length;
  const attention = providers.filter(
    (p) => p.configured && !["ACTIVE", "AVAILABLE"].includes(p.status || ""),
  ).length;

  async function verifyAll() {
    const from = origin.trim().toUpperCase();
    const to = destination.trim().toUpperCase();
    const kg = Number(weight);

    if (!/^[A-Z]{3}$/.test(from) || !/^[A-Z]{3}$/.test(to) || from === to) {
      setError("Use two different three-letter IATA airport codes.");
      return;
    }

    if (!Number.isFinite(kg) || kg <= 0) {
      setError("Weight must be greater than zero.");
      return;
    }

    setTesting(true);
    setError("");
    setMessage("");
    setTestResult(null);

    try {
      const result = (await airCargoApi.verifyAllProviders({
        origin: from,
        destination: to,
        weightKg: kg,
        from: new Date().toISOString(),
        to: new Date(Date.now() + 86400000).toISOString(),
      })) as VerificationResult;

      setTestResult(result);

      const reachable = Number(result.reachableProviderCount ?? 0);
      const total = Number(result.providerCount ?? 0);

      setMessage(
        total
          ? `Live connectivity test completed: ${reachable}/${total} configured search providers returned successfully.`
          : "No configured live search providers are available. AAL is operating in internal planning mode.",
      );

      await load();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Connectivity test failed.");
    } finally {
      setTesting(false);
    }
  }

  if (isLoading || !accessToken || !roles.includes(role || "")) {
    return null;
  }

  return (
    <main className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow">AIR CARGO · CONNECTIVITY CONTROL</div>
          <h1 className="page-title">Airline Integration Center</h1>
          <p className="page-subtitle">
            Configure real airline connectivity without confusing the carrier
            directory with live capacity. AAL only marks a provider live after
            its credentials are present and a real outbound test succeeds.
          </p>
        </div>
        <div className="actions">
          <Link className="btn" href="/air-cargo">
            <Icon name="plane" size={15} /> Air cargo desk
          </Link>
          <Link className="btn" href="/air-cargo/bookings">
            Booking desk
          </Link>
          <button
            className="btn btn-primary"
            onClick={() => void load()}
            disabled={loading}
          >
            {loading ? "Refreshing…" : "Refresh health"}
          </button>
        </div>
      </div>

      {error && (
        <div className="alert alert-error" style={{ marginBottom: 14 }}>
          {error}
        </div>
      )}
      {message && (
        <div className="alert alert-success" style={{ marginBottom: 14 }}>
          {message}
        </div>
      )}

      <section className="grid grid-4" style={{ marginBottom: 14 }}>
        <div className="card kpi">
          <div className="kpi-label">Configured providers</div>
          <div className="kpi-value">{configured}</div>
          <div className="card-muted">
            Credentials and endpoint configuration present
          </div>
        </div>
        <div className="card kpi">
          <div className="kpi-label">Live search</div>
          <div className="kpi-value">{searchable}</div>
          <div className="card-muted">Can return airline availability</div>
        </div>
        <div className="card kpi">
          <div className="kpi-label">Live booking</div>
          <div className="kpi-value">{bookable}</div>
          <div className="card-muted">Can submit eBookings</div>
        </div>
        <div className="card kpi">
          <div className="kpi-label">Integration exceptions</div>
          <div className="kpi-value">{Number(health?.deadLetters ?? 0)}</div>
          <div className="card-muted">Dead letters requiring review</div>
        </div>
      </section>

      <section className="card" style={{ marginBottom: 14 }}>
        <div className="page-head" style={{ marginBottom: 12 }}>
          <div>
            <div className="eyebrow">LIVE VERIFICATION</div>
            <h2 className="card-title">
              Prove the connection before operations use it
            </h2>
            <p className="card-muted">
              This calls every configured live search provider for the lane
              below. It never creates a booking or reserves airline capacity.
            </p>
          </div>
          <span
            className={`status ${health?.liveSearchConfigured ? "status-success" : "status-warning"}`}
          >
            {health?.liveSearchConfigured
              ? "LIVE SEARCH ENABLED"
              : "NO LIVE SEARCH"}
          </span>
        </div>
        <div className="grid grid-4">
          <div className="field">
            <label>Origin</label>
            <input
              className="input"
              maxLength={3}
              value={origin}
              onChange={(e) => setOrigin(e.target.value.toUpperCase())}
            />
          </div>
          <div className="field">
            <label>Destination</label>
            <input
              className="input"
              maxLength={3}
              value={destination}
              onChange={(e) => setDestination(e.target.value.toUpperCase())}
            />
          </div>
          <div className="field">
            <label>Chargeable weight (kg)</label>
            <input
              className="input"
              type="number"
              min="0.1"
              step="0.001"
              value={weight}
              onChange={(e) => setWeight(e.target.value)}
            />
          </div>
          <div className="field" style={{ alignSelf: "end" }}>
            <button
              className="btn btn-primary"
              style={{ width: "100%" }}
              onClick={() => void verifyAll()}
              disabled={testing}
            >
              {testing
                ? "Testing live providers…"
                : "Run live connectivity test"}
            </button>
          </div>
        </div>

        {testResult?.providers && testResult.providers.length > 0 && (
          <div className="integration-test-grid">
            {testResult.providers.map((row, index) => {
              const provider = row.provider || `Provider ${index + 1}`;
              const status = row.status || "UNKNOWN";
              const offerCount = Number(row.offerCount ?? 0);
              const latency =
                row.latencyMs == null ? "—" : String(row.latencyMs);

              return (
                <div
                  className="integration-test-row"
                  key={`${provider}-${index}`}
                >
                  <strong>{providerName(provider)}</strong>
                  <span
                    className={`status ${
                      status.startsWith("CONNECTED")
                        ? "status-success"
                        : status === "DEGRADED"
                          ? "status-danger"
                          : "status-warning"
                    }`}
                  >
                    {status}
                  </span>
                  <span className="card-muted">
                    {offerCount} offers · {latency} ms
                  </span>
                  {row.error ? (
                    <span className="card-muted">{row.error}</span>
                  ) : null}
                </div>
              );
            })}
          </div>
        )}
      </section>

      <section className="card" style={{ marginBottom: 14 }}>
        <div className="page-head" style={{ marginBottom: 12 }}>
          <div>
            <div className="eyebrow">PROVIDER CONTROL PLANE</div>
            <h2 className="card-title">Configured provider adapters</h2>
          </div>
          <span className="status status-neutral">
            {providers.length} adapters
          </span>
        </div>

        {providers.length === 0 ? (
          <div className="card-muted">
            No provider adapters were returned by the backend.
          </div>
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>Provider</th>
                  <th>Status</th>
                  <th>Capabilities</th>
                  <th>Standards</th>
                  <th>Configuration</th>
                </tr>
              </thead>
              <tbody>
                {providers.map((p, providerIndex) => {
                  const code = p.code || "UNKNOWN";
                  const status =
                    p.status || (p.configured ? "AVAILABLE" : "NOT CONFIGURED");
                  const standards =
                    p.standards && p.standards.length > 0
                      ? p.standards.join(" · ")
                      : "—";
                  const configuration =
                    p.configurationIssues && p.configurationIssues.length > 0
                      ? p.configurationIssues.join(" · ")
                      : p.configured
                        ? "Ready"
                        : "Credentials required";

                  return (
                    <tr key={`${code}-${providerIndex}`}>
                      <td>
                        <strong>{code}</strong>
                        {p.active ? (
                          <div className="card-muted">
                            Active routing provider
                          </div>
                        ) : null}
                      </td>
                      <td>
                        <span
                          className={`status ${
                            p.configured
                              ? status === "ACTIVE"
                                ? "status-success"
                                : "status-neutral"
                              : "status-warning"
                          }`}
                        >
                          {status}
                        </span>
                      </td>
                      <td>
                        <div
                          style={{
                            display: "flex",
                            flexWrap: "wrap",
                            gap: 5,
                          }}
                        >
                          {capabilities.map(([key, label]) => (
                            <span
                              key={label}
                              className={`status ${
                                p[key] ? "status-success" : "status-neutral"
                              }`}
                              style={{ fontSize: 11 }}
                            >
                              {label}
                            </span>
                          ))}
                        </div>
                      </td>
                      <td>{standards}</td>
                      <td>{configuration}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="grid grid-3">
        <div className="card">
          <div className="eyebrow">ROUTING MODE</div>
          <h2 className="card-title">
            {health?.mode?.replaceAll("_", " ") || "UNKNOWN"}
          </h2>
          <p className="card-muted">
            Active provider: <strong>{health?.activeProvider || "NONE"}</strong>
            . Selection mode: {health?.selectionMode || "—"}.
          </p>
        </div>
        <div className="card">
          <div className="eyebrow">LIVE CAPABILITIES</div>
          <div className="stack-list">
            <div>
              Availability{" "}
              <strong>
                {health?.liveAirlineAvailability ? "Live" : "Not configured"}
              </strong>
            </div>
            <div>
              Booking{" "}
              <strong>{health?.liveBooking ? "Live" : "Not configured"}</strong>
            </div>
            <div>
              Tracking{" "}
              <strong>
                {health?.liveTracking ? "Live" : "Not configured"}
              </strong>
            </div>
            <div>
              Webhooks{" "}
              <strong>
                {health?.webhooks ? "Available" : "Not configured"}
              </strong>
            </div>
          </div>
        </div>
        <div className="card">
          <div className="eyebrow">OPERATIONAL NOTE</div>
          <h2 className="card-title">No fake connectivity</h2>
          <p className="card-muted">
            Airlines shown in the directory are catalogue entries. Only
            provider-returned offers are treated as live capacity, and only
            providers with booking capability can receive a booking request.
          </p>
          {attention > 0 ? (
            <p className="card-muted" style={{ marginTop: 8 }}>
              {attention} configured provider(s) need attention.
            </p>
          ) : null}
        </div>
      </section>
    </main>
  );
}
