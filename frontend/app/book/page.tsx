"use client";

import { FormEvent, useEffect, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import {
  ApiError,
  PublicQuoteView,
  publicCommercialApi,
  publicQuoteApi,
} from "@/lib/api-client";
import Icon from "@/components/Icon";

const modes = [
  ["AIR", "Air freight"],
  ["SEA", "Sea freight"],
  ["ROAD", "Road freight"],
  ["RAIL", "Rail freight"],
] as const;

export default function BookPage() {
  const params = useSearchParams();
  const quoteToken = params.get("quote") || "";
  const requestToken = params.get("requestToken") || "";
  const selectedMode = params.get("mode") || "";
  const [quote, setQuote] = useState<PublicQuoteView | null>(null);
  const [form, setForm] = useState({
    origin: "Kigali, Rwanda",
    destination: "",
    mode: "AIR",
    reference: "",
    carrier: "",
    company: "",
    contactName: "",
    email: "",
    phone: "",
    commodity: "",
    packages: "",
  });
  const [done, setDone] = useState(false);
  const [result, setResult] = useState<{
    reference: string;
    trackingToken: string;
  } | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (quoteToken) {
      publicQuoteApi
        .view(quoteToken)
        .then((data) => {
          setQuote(data);
          const [origin, destination] = (data.route || "").split(
            /\s*(?:→|->)\s*/,
            2,
          );
          setForm((current) => ({
            ...current,
            origin: origin || current.origin,
            destination: destination || current.destination,
            mode: serviceToMode(data.serviceType),
            reference: data.quoteReference,
            company: data.client || current.company,
          }));
        })
        .catch(() => undefined);
      return;
    }
    if (requestToken) {
      publicCommercialApi
        .quoteRequest(requestToken)
        .then((data) => {
          setForm((current) => ({
            ...current,
            origin: data.origin || current.origin,
            destination: data.destination || current.destination,
            mode: selectedMode || current.mode,
            reference: data.quoteReference,
            company: data.company || current.company,
            contactName: data.contactName || current.contactName,
            email: data.email || current.email,
            phone: data.phone || current.phone,
            commodity: data.commodity || current.commodity,
            packages:
              data.packages == null ? current.packages : String(data.packages),
          }));
        })
        .catch(() => undefined);
    }
  }, [quoteToken, requestToken, selectedMode]);

  const update = (key: string, value: string) =>
    setForm((current) => ({ ...current, [key]: value }));

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError("");
    try {
      const response = quoteToken
        ? await publicQuoteApi.book(quoteToken, form.reference || undefined)
        : await publicCommercialApi.bookShipment({
            ...form,
            serviceType: form.mode,
            customerReference: form.reference,
            quoteRequestToken: requestToken || undefined,
            selectedMode: selectedMode || form.mode,
          });
      setResult({
        reference: response.reference,
        trackingToken: response.trackingToken,
      });
      setDone(true);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Unable to create booking");
    } finally {
      setBusy(false);
    }
  }

  if (done && result) {
    return (
      <main className="public-flow">
        <FlowNav />
        <section className="flow-success">
          <div className="flow-success-icon">
            <Icon name="ship" size={30} />
          </div>
          <div className="public-eyebrow">BOOKING CONFIRMED</div>
          <h1>Your shipment request is now with AAL.</h1>
          <p>
            Booking reference <strong>{result.reference}</strong> is in the
            operational workflow.
          </p>
          <div className="tracking-ticket">
            <span>PUBLIC TRACKING TOKEN</span>
            <strong>{result.trackingToken}</strong>
            <small>Keep this token to track without signing in.</small>
          </div>
          <div className="flow-success-actions">
            <Link
              className="public-main-button"
              href={`/track/${encodeURIComponent(result.trackingToken)}`}
            >
              Track shipment <Icon name="arrow" size={14} />
            </Link>
            <Link className="flow-secondary" href="/">
              Return to AAL
            </Link>
          </div>
        </section>
      </main>
    );
  }

  const fromQuote = Boolean(quoteToken && quote);
  const fromQuoteRequest = Boolean(requestToken);
  const quoteCanBook = !quoteToken || quote?.response === "ACCEPTED";

  return (
    <main className="public-flow">
      <FlowNav />
      <section className="flow-layout">
        <div className="flow-intro">
          <div className="public-eyebrow">02 / BOOK</div>
          <h1>
            {fromQuote
              ? "Book your accepted quotation."
              : fromQuoteRequest
                ? "Book from your quote request."
                : "Put the movement in motion."}
          </h1>
          <p>
            {fromQuote
              ? `Quotation ${quote?.quoteReference} is ready to convert into an AAL shipment.`
              : fromQuoteRequest
                ? "Your public quote request is already registered. Confirm your shipment details below."
                : "Book directly with AAL. No account is required to submit a booking request."}
          </p>
          <div className="flow-benefits">
            <span>
              <Icon name="shield" size={15} /> No login required
            </span>
            <span>
              <Icon name="globe" size={15} /> Track immediately
            </span>
            <span>
              <Icon name="chart" size={15} /> AAL operations
            </span>
          </div>
          {quoteToken && quote && (
            <div className="quote-summary-card">
              <span>ACCEPTED QUOTATION</span>
              <strong>{quote.quoteReference}</strong>
              <small>
                {quote.route || "—"} · {quote.serviceType || "—"}
              </small>
              <b>{quote.quotedAmount?.toLocaleString() || "—"}</b>
            </div>
          )}
        </div>
        <form className="flow-form" onSubmit={submit}>
          <div className="flow-form-head">
            <span>BOOKING DETAILS</span>
            <strong>{fromQuote ? "Confirm booking" : "New booking"}</strong>
          </div>
          <div className="flow-grid">
            <Field
              label="Origin"
              value={form.origin}
              onChange={(v) => update("origin", v)}
            />
            <Field
              label="Destination"
              value={form.destination}
              onChange={(v) => update("destination", v)}
            />
            <label className="flow-field">
              <span>Transport mode</span>
              <select
                value={form.mode}
                disabled={fromQuote}
                onChange={(e) => update("mode", e.target.value)}
              >
                {modes.map(([value, label]) => (
                  <option value={value} key={value}>
                    {label}
                  </option>
                ))}
              </select>
            </label>
            <Field
              label="Customer reference"
              value={form.reference}
              onChange={(v) => update("reference", v)}
            />
            <Field
              label="Preferred carrier"
              value={form.carrier}
              onChange={(v) => update("carrier", v)}
            />
            <Field
              label="Company"
              value={form.company}
              onChange={(v) => update("company", v)}
            />
            <Field
              label="Contact name"
              value={form.contactName}
              onChange={(v) => update("contactName", v)}
            />
            <Field
              label="Email"
              value={form.email}
              onChange={(v) => update("email", v)}
              type="email"
            />
            <Field
              label="Phone"
              value={form.phone}
              onChange={(v) => update("phone", v)}
            />
            <Field
              label="Commodity"
              value={form.commodity}
              onChange={(v) => update("commodity", v)}
            />
            <Field
              label="Packages"
              value={form.packages}
              onChange={(v) => update("packages", v)}
              type="number"
            />
          </div>
          {!quoteCanBook && !fromQuoteRequest && (
            <div className="alert alert-error">
              Accept the quotation before booking it.
            </div>
          )}
          {error && <div className="alert alert-error">{error}</div>}
          <button
            className="public-main-button flow-submit"
            disabled={busy || (!quoteCanBook && !fromQuoteRequest)}
          >
            {busy
              ? "Submitting…"
              : fromQuote
                ? "Confirm booking"
                : "Submit booking"}{" "}
            <Icon name="arrow" size={14} />
          </button>
          <small className="flow-privacy">
            No account is required. AAL will continue the operational workflow
            after submission.
          </small>
        </form>
      </section>
    </main>
  );
}

function serviceToMode(value?: string | null) {
  const v = (value || "ROAD").toUpperCase();
  if (v.includes("AIR")) return "AIR";
  if (v.includes("SEA")) return "SEA";
  if (v.includes("RAIL")) return "RAIL";
  return "ROAD";
}

function Field({
  label,
  value,
  onChange,
  type = "text",
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  type?: string;
}) {
  return (
    <label className="flow-field">
      <span>{label}</span>
      <input
        required
        value={value}
        type={type}
        onChange={(e) => onChange(e.target.value)}
      />
    </label>
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
