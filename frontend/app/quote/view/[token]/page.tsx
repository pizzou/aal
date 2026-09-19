"use client";
import { useEffect, useState } from "react";
import { ApiError, PublicQuoteView, publicQuoteApi } from "@/lib/api-client";

export default function PublicQuotePage({ params }: { params: { token: string } }) {
  const [quote, setQuote] = useState<PublicQuoteView | null>(null);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);
  useEffect(() => { publicQuoteApi.view(params.token).then(setQuote).catch(e => setError(e instanceof ApiError ? e.message : "Quotation link is unavailable.")); }, [params.token]);
  async function respond(action: "ACCEPTED" | "DECLINED") {
    setBusy(true); setError("");
    try { const r = await publicQuoteApi.respond(params.token, action); setMessage(r.message); const v = await publicQuoteApi.view(params.token); setQuote(v); }
    catch (e) { setError(e instanceof ApiError ? e.message : "Unable to record your response."); }
    finally { setBusy(false); }
  }
  if (error) return <main className="public-flow"><section className="flow-success"><div className="public-eyebrow">AAL QUOTATION</div><h1>Quotation unavailable</h1><p>{error}</p></section></main>;
  if (!quote) return <main className="public-flow"><section className="flow-success"><div className="public-eyebrow">AAL QUOTATION</div><h1>Loading quotation…</h1></section></main>;
  return <main className="public-flow"><nav className="public-nav flow-nav"><div className="public-brand"><span className="public-brand-mark">✈</span><span><strong>AAL</strong><small>AFRICA LOGISTIC AVIATION</small></span></div></nav><section className="flow-layout"><div className="flow-intro"><div className="public-eyebrow">AAL QUOTATION</div><h1>{quote.quoteReference}</h1><p>Prepared for <strong>{quote.client || "Customer"}</strong>. Review the quotation details below and respond securely online.</p></div><div className="card" style={{ alignSelf: "start" }}><div className="metric-row"><span>Route</span><strong>{quote.route || "—"}</strong></div><div className="metric-row"><span>Service</span><strong>{quote.serviceType || "—"}</strong></div><div className="metric-row"><span>Commodity</span><strong>{quote.commodity || "—"}</strong></div><div className="metric-row"><span>Chargeable weight</span><strong>{quote.chargeableWeightKg ?? "—"} kg</strong></div><div className="metric-row"><span>Quoted total</span><strong>{(quote.quotedAmount ?? 0).toLocaleString()}</strong></div><div className="metric-row"><span>Valid until</span><strong>{quote.validUntil || "—"}</strong></div>{message && <div className="alert alert-success" style={{ marginTop: 16 }}>{message}</div>}{quote.actionable && !quote.response && <div style={{ display: "flex", gap: 10, marginTop: 20 }}><button className="btn btn-primary" disabled={busy} onClick={() => respond("ACCEPTED")}>{busy ? "Processing…" : "Accept quotation"}</button><button className="btn" disabled={busy} onClick={() => respond("DECLINED")}>Decline</button></div>}{quote.response && <div className="status status-neutral" style={{ marginTop: 18 }}>Response recorded: {quote.response}</div>}</div></section></main>;
}
