"use client";

import Link from "next/link";
import { FormEvent, useState } from "react";
import { useRouter } from "next/navigation";
import Icon from "@/components/Icon";

const cards = [
  {
    href: "/quote",
    title: "Quote",
    text: "Enter your route and shipment details. Compare available air, sea and road options and request a formal quotation.",
    action: "Start with a quote",
    icon: "chart" as const,
  },
  {
    href: "/book",
    title: "Book",
    text: "Turn your quotation or quote request into a booking without creating a customer account.",
    action: "Start with a booking",
    icon: "ship" as const,
  },
  {
    href: "/track",
    title: "Track",
    text: "Search your tracking reference and follow shipment status, milestones, ETA and customer-visible documents.",
    action: "Track a shipment",
    icon: "search" as const,
  },
];

export default function Home() {
  const router = useRouter();
  const [reference, setReference] = useState("");

  function submitTrack(event: FormEvent) {
    event.preventDefault();
    const value = reference.trim();
    if (value) router.push(`/track/${encodeURIComponent(value)}`);
  }

  return (
    <main className="kn-public-shell kn-public-home">
      <header className="kn-header">
        <Link href="/" className="kn-brand" aria-label="AAL home">
          <span className="kn-brand-mark">
            <Icon name="plane" size={20} />
          </span>
          <span>
            <strong>AAL</strong>
            <small>AVIATION AFRICA LOGISTIC </small>
          </span>
        </Link>

        <form
          className="kn-header-search kn-header-track"
          onSubmit={submitTrack}
        >
          <Icon name="search" size={17} />
          <input
            aria-label="Search by tracking reference"
            placeholder="Search by tracking reference"
            value={reference}
            onChange={(e) => setReference(e.target.value)}
          />
          <button type="submit" aria-label="Track shipment">
            <Icon name="arrow" size={15} />
          </button>
        </form>

        <div className="kn-header-actions">
          <Link href="/login" className="kn-login-button">
            AAL team
          </Link>
        </div>
      </header>

      <div className="kn-hero-banner">
        <div className="kn-hero-art" aria-hidden="true">
          <span className="kn-hero-orb one" />
          <span className="kn-hero-orb two" />
          <span className="kn-hero-route route-a" />
          <span className="kn-hero-route route-b" />
          <div className="kn-hero-ship">
            <Icon name="ship" size={52} />
          </div>
          <div className="kn-hero-plane">
            <Icon name="plane" size={38} />
          </div>
        </div>
        <div className="kn-hero-copy">
          <div className="kn-eyebrow kn-light">AAL LOGISTICS</div>
          <h1>Quote, book and track your freight.</h1>
          <p>
            Simple public access for customers. No account is required to start
            a quote, submit a booking or track a shipment.
          </p>
          <div className="kn-hero-actions">
            <Link href="/quote" className="kn-primary-button">
              Start with a quote <Icon name="arrow" size={14} />
            </Link>
            <Link href="/book" className="kn-hero-secondary">
              Start with a booking
            </Link>
          </div>
        </div>
      </div>

      <section className="kn-main-content">
        <div className="kn-three-cards">
          {cards.map((card, index) => (
            <Link href={card.href} className="kn-action-card" key={card.title}>
              <div className="kn-action-icon">
                <Icon name={card.icon} size={22} />
              </div>
              <div className="kn-action-number">0{index + 1}</div>
              <h2>{card.title}</h2>
              <p>{card.text}</p>
              <span className="kn-card-link">
                {card.action} <Icon name="arrow" size={14} />
              </span>
            </Link>
          ))}
        </div>

        <section className="kn-public-capabilities">
          <div>
            <div className="kn-eyebrow">ONE PUBLIC EXPERIENCE</div>
            <h2>Designed around the three things customers need most.</h2>
          </div>
          <div className="kn-capability-list">
            <div>
              <Icon name="shield" size={18} />
              <span>
                <strong>Secure</strong>
                <small>
                  Token-based quote and tracking links with no customer
                  dashboard exposed.
                </small>
              </span>
            </div>
            <div>
              <Icon name="globe" size={18} />
              <span>
                <strong>Multimodal</strong>
                <small>
                  Air, sea, road and unified shipment visibility behind one AAL
                  workflow.
                </small>
              </span>
            </div>
            <div>
              <Icon name="chart" size={18} />
              <span>
                <strong>Operationally connected</strong>
                <small>
                  Your public action becomes an internal AAL quotation, shipment
                  and finance record.
                </small>
              </span>
            </div>
          </div>
        </section>
      </section>

      <footer className="kn-public-footer">
        <span>© {new Date().getFullYear()} Aviation Africa Logistic </span>
        <span>Quote · Book · Track</span>
        <Link href="/login">AAL team sign in</Link>
      </footer>
    </main>
  );
}
