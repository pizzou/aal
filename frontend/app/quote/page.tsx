"use client";

import { FormEvent, useState } from "react";
import Link from "next/link";
import { ApiError, publicCommercialApi } from "@/lib/api-client";
import Icon from "@/components/Icon";

const modes = [
  ["AIR", "Air freight", "plane"],
  ["SEA", "Sea freight", "ship"],
  ["ROAD", "Road freight", "truck"],
] as const;

export default function QuotePage() {
  const [mode, setMode] = useState("AIR");
  const [form, setForm] = useState({
    origin: "Kigali, Rwanda",
    destination: "",
    weight: "",
    volume: "",
    packages: "",
    commodity: "",
    company: "",
    contactName: "",
    email: "",
    phone: "",
    notes: "",
  });
  const [sent, setSent] = useState(false);
  const [quoteId, setQuoteId] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  const update = (key: keyof typeof form, value: string) =>
    setForm((current) => ({ ...current, [key]: value }));

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError("");
    try {
      const response = await publicCommercialApi.requestQuote({
        origin: form.origin,
        destination: form.destination,
        serviceType: mode,
        commodity: form.commodity,
        chargeableWeightKg: Number(form.weight) || 0,
        volumeCbm: Number(form.volume) || 0,
        packages: Number(form.packages) || 0,
        company: form.company,
        contactName: form.contactName,
        email: form.email,
        phone: form.phone,
        notes: form.notes,
      });
      setQuoteId(response.quoteId);
      setSent(true);
    } catch (errorValue) {
      setError(
        errorValue instanceof ApiError
          ? errorValue.message
          : "Unable to submit quote request.",
      );
    } finally {
      setBusy(false);
    }
  }

  if (sent) {
    return (
      <main className="public-flow">
        <FlowNav />
        <section className="flow-success">
          <div className="flow-success-icon">
            <Icon name="shield" size={30} />
          </div>
          <div className="public-eyebrow">REQUEST RECEIVED</div>
          <h1>Your freight request is with AAL.</h1>
          <p>
            Reference <strong>{quoteId}</strong>. We sent a confirmation to{" "}
            <strong>{form.email}</strong>. When the quotation is prepared, AAL
            will email you a secure link where you can review, accept and book
            it — no account required.
          </p>
          <div className="flow-success-actions">
            <Link className="public-main-button" href="/track">
              Track a shipment <Icon name="arrow" size={14} />
            </Link>
            <Link className="flow-secondary" href="/">
              Return to AAL
            </Link>
          </div>
        </section>
      </main>
    );
  }

  return (
    <main className="public-flow">
      <FlowNav />
      <section className="quote-request-shell">
        <div className="quote-request-head">
          <div>
            <div className="public-eyebrow">01 / QUOTE</div>
            <h1>Start your freight quote.</h1>
            <p>
              Tell AAL what needs to move. No account is required and your
              quotation will be delivered to your email.
            </p>
          </div>
          <div className="quote-stepper">
            <span className="active">1 Details</span>
            <span>2 AAL pricing</span>
            <span>3 Review & book</span>
          </div>
        </div>

        <form className="quote-request-form" onSubmit={submit}>
          <section className="quote-form-section">
            <div className="quote-form-title">
              <span>SHIPMENT</span>
              <strong>What are you moving?</strong>
            </div>
            <div className="flow-mode quote-mode-grid">
              {modes.map(([value, label, icon]) => (
                <button
                  type="button"
                  key={value}
                  className={mode === value ? "selected" : ""}
                  onClick={() => setMode(value)}
                >
                  <Icon name={icon} size={18} />
                  <span>{label}</span>
                </button>
              ))}
            </div>
            <div className="flow-grid">
              <Field
                label="Origin"
                value={form.origin}
                onChange={(value) => update("origin", value)}
              />
              <Field
                label="Destination"
                value={form.destination}
                onChange={(value) => update("destination", value)}
              />
              <Field
                label="Weight (kg)"
                value={form.weight}
                onChange={(value) => update("weight", value)}
                type="number"
              />
              <Field
                label="Volume (CBM)"
                value={form.volume}
                onChange={(value) => update("volume", value)}
                type="number"
              />
              <Field
                label="Packages"
                value={form.packages}
                onChange={(value) => update("packages", value)}
                type="number"
              />
              <Field
                label="Commodity"
                value={form.commodity}
                onChange={(value) => update("commodity", value)}
              />
            </div>
          </section>

          <section className="quote-form-section">
            <div className="quote-form-title">
              <span>CONTACT</span>
              <strong>Where should we send your quotation?</strong>
            </div>
            <div className="flow-grid">
              <Field
                label="Company"
                value={form.company}
                onChange={(value) => update("company", value)}
              />
              <Field
                label="Contact name"
                value={form.contactName}
                onChange={(value) => update("contactName", value)}
              />
              <Field
                label="Email"
                value={form.email}
                onChange={(value) => update("email", value)}
                type="email"
              />
              <Field
                label="Phone"
                value={form.phone}
                onChange={(value) => update("phone", value)}
              />
            </div>
            <label className="flow-textarea">
              <span>Additional requirements</span>
              <textarea
                value={form.notes}
                onChange={(e) => update("notes", e.target.value)}
                rows={4}
                placeholder="Incoterms, delivery requirements, special handling, preferred departure, etc."
              />
            </label>
          </section>

          {error && <div className="alert alert-error">{error}</div>}
          <div className="quote-form-submit">
            <div>
              <strong>Secure customer delivery</strong>
              <small>
                AAL will use your email to send the quotation and secure review
                link.
              </small>
            </div>
            <button className="public-main-button" disabled={busy}>
              {busy ? "Sending request…" : "Request AAL quote"}
              <Icon name="arrow" size={14} />
            </button>
          </div>
        </form>
      </section>
    </main>
  );
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
