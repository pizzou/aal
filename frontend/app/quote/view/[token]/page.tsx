"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { ApiError, PublicQuoteView, publicQuoteApi } from "@/lib/api-client";
import Icon from "@/components/Icon";

export default function PublicQuotePage({
  params,
}: {
  params: { token: string };
}) {
  const [quote, setQuote] = useState<PublicQuoteView | null>(null);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    publicQuoteApi
      .view(params.token)
      .then(setQuote)
      .catch((e: unknown) =>
        setError(
          e instanceof ApiError ? e.message : "Quotation link is unavailable.",
        ),
      );
  }, [params.token]);

  async function respond(action: "ACCEPTED" | "DECLINED") {
    setBusy(true);
    setError("");
    try {
      const response = await publicQuoteApi.respond(params.token, action);
      setMessage(response.message);
      setQuote(await publicQuoteApi.view(params.token));
    } catch (e) {
      setError(
        e instanceof ApiError ? e.message : "Unable to record your response.",
      );
    } finally {
      setBusy(false);
    }
  }

  if (error)
    return (
      <main className="public-flow">
        <FlowNav />
        <section className="flow-success">
          <div className="public-eyebrow">AAL QUOTATION</div>
          <h1>Quotation unavailable</h1>
          <p>{error}</p>
          <Link className="public-main-button" href="/quote">
            Request a new quote <Icon name="arrow" size={14} />
          </Link>
        </section>
      </main>
    );
  if (!quote)
    return (
      <main className="public-flow">
        <FlowNav />
        <section className="flow-success">
          <div className="public-eyebrow">AAL QUOTATION</div>
          <h1>Loading quotation…</h1>
        </section>
      </main>
    );

  const accepted =
    quote.response === "ACCEPTED" || quote.status.toUpperCase() === "WON";
  const booked = quote.status.toUpperCase() === "CONVERTED";

  return (
    <main className="public-flow">
      <FlowNav />
      <section className="quote-view-shell">
        <div className="quote-view-head">
          <div>
            <div className="public-eyebrow">AAL QUOTATION</div>
            <h1>{quote.quoteReference}</h1>
            <p>
              Prepared for <strong>{quote.client || "Customer"}</strong>. Review
              the quotation, accept it and continue directly to booking.
            </p>
          </div>
          <span
            className={`track-status ${booked ? "quote-status-booked" : accepted ? "" : "quote-status-open"}`}
          >
            {booked ? "BOOKED" : accepted ? "ACCEPTED" : quote.status}
          </span>
        </div>

        <div className="quote-view-grid">
          <section className="quote-view-card">
            <div className="quote-view-card-label">SHIPMENT</div>
            <QuoteRow label="Route" value={quote.route || "—"} />
            <QuoteRow label="Service" value={quote.serviceType || "—"} />
            <QuoteRow label="Commodity" value={quote.commodity || "—"} />
            <QuoteRow
              label="Chargeable weight"
              value={
                quote.chargeableWeightKg == null
                  ? "—"
                  : `${quote.chargeableWeightKg} kg`
              }
            />
            <QuoteRow label="Valid until" value={quote.validUntil || "—"} />
          </section>

          <section className="quote-view-card quote-price-card">
            <div className="quote-view-card-label">QUOTED TOTAL</div>
            <div className="quote-price">
              {quote.quotedAmount == null
                ? "—"
                : quote.quotedAmount.toLocaleString()}
            </div>
            <p>No account is required to respond or book.</p>
            {message && <div className="alert alert-success">{message}</div>}
            {error && <div className="alert alert-error">{error}</div>}
            {!quote.response && quote.actionable && (
              <div className="quote-action-row">
                <button
                  className="public-main-button"
                  disabled={busy}
                  onClick={() => respond("ACCEPTED")}
                >
                  {busy ? "Processing…" : "Accept quotation"}
                  <Icon name="arrow" size={14} />
                </button>
                <button
                  className="flow-secondary"
                  disabled={busy}
                  onClick={() => respond("DECLINED")}
                >
                  Decline
                </button>
              </div>
            )}
            {accepted && !booked && (
              <Link
                className="public-main-button quote-book-button"
                href={`/book?quote=${encodeURIComponent(params.token)}`}
              >
                Book this quotation <Icon name="arrow" size={14} />
              </Link>
            )}
            {booked && (
              <Link
                className="public-main-button quote-book-button"
                href="/track"
              >
                Track your shipment <Icon name="arrow" size={14} />
              </Link>
            )}
          </section>
        </div>
      </section>
    </main>
  );
}

function QuoteRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="quote-row">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function FlowNav() {
  return (
    <nav className="public-nav flow-nav">
      <Link href="/" className="public-brand">
        <span className="public-brand-mark">
          <Icon name="plane" size={20} />
        </span>
        <span>
          <strong>AAL</strong>
          <small>AFRICA LOGISTIC AVIATION</small>
        </span>
      </Link>
      <div className="flow-nav-links">
        <Link href="/quote">Quote</Link>
        <Link href="/book">Book</Link>
        <Link href="/track">Track</Link>
        <Link href="/login">AAL team</Link>
      </div>
    </nav>
  );
}
