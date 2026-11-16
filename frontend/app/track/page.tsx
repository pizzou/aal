"use client";

import { FormEvent, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import Icon from "@/components/Icon";
import { normalizeTrackingInput } from "@/lib/tracking-reference";

export default function TrackPage() {
  const router = useRouter();
  const [reference, setReference] = useState("");

  function submit(event: FormEvent) {
    event.preventDefault();
    const token = normalizeTrackingInput(reference);
    if (token) router.push(`/track/${encodeURIComponent(token)}`);
  }

  return (
    <main className="public-flow public-track-home">
      <FlowNav />
      <section className="public-center-panel">
        <div className="public-eyebrow">03 / TRACK</div>
        <h1>Track your shipment.</h1>
        <p>
          Enter the secure AAL tracking number sent with your booking, or paste
          your tracking link.
        </p>
        <form onSubmit={submit} className="public-large-track-form">
          <div className="public-track-input">
            <Icon name="search" size={18} />
            <input
              autoFocus
              value={reference}
              onChange={(e) => setReference(e.target.value)}
              placeholder="AAL-TRK-… or your tracking link"
            />
          </div>
          <button className="public-main-button" type="submit">
            Track shipment <Icon name="arrow" size={14} />
          </button>
        </form>
        <p className="card-muted">
          Your shipment number (AAL-SHP-…) identifies the job internally; the
          secure AAL-TRK number is required for public tracking.
        </p>
        <div className="flow-benefits">
          <span>
            <Icon name="shield" size={15} /> No login required
          </span>
          <span>
            <Icon name="globe" size={15} /> Milestones & ETA
          </span>
          <span>
            <Icon name="file" size={15} /> Public documents
          </span>
        </div>
      </section>
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
