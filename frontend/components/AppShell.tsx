"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth-context";
import { shipmentsApi, Shipment } from "@/lib/api-client";
import Icon, { IconName } from "./Icon";
import { useEffect, useMemo, useState } from "react";

type NavItem = { label: string; href: string; icon: IconName };

const portalNav: NavItem[] = [
  { label: "Home", href: "/portal", icon: "grid" },
  { label: "Get a quote", href: "/portal/quote", icon: "plus" },
  { label: "Book shipment", href: "/portal/book", icon: "ship" },
  { label: "Track shipments", href: "/portal/shipments", icon: "globe" },
  { label: "Documents", href: "/portal/documents", icon: "file" },
  { label: "Invoices", href: "/portal/invoices", icon: "money" },
  { label: "Analytics", href: "/portal/analytics", icon: "chart" },
];

const internalNav: Array<NavItem & { section: string; roles?: string[] }> = [
  { section: "CONTROL TOWER", label: "AAL Operations & Profitability", href: "/aal-control-tower", icon: "grid", roles: ["ADMIN","MANAGER","OPERATIONS","SALES","FINANCE"] },
    { section: "CONTROL TOWER", label: "Shipment Register", href: "/shipments", icon: "ship", roles: ["ADMIN","MANAGER","OPERATIONS","SALES","FINANCE"] },
    { section: "OPERATIONS", label: "Air Cargo", href: "/air-cargo", icon: "plane", roles: ["ADMIN","MANAGER","OPERATIONS","AIR_CARGO"] },
  { section: "OPERATIONS", label: "Dispatch & Fleet", href: "/trips", icon: "truck", roles: ["ADMIN","MANAGER","OPERATIONS","DISPATCH"] },
  { section: "OPERATIONS", label: "Warehouse Control", href: "/warehouses", icon: "warehouse", roles: ["ADMIN","MANAGER","OPERATIONS","WAREHOUSE"] },
  { section: "OPERATIONS", label: "Exceptions", href: "/exceptions", icon: "bell", roles: ["ADMIN","MANAGER","OPERATIONS"] },
  { section: "OPERATIONS", label: "Documents & Customs", href: "/documents", icon: "file", roles: ["ADMIN","MANAGER","OPERATIONS","AIR_CARGO"] },
  { section: "COMMERCIAL & FINANCE", label: "Customers", href: "/customers", icon: "chart", roles: ["ADMIN","MANAGER","SALES"] },
  { section: "COMMERCIAL & FINANCE", label: "Sales & Quotations", href: "/commercial", icon: "chart", roles: ["ADMIN","MANAGER","SALES"] },
  { section: "COMMERCIAL & FINANCE", label: "Billing & Receivables", href: "/billing", icon: "money", roles: ["ADMIN","MANAGER","FINANCE"] },
  { section: "DATA & PLATFORM", label: "Legacy Data Migration", href: "/command-center", icon: "file", roles: ["ADMIN","MANAGER","OPERATIONS"] },
  { section: "DATA & PLATFORM", label: "Audit Trail", href: "/audit", icon: "shield", roles: ["ADMIN","MANAGER"] },
  { section: "DATA & PLATFORM", label: "Settings", href: "/settings", icon: "shield", roles: ["ADMIN","MANAGER","FINANCE","OPERATIONS"] },
  { section: "DATA & PLATFORM", label: "Multimodal Mode Control", href: "/multimodal", icon: "globe", roles: ["ADMIN","MANAGER","OPERATIONS","AIR_CARGO"] },
  { section: "ADMINISTRATION", label: "Users & Access", href: "/users", icon: "shield", roles: ["ADMIN"] },
];

export default function AppShell({ children }: { children: React.ReactNode }) {
  const path = usePathname();
  const router = useRouter();
  const { logout, isLoading, accessToken, role } = useAuth();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [shipments, setShipments] = useState<Shipment[]>([]);

  const bare = path === "/login" || path.startsWith("/track/") || path.startsWith("/customer-portal/") || path === "/";
  const portal = path === "/portal" || path.startsWith("/portal/");

  useEffect(() => {
    if (!accessToken) return;
    shipmentsApi.list().then(r => setShipments(r.content)).catch(() => undefined);
  }, [accessToken]);

  const results = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return [];
    return shipments.filter(s => [s.referenceCode,s.clientName,s.carrierReferenceNumber,s.invoiceNo,s.commodity,s.originCityPort,s.destinationCityPort,s.airlineUsed].filter(Boolean).join(" ").toLowerCase().includes(q)).slice(0, 7);
  }, [query, shipments]);

  if (bare) return <>{children}</>;
  if (isLoading) return <div className="app-loading"><div className="loading-mark">A</div><div><strong>Africa Logistic Aviation</strong><span>Loading secure workspace…</span></div></div>;
  if (!accessToken) return <>{children}</>;

  async function doLogout() { await logout(); router.push("/login"); }

  if (portal) {
    return <CustomerShell path={path} open={open} setOpen={setOpen} query={query} setQuery={setQuery} results={results} doLogout={doLogout}>{children}</CustomerShell>;
  }

  const visible = internalNav.filter(i => !i.roles || (role && i.roles.includes(role)));
  let last = "";
  return (
    <div className="app-shell">
      <aside className={`sidebar ${open ? "open" : ""}`}>
        <Brand />
        <div className="workspace-chip"><span className="online-dot"/><div><strong>Operations Control Tower</strong><span>Internal AAL workspace</span></div></div>
        <div className="nav-list">
          {visible.map(item => { const section = item.section !== last; last = item.section; const active = path === item.href || path.startsWith(`${item.href}/`); return <div key={item.href}>{section && <div className="nav-section">{item.section}</div>}<Link href={item.href} className={`nav-link ${active ? "active" : ""}`} onClick={() => setOpen(false)}><span className="nav-icon"><Icon name={item.icon} size={17}/></span><span className="nav-link-copy"><strong>{item.label}</strong></span></Link></div>; })}
        </div>
        <div className="sidebar-command"><div className="sidebar-command-icon"><Icon name="spark" size={16}/></div><div><strong>AAL Command Intelligence</strong><p>Operations, revenue, cost and risk in one control layer.</p></div></div>
        <div className="sidebar-footer"><Link className="nav-link footer-link" href="/portal"><span className="nav-icon"><Icon name="globe" size={17}/></span><span className="nav-link-copy"><strong>Customer Portal</strong><small>Switch to client experience</small></span></Link><button className="nav-link nav-button footer-link" onClick={doLogout}><span className="nav-icon"><Icon name="logout" size={17}/></span><span className="nav-link-copy"><strong>Sign out</strong></span></button></div>
      </aside>
      <section className="workspace">
        <header className="topbar"><button className="icon-btn mobile-menu" onClick={() => setOpen(!open)} aria-label="Open navigation"><Icon name="menu"/></button><div className="topbar-context"><div className="topbar-breadcrumb">AAL <span>/</span> Operations Control Tower</div><SearchBox query={query} setQuery={setQuery} results={results}/></div><div className="top-actions"><div className="live-indicator"><span/> SYSTEM ONLINE</div><button className="icon-btn"><Icon name="bell"/></button><div className="top-divider"/><div className="operator"><div className="avatar">OP</div><div className="operator-copy"><strong>Operations</strong><span>{role || "AAL workspace"}</span></div></div></div></header>{children}
      </section>
    </div>
  );
}

function Brand() { return <div className="brand"><div className="brand-mark"><Icon name="plane" size={20}/></div><div className="brand-copy"><div className="brand-title">AAL</div><div className="brand-subtitle">AFRICA LOGISTIC AVIATION</div></div></div>; }
function SearchBox({query,setQuery,results}:{query:string;setQuery:(v:string)=>void;results:Shipment[]}) { return <div className="global-search-wrap"><Icon name="search" size={16}/><input className="global-search" value={query} onChange={e=>setQuery(e.target.value)} placeholder="Search shipment, AWB, client, invoice…"/><kbd>⌘ K</kbd>{results.length>0&&<div className="search-results">{results.map(s=><Link href={`/shipments/${s.id}`} key={s.id}><div><strong>{s.referenceCode}</strong><span>{s.clientName||"Unassigned client"}</span></div><Icon name="chevron" size={15}/></Link>)}</div>}</div>; }

function CustomerShell({children,path,open,setOpen,query,setQuery,results,doLogout}:{children:React.ReactNode;path:string;open:boolean;setOpen:(v:boolean)=>void;query:string;setQuery:(v:string)=>void;results:Shipment[];doLogout:()=>void}) {
  return <div className="portal-shell">
    <aside className={`portal-sidebar ${open?"open":""}`}>
      <div className="portal-brand"><div className="portal-logo"><Icon name="plane" size={20}/></div><div><strong>AAL</strong><span>AFRICA LOGISTIC AVIATION</span></div></div>
      <div className="portal-account"><div className="portal-account-label">CUSTOMER WORKSPACE</div><strong>My AAL Logistics</strong><span>Connected account</span></div>
      <nav className="portal-nav">{portalNav.map(item=>{const active=path===item.href||path.startsWith(`${item.href}/`);return <Link key={item.href} href={item.href} className={active?"active":""} onClick={()=>setOpen(false)}><Icon name={item.icon} size={17}/><span>{item.label}</span>{item.href==="/portal/quote"&&<b>NEW</b>}</Link>})}</nav>
      <div className="portal-sidebar-bottom"><Link href="/dashboard"><Icon name="grid" size={16}/> Internal control tower</Link><button onClick={doLogout}><Icon name="logout" size={16}/> Sign out</button></div>
    </aside>
    <main className="portal-main">
      <header className="portal-topbar"><button className="icon-btn portal-menu" onClick={()=>setOpen(!open)}><Icon name="menu"/></button><div className="portal-top-search"><SearchBox query={query} setQuery={setQuery} results={results}/></div><div className="portal-top-actions"><button className="portal-icon"><Icon name="bell"/></button><button className="portal-help">Help</button><div className="portal-avatar">AA</div></div></header>
      {children}
    </main>
  </div>;
}
