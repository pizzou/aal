"use client";

import { FormEvent, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import Icon from "@/components/Icon";

export default function PublicTrackPage() {
  const router = useRouter();
  const [reference, setReference] = useState("");

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const token = reference.trim();
    if (!token) return;
    router.push(`/track/${encodeURIComponent(token)}`);
  }

  return (
    <main className="public-utility-page">
      <nav className="public-nav">
        <Link href="/" className="public-brand">
          <span className="public-brand-mark"><Icon name="plane" size={20} /></span>
          <span><strong>AAL</strong><small>AFRICA LOGISTIC AVIATION</small></span>
        </Link>
        <Link className="public-signin" href="/login">Sign in</Link>
      </nav>

      <section className="public-track-card">
        <div className="public-eyebrow">SHIPMENT VISIBILITY</div>
        <h1>Track your shipment</h1>
        <p>Enter the secure shipment tracking reference provided by AAL.</p>
        <form onSubmit={submit} className="public-track-form">
          <label htmlFor="tracking-reference">Tracking reference</label>
          <div className="public-track-input">
            <Icon name="search" size={17} />
            <input
              id="tracking-reference"
              value={reference}
              onChange={(event) => setReference(event.target.value)}
              placeholder="Enter tracking reference or token"
              autoComplete="off"
              required
            />
          </div>
          <button className="public-main-button" type="submit">
            Track shipment <Icon name="arrow" size={14} />
          </button>
        </form>
        <Link href="/" className="public-back">Back to AAL</Link>
      </section>
    </main>
  );
}
