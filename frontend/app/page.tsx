"use client";

import Link from "next/link";
import { FormEvent, useState } from "react";
import { useRouter } from "next/navigation";
import Icon from "@/components/Icon";

const services = [
  {
    title: "Air freight",
    copy: "Fast, time-critical cargo across regional and international lanes.",
    icon: "plane" as const,
  },
  {
    title: "Sea freight",
    copy: "FCL, LCL and port-to-port coordination with milestone visibility.",
    icon: "ship" as const,
  },
  {
    title: "Road freight",
    copy: "Reliable road movements with dispatch, delivery and proof of delivery.",
    icon: "truck" as const,
  },
  {
    title: "Multimodal",
    copy: "One coordinated journey across truck, air, sea and rail legs.",
    icon: "globe" as const,
  },
];

export default function Home() {
  const router = useRouter();
  const [trackingRef, setTrackingRef] = useState("");

  function track(event: FormEvent) {
    event.preventDefault();
    const token = trackingRef.trim();
    if (token) router.push(`/track/${encodeURIComponent(token)}`);
  }

  return (
    <main className="public-site">
      <header className="public-site-nav">
        <Link
          href="/"
          className="public-brand public-brand-large"
          aria-label="AAL home"
        >
          <span className="public-brand-mark">
            <Icon name="plane" size={20} />
          </span>
          <span>
            <strong>AAL</strong>
            <small>AFRICA LOGISTIC AVIATION</small>
          </span>
        </Link>
        <nav className="public-site-links" aria-label="Public navigation">
          <Link href="/quote">Quote</Link>
          <Link href="/book">Book</Link>
          <Link href="/track">Track</Link>
        </nav>
        <Link href="/login" className="public-team-link">
          AAL team
        </Link>
      </header>

      <section className="public-hero-v2">
        <div className="public-hero-v2-copy">
          <div className="public-eyebrow">AFRICA LOGISTIC AVIATION</div>
          <h1>Complete control of your shipment.</h1>
          <p>
            Get a freight quote, book your movement and follow every milestone —
            without creating an account.
          </p>
          <div className="public-hero-actions">
            <Link href="/quote" className="public-primary-cta">
              Start with a quote <Icon name="arrow" size={15} />
            </Link>
            <Link href="/book" className="public-secondary-cta">
              Book a shipment
            </Link>
          </div>
          <div className="public-proof-row">
            <span>
              <Icon name="shield" size={14} /> Secure public access
            </span>
            <span>
              <Icon name="globe" size={14} /> Air · Sea · Road
            </span>
            <span>
              <Icon name="chart" size={14} /> Live shipment visibility
            </span>
          </div>
        </div>

        <div className="public-hero-card">
          <div className="public-hero-card-label">TRACK A SHIPMENT</div>
          <h2>Where is your cargo?</h2>
          <p>Enter the tracking token or reference you received from AAL.</p>
          <form onSubmit={track} className="public-track-form">
            <div className="public-track-input">
              <Icon name="search" size={17} />
              <input
                value={trackingRef}
                onChange={(e) => setTrackingRef(e.target.value)}
                placeholder="Tracking reference"
                aria-label="Tracking reference"
              />
            </div>
            <button className="public-primary-cta" type="submit">
              Track <Icon name="arrow" size={14} />
            </button>
          </form>
          <div className="public-hero-card-note">No login required.</div>
        </div>
      </section>

      <section className="public-journey">
        <div className="public-section-head">
          <div>
            <div className="public-eyebrow">ONE SIMPLE JOURNEY</div>
            <h2>From quote to delivery.</h2>
          </div>
          <p>
            Everything customers need is available from the public experience.
            AAL operations remain behind the scenes.
          </p>
        </div>
        <div className="public-journey-grid">
          <Link href="/quote" className="public-journey-card">
            <span>01</span>
            <div className="public-journey-icon">
              <Icon name="file" size={20} />
            </div>
            <h3>Quote</h3>
            <p>Tell AAL your route, cargo and service requirement.</p>
            <strong>
              Request a quote <Icon name="arrow" size={13} />
            </strong>
          </Link>
          <Link href="/book" className="public-journey-card">
            <span>02</span>
            <div className="public-journey-icon">
              <Icon name="ship" size={20} />
            </div>
            <h3>Book</h3>
            <p>Book directly or continue from an accepted quotation.</p>
            <strong>
              Start a booking <Icon name="arrow" size={13} />
            </strong>
          </Link>
          <Link href="/track" className="public-journey-card">
            <span>03</span>
            <div className="public-journey-icon">
              <Icon name="globe" size={20} />
            </div>
            <h3>Track</h3>
            <p>Follow milestones, ETA, carrier and delivery progress.</p>
            <strong>
              Track shipment <Icon name="arrow" size={13} />
            </strong>
          </Link>
        </div>
      </section>

      <section className="public-services">
        <div className="public-section-head">
          <div>
            <div className="public-eyebrow">LOGISTICS CAPABILITIES</div>
            <h2>One platform. Every mode.</h2>
          </div>
          <p>
            Built around the daily realities of African freight: multimodal
            coordination, shipment control and operational visibility.
          </p>
        </div>
        <div className="public-service-grid">
          {services.map((service) => (
            <div className="public-service-card" key={service.title}>
              <div className="public-service-icon">
                <Icon name={service.icon} size={19} />
              </div>
              <h3>{service.title}</h3>
              <p>{service.copy}</p>
            </div>
          ))}
        </div>
      </section>

      <section className="public-final-cta">
        <div>
          <div className="public-eyebrow">READY TO MOVE?</div>
          <h2>Start with the shipment you have today.</h2>
          <p>
            No customer account is required for quoting, booking or tracking.
          </p>
        </div>
        <Link href="/quote" className="public-primary-cta">
          Get a quote <Icon name="arrow" size={14} />
        </Link>
      </section>

      <footer className="public-site-footer">
        <span>© {new Date().getFullYear()} Africa Logistic Aviation</span>
        <span>Quote · Book · Track</span>
        <Link href="/login">AAL team sign in</Link>
      </footer>
    </main>
  );
}
