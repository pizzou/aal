"use client";

import { FormEvent, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import Icon from "@/components/Icon";

export default function TrackPage() {
  const router = useRouter();
  const [reference, setReference] = useState("");

  function submit(event: FormEvent) {
    event.preventDefault();
    const token = reference.trim();
    if (token) router.push(`/track/${encodeURIComponent(token)}`);
  }

  return (
    <main className="public-flow public-track-home">
      <FlowNav />
      <section className="public-center-panel">
        <div className="public-eyebrow">03 / TRACK</div>
        <h1>Track your shipment.</h1>
        <p>Enter the tracking token or shipment reference sent by AAL.</p>
        <form onSubmit={submit} className="public-large-track-form">
          <div className="public-track-input">
            <Icon name="search" size={18} />
            <input
              autoFocus
              value={reference}
              onChange={(e) => setReference(e.target.value)}
              placeholder="e.g. 2a7f... or AAL-2026-00124"
            />
          </div>
          <button className="public-main-button" type="submit">
            Track shipment <Icon name="arrow" size={14} />
          </button>
        </form>
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
