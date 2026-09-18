"use client";

import Link from "next/link";
import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth-context";
import Icon from "@/components/Icon";

const actions = [
  {
    key: "quote",
    eyebrow: "01",
    title: "Get a quote",
    text: "Enter your route and shipment details and request an AAL freight quotation.",
    href: "/portal/quote",
    icon: "file" as const,
    label: "Start with a quote",
  },
  {
    key: "book",
    eyebrow: "02",
    title: "Book a shipment",
    text: "Turn your freight requirement into a shipment request and continue with AAL operations.",
    href: "/portal/book",
    icon: "ship" as const,
    label: "Start a booking",
  },
  {
    key: "track",
    eyebrow: "03",
    title: "Track a shipment",
    text: "Use your secure tracking reference to see status, milestones and expected arrival information.",
    href: "/track",
    icon: "globe" as const,
    label: "Track shipment",
  },
];

export default function Home() {
  const { accessToken, isLoading } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (accessToken) router.replace("/aal-control-tower");
  }, [accessToken, router]);

  if (isLoading || accessToken) return null;

  return (
    <main className="public-home">
      <nav className="public-nav">
        <Link href="/" className="public-brand" aria-label="AAL home">
          <span className="public-brand-mark"><Icon name="plane" size={20} /></span>
          <span>
            <strong>AAL</strong>
            <small>AFRICA LOGISTIC AVIATION</small>
          </span>
        </Link>

        <div className="public-nav-actions">
          <span className="public-nav-caption">Logistics, connected.</span>
          <Link className="public-signin" href="/login">Sign in</Link>
          <Link className="public-nav-cta" href="/login?next=/portal">Customer workspace</Link>
        </div>
      </nav>

      <section className="public-hero">
        <div className="public-hero-copy">
          <div className="public-eyebrow">AFRICA LOGISTIC AVIATION</div>
          <h1>Move your shipment.<br /><span>Stay in control.</span></h1>
          <p>
            AAL gives you a simple starting point for freight: get a quote,
            book a shipment, or track an existing movement.
          </p>
        </div>

        <div className="public-action-grid" aria-label="AAL customer actions">
          {actions.map((action) => (
            <Link href={action.href} className={`public-action-card ${action.key}`} key={action.key}>
              <div className="public-action-top">
                <span className="public-action-number">{action.eyebrow}</span>
                <span className="public-action-icon"><Icon name={action.icon} size={19} /></span>
              </div>
              <div className="public-action-body">
                <h2>{action.title}</h2>
                <p>{action.text}</p>
              </div>
              <span className="public-action-link">{action.label}<Icon name="arrow" size={14} /></span>
            </Link>
          ))}
        </div>
      </section>

      <section className="public-trust">
        <div>
          <strong>Air · Sea · Road · Multimodal</strong>
          <span>One connected AAL logistics experience.</span>
        </div>
        <div className="public-trust-items">
          <span><Icon name="shield" size={14} /> Secure customer access</span>
          <span><Icon name="globe" size={14} /> Shipment visibility</span>
          <span><Icon name="chart" size={14} /> Commercial control</span>
        </div>
      </section>

      <footer className="public-footer">
        <span>© {new Date().getFullYear()} Africa Logistic Aviation</span>
        <div>
          <Link href="/track">Track shipment</Link>
          <Link href="/login">Customer / staff sign in</Link>
        </div>
      </footer>
    </main>
  );
}
