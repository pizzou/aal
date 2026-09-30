"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { airCargoApi, ApiError } from "@/lib/api-client";
import { useAuth } from "@/lib/auth-context";
import { useRouter } from "next/navigation";
import Icon from "@/components/Icon";

type Provider = {
  code?: string; configured?: boolean; active?: boolean; scheduleSearch?: boolean;
  liveCapacity?: boolean; booking?: boolean; amendment?: boolean; cancellation?: boolean;
  flightStatus?: boolean; awbSubmission?: boolean; webhooks?: boolean; standards?: string[];
  status?: string; configurationIssues?: string[];
};
type Health = {
  selectionMode?: string; activeProvider?: string; mode?: string; liveSearchConfigured?: boolean;
  liveAirlineAvailability?: boolean; liveBooking?: boolean; liveTracking?: boolean; webhooks?: boolean;
  providers?: Provider[]; deadLetters?: number;
};
const capabilities = [
  ["scheduleSearch", "Search"], ["liveCapacity", "Capacity"], ["booking", "Booking"],
  ["amendment", "Amend"], ["cancellation", "Cancel"], ["flightStatus", "Tracking"],
  ["awbSubmission", "e-AWB"], ["webhooks", "Webhooks"],
] as const;
const roles = ["ADMIN", "MANAGER", "OPERATIONS", "AIR_CARGO"];

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

  useEffect(() => {
    if (!isLoading && (!accessToken || !roles.includes(role || ""))) router.replace("/dashboard");
  }, [isLoading, accessToken, role, router]);

  async function load() {
    if (!accessToken) return;
    setLoading(true); setError("");
    try { setHealth((await airCargoApi.integrationHealth()) as Health); }
    catch (e) { setError(e instanceof ApiError ? e.message : "Unable to load airline integration health."); }
    finally { setLoading(false); }
  }
  useEffect(() => { if (!isLoading && accessToken && roles.includes(role || "")) void load(); }, [isLoading, accessToken, role]);

  const providers = useMemo(() => health?.providers ?? [], [health]);
  const configured = providers.filter((p) => p.configured).length;
  const searchable = providers.filter((p) => p.configured && p.scheduleSearch).length;
  const bookable = providers.filter((p) => p.configured && p.booking).length;
  const attention = providers.filter((p) => p.configured && !["ACTIVE", "AVAILABLE"].includes(p.status || "")).length;

  async function verifyAll() {
    const from = origin.trim().toUpperCase(), to = destination.trim().toUpperCase(), kg = Number(weight);
    if (!/^[A-Z]{3}$/.test(from) || !/^[A-Z]{3}$/.test(to) || from === to) return setError("Use two different three-letter IATA airport codes.");
    if (!Number.isFinite(kg) || kg <= 0) return setError("Weight must be greater than zero.");
    setTesting(true); setError(""); setMessage("");
    try {
      const result = await airCargoApi.verifyAllProviders({ origin: from, destination: to, weightKg: kg, from: new Date().toISOString(), to: new Date(Date.now() + 86400000).toISOString() });
      const reachable = Number(result.reachableProviderCount ?? 0), total = Number(result.providerCount ?? 0);
      setMessage(total ? `Live connectivity test completed: ${reachable}/${total} configured search providers reachable.` : "No configured live search providers are available. AAL remains in internal planning mode.");
      await load();
    } catch (e) { setError(e instanceof ApiError ? e.message : "Connectivity test failed."); }
    finally { setTesting(false); }
  }

  if (isLoading || !accessToken || !roles.includes(role || "")) return null;

  return <main className="page">
    <div className="page-head">
      <div><div className="eyebrow">AIR CARGO · INTEGRATION CONTROL</div><h1 className="page-title">Airline Integration Center</h1><p className="page-subtitle">One operational view of configured airline providers, real capabilities and live connectivity. A listed airline is not treated as connected until a provider actually returns it.</p></div>
      <div className="actions"><Link className="btn" href="/air-cargo"><Icon name="plane" size={15}/> Air cargo desk</Link><Link className="btn" href="/air-cargo/bookings">Booking desk</Link><button className="btn btn-primary" onClick={() => void load()} disabled={loading}>{loading ? "Refreshing…" : "Refresh health"}</button></div>
    </div>
    {error && <div className="alert alert-error" style={{marginBottom:14}}>{error}</div>}
    {message && <div className="alert alert-success" style={{marginBottom:14}}>{message}</div>}
    <section className="grid grid-4" style={{marginBottom:14}}>
      <div className="card kpi"><div className="kpi-label">Configured providers</div><div className="kpi-value">{configured}</div><div className="card-muted">Credentials/configuration present</div></div>
      <div className="card kpi"><div className="kpi-label">Live search</div><div className="kpi-value">{searchable}</div><div className="card-muted">Providers returning availability</div></div>
      <div className="card kpi"><div className="kpi-label">Live booking</div><div className="kpi-value">{bookable}</div><div className="card-muted">Providers able to submit bookings</div></div>
      <div className="card kpi"><div className="kpi-label">Open dead letters</div><div className="kpi-value">{Number(health?.deadLetters ?? 0)}</div><div className="card-muted">Provider operations requiring attention</div></div>
    </section>
    <section className="card" style={{marginBottom:14}}>
      <div className="page-head" style={{marginBottom:10}}><div><div className="eyebrow">OUTBOUND CONNECTIVITY TEST</div><h2 className="card-title">Test real airline-provider reachability</h2><p className="card-muted">This performs a live search against configured providers. It does not create a booking or reserve capacity.</p></div><span className={`status ${health?.liveSearchConfigured ? "status-success" : "status-warning"}`}>{health?.liveSearchConfigured ? "LIVE SEARCH AVAILABLE" : "NO LIVE SEARCH PROVIDER"}</span></div>
      <div className="grid grid-4"><div className="field"><label>Origin</label><input className="input" maxLength={3} value={origin} onChange={e=>setOrigin(e.target.value.toUpperCase())}/></div><div className="field"><label>Destination</label><input className="input" maxLength={3} value={destination} onChange={e=>setDestination(e.target.value.toUpperCase())}/></div><div className="field"><label>Chargeable weight (kg)</label><input className="input" type="number" min="0.1" step="0.001" value={weight} onChange={e=>setWeight(e.target.value)}/></div><div className="field" style={{alignSelf:"end"}}><button className="btn btn-primary" style={{width:"100%"}} onClick={()=>void verifyAll()} disabled={testing}>{testing ? "Testing providers…" : "Run live test"}</button></div></div>
    </section>
    <section className="card" style={{marginBottom:14}}>
      <div className="page-head" style={{marginBottom:12}}><div><div className="eyebrow">PROVIDER REGISTER</div><h2 className="card-title">Configured airline connectivity</h2></div><span className="status status-neutral">{providers.length} adapters</span></div>
      {providers.length === 0 ? <div className="card-muted">No provider adapters were returned by the backend.</div> : <div className="table-wrap"><table className="table"><thead><tr><th>Provider</th><th>Status</th><th>Capabilities</th><th>Standards</th><th>Configuration</th></tr></thead><tbody>{providers.map(p=><tr key={p.code}><td><strong>{p.code || "UNKNOWN"}</strong>{p.active&&<div className="card-muted">Active routing provider</div>}</td><td><span className={`status ${p.configured ? p.status === "ACTIVE" ? "status-success" : "status-neutral" : "status-warning"}`}>{p.status || (p.configured ? "AVAILABLE" : "NOT CONFIGURED")}</span></td><td><div style={{display:"flex",flexWrap:"wrap",gap:5}}>{capabilities.map(([key,label])=><span key={label} className={`status ${p[key] ? "status-success" : "status-neutral"}`} style={{fontSize:11}}>{label}</span>)}</div></td><td>{p.standards?.length ? p.standards.join(" · ") : "—"}</td><td>{p.configurationIssues?.length ? <div className="card-muted">{p.configurationIssues.join(" · ")}</div> : p.configured ? "Ready" : "Credentials required"}</td></tr>)}</tbody></table></div>}
    </section>
    <section className="grid grid-3">
      <div className="card"><div className="eyebrow">ROUTING MODE</div><h2 className="card-title">{health?.mode?.replaceAll("_"," ") || "UNKNOWN"}</h2><p className="card-muted">Active provider: <strong>{health?.activeProvider || "NONE"}</strong>. Selection mode: {health?.selectionMode || "—"}.</p></div>
      <div className="card"><div className="eyebrow">LIVE CAPABILITIES</div><div className="stack-list"><div>Availability <strong>{health?.liveAirlineAvailability ? "Live" : "Not configured"}</strong></div><div>Booking <strong>{health?.liveBooking ? "Live" : "Not configured"}</strong></div><div>Tracking <strong>{health?.liveTracking ? "Live" : "Not configured"}</strong></div><div>Webhooks <strong>{health?.webhooks ? "Available" : "Not configured"}</strong></div></div></div>
      <div className="card"><div className="eyebrow">OPERATIONAL NOTE</div><h2 className="card-title">No fake connectivity</h2><p className="card-muted">Airlines shown in the directory are catalogue entries. Only provider-returned offers are treated as live capacity, and only providers with booking capability can receive a booking request.</p>{attention>0&&<p className="card-muted" style={{marginTop:8}}>{attention} configured provider(s) need attention.</p>}</div>
    </section>
  </main>;
}
