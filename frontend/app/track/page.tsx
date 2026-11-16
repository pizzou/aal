"use client";

import { FormEvent, useRef, useState, useTransition } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import Icon from "@/components/Icon";
import { normalizeTrackingInput } from "@/lib/tracking-reference";

export default function TrackPage() {
  const router = useRouter();
  const inputRef = useRef<HTMLInputElement>(null);

  const [reference, setReference] = useState("");
  const [error, setError] = useState("");
  const [isPending, startTransition] = useTransition();

  const canSubmit = reference.trim().length > 0 && !isPending;

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();

    const value = reference.trim();

    if (!value) {
      setError("Please enter your shipment tracking number.");
      inputRef.current?.focus();
      return;
    }

    if (/^AAL-SHP-/i.test(value)) {
      setError(
        "This is an internal shipment reference. Please use your secure AAL-TRK tracking number.",
      );
      inputRef.current?.focus();
      return;
    }

    const token = normalizeTrackingInput(value);

    if (!token) {
      setError(
        "We could not recognize that tracking number. Check your AAL-TRK number or paste the complete tracking link.",
      );
      inputRef.current?.focus();
      return;
    }

    setError("");

    startTransition(() => {
      router.push(`/track/${encodeURIComponent(token)}`);
    });
  }

  function clearInput() {
    setReference("");
    setError("");
    inputRef.current?.focus();
  }

  return (
    <main className="public-flow public-track-home track-page">
      <FlowNav />

      <section className="public-center-panel track-panel">
        <div className="track-header">
          <div className="track-icon">
            <Icon name="search" size={25} />
          </div>

          <div className="public-eyebrow track-eyebrow">
            SHIPMENT VISIBILITY
          </div>

          <h1>Track your shipment</h1>

          <p className="track-description">
            Get the latest information about your cargo, including shipment
            progress, locations, milestones and delivery updates.
          </p>
        </div>

        <form
          onSubmit={submit}
          className="public-large-track-form track-form"
          noValidate
        >
          <label htmlFor="tracking-reference" className="track-label">
            Tracking Number
          </label>

          <p className="track-field-description" id="tracking-help">
            Enter your secure AAL tracking number or paste the tracking link
            from your booking confirmation.
          </p>

          <div className="track-form-row">
            <div
              className={`public-track-input track-field ${
                error ? "track-field-error" : ""
              }`}
            >
              <span className="track-search-icon" aria-hidden="true">
                <Icon name="search" size={21} />
              </span>

              <input
                ref={inputRef}
                id="tracking-reference"
                name="tracking-reference"
                type="text"
                inputMode="text"
                autoComplete="off"
                autoCapitalize="none"
                spellCheck={false}
                value={reference}
                onChange={(event) => {
                  setReference(event.target.value);
                  if (error) setError("");
                }}
                placeholder="Enter your AAL-TRK number"
                aria-invalid={Boolean(error)}
                aria-describedby={
                  error ? "tracking-help tracking-error" : "tracking-help"
                }
              />

              {reference.length > 0 && (
                <button
                  type="button"
                  className="track-clear"
                  onClick={clearInput}
                  aria-label="Clear tracking number"
                >
                  Clear
                </button>
              )}
            </div>

            <button
              className="public-main-button track-submit"
              type="submit"
              disabled={!canSubmit}
            >
              <span>
                {isPending ? "Opening tracking..." : "Track Shipment"}
              </span>

              {!isPending && <Icon name="arrow" size={17} />}
            </button>
          </div>

          {error && (
            <p id="tracking-error" className="track-error" role="alert">
              {error}
            </p>
          )}
        </form>

        <div className="track-info">
          <span className="track-info-icon">
            <Icon name="shield" size={19} />
          </span>

          <div>
            <strong>Where can I find my tracking number?</strong>

            <p>
              Your secure tracking number begins with <strong>AAL-TRK</strong>{" "}
              and is provided with your shipment confirmation or tracking link.
            </p>

            <p>
              <strong>AAL-SHP</strong> is an internal shipment reference and
              cannot be used for public tracking.
            </p>
          </div>
        </div>

        <div className="flow-benefits track-benefits">
          <span>
            <Icon name="shield" size={16} />
            No login required
          </span>

          <span>
            <Icon name="globe" size={16} />
            Shipment milestones &amp; ETA
          </span>

          <span>
            <Icon name="file" size={16} />
            Available public documents
          </span>
        </div>
      </section>

      <style jsx>{`
        .track-page {
          min-height: 100vh;
          background:
            radial-gradient(
              circle at 50% 8%,
              rgba(36, 111, 224, 0.08),
              transparent 45%
            ),
            #f5f8fc;
        }

        .track-panel {
          width: min(100% - 32px, 850px);
          margin: 54px auto 80px;
          padding: clamp(28px, 5vw, 55px);
          background: #ffffff;
          border: 1px solid #e0e8f3;
          border-radius: 26px;
          box-shadow: 0 18px 55px rgba(24, 51, 92, 0.07);
        }

        .track-header {
          text-align: center;
          margin-bottom: 38px;
        }

        .track-icon {
          width: 64px;
          height: 64px;
          margin: 0 auto 20px;
          display: flex;
          align-items: center;
          justify-content: center;
          color: #1565d8;
          background: #eaf2ff;
          border-radius: 20px;
        }

        .track-eyebrow {
          color: #326bad;
          font-size: 11px;
          font-weight: 800;
          letter-spacing: 0.16em;
          margin-bottom: 13px;
        }

        .track-header h1 {
          margin: 0 0 14px;
          color: #132b49;
          font-size: clamp(30px, 4.2vw, 43px);
          font-weight: 800;
          letter-spacing: -0.035em;
          line-height: 1.15;
        }

        .track-description {
          max-width: 610px;
          margin: 0 auto;
          color: #61738d;
          font-size: 15px;
          line-height: 1.8;
        }

        .track-form {
          display: block;
        }

        .track-label {
          display: block;
          margin-bottom: 7px;
          color: #172c47;
          font-size: 15px;
          font-weight: 750;
        }

        .track-field-description {
          margin: 0 0 15px;
          color: #667892;
          font-size: 13px;
          line-height: 1.6;
        }

        .track-form-row {
          display: grid;
          grid-template-columns: minmax(0, 1fr) auto;
          align-items: stretch;
          gap: 12px;
        }

        .track-field {
          display: flex;
          align-items: center;
          min-width: 0;
          min-height: 62px;
          padding: 0 15px;
          background: #ffffff;
          border: 2px solid #ccd9ea;
          border-radius: 14px;
          transition:
            border-color 0.2s ease,
            box-shadow 0.2s ease;
        }

        .track-field:focus-within {
          border-color: #1565d8;
          box-shadow: 0 0 0 4px rgba(21, 101, 216, 0.11);
        }

        .track-field-error {
          border-color: #cf4751;
        }

        .track-search-icon {
          display: inline-flex;
          align-items: center;
          flex-shrink: 0;
          margin-right: 12px;
          color: #6482a9;
        }

        .track-field input {
          flex: 1;
          min-width: 0;
          height: 56px;
          padding: 0;
          color: #152d4b;
          background: transparent;
          border: 0;
          outline: 0;
          box-shadow: none;
          font-family: inherit;
          font-size: 16px;
          font-weight: 600;
        }

        .track-field input::placeholder {
          color: #91a0b4;
          font-weight: 400;
        }

        .track-clear {
          flex-shrink: 0;
          margin-left: 8px;
          padding: 8px 5px;
          border: 0;
          background: transparent;
          color: #51739f;
          font-family: inherit;
          font-size: 12px;
          font-weight: 700;
          cursor: pointer;
        }

        .track-clear:hover {
          color: #1459bd;
        }

        .track-submit {
          display: inline-flex;
          align-items: center;
          justify-content: center;
          gap: 13px;
          min-height: 62px;
          padding: 0 26px;
          white-space: nowrap;
          background: #155fce;
          color: #ffffff;
          border: 1px solid #155fce;
          border-radius: 14px;
          font-family: inherit;
          font-size: 15px;
          font-weight: 750;
          cursor: pointer;
          transition:
            background 0.2s ease,
            transform 0.2s ease;
        }

        .track-submit:hover:not(:disabled) {
          background: #104ea9;
          transform: translateY(-1px);
        }

        .track-submit:disabled {
          opacity: 0.58;
          cursor: not-allowed;
          transform: none;
        }

        .track-submit:focus-visible,
        .track-clear:focus-visible {
          outline: 3px solid #8eb9ff;
          outline-offset: 3px;
        }

        .track-error {
          margin: 12px 0 0;
          padding: 12px 14px;
          border-radius: 10px;
          background: #fff0f0;
          color: #b12839;
          font-size: 13px;
          line-height: 1.6;
        }

        .track-info {
          display: flex;
          align-items: flex-start;
          gap: 14px;
          margin-top: 32px;
          padding: 20px;
          background: #f3f7fd;
          border: 1px solid #e0e9f7;
          border-radius: 16px;
        }

        .track-info-icon {
          display: inline-flex;
          align-items: center;
          justify-content: center;
          flex: 0 0 37px;
          height: 37px;
          border-radius: 11px;
          background: #e1edff;
          color: #2267c2;
        }

        .track-info strong {
          color: #244363;
          font-weight: 750;
        }

        .track-info > div > strong {
          display: block;
          margin: 1px 0 9px;
          font-size: 14px;
        }

        .track-info p {
          margin: 5px 0 0;
          color: #60738d;
          font-size: 13px;
          line-height: 1.7;
        }

        .track-benefits {
          display: flex;
          flex-wrap: wrap;
          align-items: center;
          justify-content: center;
          gap: 14px 24px;
          margin-top: 28px;
          padding-top: 24px;
          border-top: 1px solid #edf1f7;
        }

        .track-benefits span {
          display: inline-flex;
          align-items: center;
          gap: 7px;
          color: #62758f;
          font-size: 12px;
          font-weight: 600;
        }

        @media (max-width: 680px) {
          .track-panel {
            margin-top: 25px;
            margin-bottom: 40px;
            padding: 28px 20px;
            border-radius: 20px;
          }

          .track-header {
            margin-bottom: 30px;
          }

          .track-form-row {
            grid-template-columns: minmax(0, 1fr);
          }

          .track-submit {
            width: 100%;
          }

          .track-info {
            padding: 16px;
          }

          .track-benefits {
            justify-content: flex-start;
          }
        }

        @media (prefers-reduced-motion: reduce) {
          .track-field,
          .track-submit {
            transition: none;
          }
        }
      `}</style>
    </main>
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
          <small>AVIATION AFRICA LOGISTICS</small>
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
