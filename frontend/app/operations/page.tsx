"use client";

import Link from "next/link";
import { useAuth } from "@/lib/auth-context";

type Destination = { title: string; detail: string; href: string; roles: string[] };
const destinations: Destination[] = [
  { title: "Air cargo & AWBs", detail: "Bookings, air waybills and airline workflows", href: "/air-cargo", roles: ["ADMIN", "MANAGER", "OPERATIONS", "AIR_CARGO"] },
  { title: "Road dispatch & fleet", detail: "Assign trips, transport and drivers", href: "/trips", roles: ["ADMIN", "MANAGER", "OPERATIONS", "DISPATCH"] },
  { title: "Warehouses & inventory", detail: "Receiving, storage and inventory movements", href: "/warehouses", roles: ["ADMIN", "MANAGER", "OPERATIONS", "WAREHOUSE"] },
  { title: "Multimodal & ocean", detail: "Transport legs and sea freight registers", href: "/multimodal", roles: ["ADMIN", "MANAGER", "OPERATIONS", "AIR_CARGO", "DISPATCH", "WAREHOUSE"] },
  { title: "Customs & documents", detail: "Shipment documents and customs evidence", href: "/documents", roles: ["ADMIN", "MANAGER", "OPERATIONS", "AIR_CARGO"] },
  { title: "Exceptions & follow-up", detail: "Delayed or blocked shipments and next actions", href: "/exceptions", roles: ["ADMIN", "MANAGER", "OPERATIONS"] },
  { title: "Mobile operations", detail: "Driver and warehouse mobile workflows", href: "/mobile-ops", roles: ["ADMIN", "MANAGER", "OPERATIONS", "DISPATCH", "WAREHOUSE"] },
];

export default function OperationsWorkspace() {
  const { role } = useAuth();
  const allowed = destinations.filter(destination => destination.roles.includes(role || ""));
  return <main className="page">
    <div className="page-head"><div>
      <div className="eyebrow">AAL / DAILY WORK</div>
      <h1 className="page-title">Operations</h1>
      <p className="page-subtitle">Choose the activity you are working on. Specialist tools stay grouped here, not scattered across the main menu.</p>
    </div></div>
    <section className="form-panel" aria-label="Operational activities">
      <div className="section-heading"><div className="section-number">01</div>
        <div><h2>What would you like to do?</h2><p>Only workflows available to your role are shown.</p></div>
      </div>
      <div className="form-grid">
        {allowed.map(destination => <Link key={destination.href} href={destination.href}
          className="form-panel" style={{display:"block", textDecoration:"none", padding:20}}>
            <strong>{destination.title} →</strong>
            <p className="page-subtitle" style={{marginTop:8}}>{destination.detail}</p>
          </Link>)}
      </div>
      {!allowed.length && <p>Your account does not have access to operations. Contact an administrator.</p>}
    </section>
  </main>;
}
