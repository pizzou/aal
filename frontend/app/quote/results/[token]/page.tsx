"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import {
  ApiError,
  PublicQuoteOption,
  PublicQuoteRequestResponse,
  publicCommercialApi,
} from "@/lib/api-client";
import Icon from "@/components/Icon";

export default function PublicQuoteResultsPage() {
  const params = useParams<{ token: string }>();
  const token = params?.token || "";
  const [data, setData] = useState<PublicQuoteRequestResponse | null>(null);
  const [selected, setSelected] = useState<PublicQuoteOption | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!token) return;
    publicCommercialApi
      .quoteRequest(token)
      .then((response) => {
        setData(response);
        setSelected(response.options[0] ?? null);
      })
      .catch((e: unknown) =>
        setError(
          e instanceof ApiError ? e.message : "Quote request is unavailable.",
        ),
      );
  }, [token]);

  if (error) {
    return (
      <main className="kn-public-shell">
        <PublicHeader />
        <section className="flow-success">
          <div className="public-eyebrow">AAL QUOTE</div>
          <h1>Quote unavailable</h1>
          <p>{error}</p>
          <Link className="public-main-button" href="/quote">
            Request a new quote <Icon name="arrow" size={14} />
          </Link>
        </section>
      </main>
    );
  }
  if (!data) {
    return (
      <main className="kn-public-shell">
        <PublicHeader />
        <section className="flow-success">
          <div className="public-eyebrow">AAL QUOTE</div>
          <h1>Loading your quote…</h1>
          <p>We are retrieving the latest quote options.</p>
        </section>
      </main>
    );
  }

  const mode = selected?.mode || data.options[0]?.mode || "AIR";

  return (
    <main className="kn-public-shell">
      <PublicHeader />
      <section className="kn-page-frame">
        <div className="kn-breadcrumb">
          Home <span>›</span> Quote <span>›</span> Results
        </div>
        <div className="kn-stepper" aria-label="Quote steps">
          <Step number="1" label="Quote request" complete />
          <span className="kn-step-arrow">→</span>
          <Step number="2" label="Quote results" active />
          <span className="kn-step-arrow">→</span>
          <Step number="3" label="Quote details" />
          <span className="kn-step-arrow">→</span>
          <Step number="4" label="Booking" />
        </div>

        <header className="kn-page-head">
          <div>
            <div className="kn-eyebrow">QUOTE RESULTS</div>
            <h1>Available freight options.</h1>
            <p>
              Reference {data.quoteReference} · valid until {data.validUntil}.
              Select the option that fits your shipment.
            </p>
          </div>
        </header>

        <section className="kn-results-layout">
          <div>
            <div className="kn-results-grid">
              {data.options.map((option) => (
                <button
                  type="button"
                  key={option.mode}
                  className={`kn-result-card ${selected?.mode === option.mode ? "selected" : ""}`}
                  onClick={() => setSelected(option)}
                >
                  <div className="kn-result-top">
                    <span className="kn-result-mode">{option.modeLabel}</span>
                    <span className="kn-rate-tag">{option.rateType}</span>
                  </div>
                  <div className="kn-result-price">
                    {option.currency}{" "}
                    {Number(option.totalCharge || 0).toLocaleString(undefined, {
                      minimumFractionDigits: 2,
                      maximumFractionDigits: 2,
                    })}
                  </div>
                  <div className="kn-result-lines">
                    <span>
                      Base charge{" "}
                      <strong>
                        {Number(option.baseCharge || 0).toLocaleString()}
                      </strong>
                    </span>
                    <span>
                      Fuel / surcharge{" "}
                      <strong>
                        {Number(option.fuelSurcharge || 0).toLocaleString()}
                      </strong>
                    </span>
                  </div>
                  <div className="kn-result-choice">
                    {selected?.mode === option.mode ? "Selected" : "Select"}
                    <Icon name="arrow" size={13} />
                  </div>
                </button>
              ))}
            </div>
            {!data.options.length && (
              <div className="kn-empty">
                No instant rate is available for this lane yet. AAL has received
                the request and the commercial team will send the formal
                quotation to {data.email}.
              </div>
            )}
          </div>

          <aside className="kn-summary-card">
            <div className="kn-eyebrow">SHIPMENT SUMMARY</div>
            <h3>
              {data.origin} → {data.destination}
            </h3>
            <div className="kn-summary-line">
              <span>Customer</span>
              <strong>{data.company || data.contactName}</strong>
            </div>
            <div className="kn-summary-line">
              <span>Contact</span>
              <strong>{data.email}</strong>
            </div>
            <div className="kn-summary-line">
              <span>Commodity</span>
              <strong>{data.commodity || "General cargo"}</strong>
            </div>
            <div className="kn-summary-line">
              <span>Packages</span>
              <strong>{data.packages ?? "—"}</strong>
            </div>
            <div className="kn-summary-line">
              <span>Volume</span>
              <strong>
                {data.volumeCbm == null ? "—" : `${data.volumeCbm} m³`}
              </strong>
            </div>
            <Link
              className="kn-primary-button"
              href={`/book?requestToken=${encodeURIComponent(token)}&mode=${encodeURIComponent(mode)}`}
            >
              Book selected option <Icon name="arrow" size={14} />
            </Link>
            <Link className="kn-text-button" href="/quote">
              Start another quote
            </Link>
          </aside>
        </section>
      </section>
    </main>
  );
}

function PublicHeader() {
  return (
    <header className="kn-header">
      <Link href="/" className="kn-brand">
        <span className="kn-brand-mark kn-brand-image">
          <img
            src="/branding/aal-logo.jpg"
            alt="Aviation Africa Logistics Ltd"
          />
        </span>
        <span>
          <strong>AVIATION AFRICA</strong>
          <small>LOGISTICS LTD · GLOBAL REACH · AFRICAN ROOTS</small>
        </span>
      </Link>
      <div className="kn-header-search">
        <Icon name="search" size={17} />
        <span>Search by tracking reference</span>
        <Link href="/track">Track</Link>
      </div>
      <div className="kn-header-actions">
        <Link href="/book">Book</Link>
        <Link href="/login" className="kn-login-button">
          AAL team
        </Link>
      </div>
    </header>
  );
}
function Step({
  number,
  label,
  active = false,
  complete = false,
}: {
  number: string;
  label: string;
  active?: boolean;
  complete?: boolean;
}) {
  return (
    <span
      className={`kn-step ${active ? "active" : ""} ${complete ? "complete" : ""}`}
    >
      <b>{number}</b>
      {label}
    </span>
  );
}
