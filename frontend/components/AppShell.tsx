"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth-context";
import { shipmentsApi, Shipment } from "@/lib/api-client";
import Icon, { IconName } from "./Icon";
import { useEffect, useState } from "react";

type NavItem = { label: string; href: string; icon: IconName };

const internalNav: Array<NavItem & { section: string; roles?: string[] }> = [
  {
    section: "CONTROL TOWER",
    label: "Command Center",
    href: "/aal-control-tower",
    icon: "grid",
    roles: ["ADMIN", "MANAGER", "OPERATIONS", "SALES", "FINANCE"],
  },
  {
    section: "CONTROL TOWER",
    label: "Advanced Logistics OS",
    href: "/advanced-logistics",
    icon: "grid",
    roles: [
      "ADMIN",
      "MANAGER",
      "OPERATIONS",
      "SALES",
      "FINANCE",
      "AIR_CARGO",
      "WAREHOUSE",
      "DISPATCH",
    ],
  },
  {
    section: "CONTROL TOWER",
    label: "Shipment Register",
    href: "/shipments",
    icon: "ship",
    roles: ["ADMIN", "MANAGER", "OPERATIONS", "SALES", "FINANCE"],
  },
  {
    section: "OPERATIONS",
    label: "Air Cargo",
    href: "/air-cargo",
    icon: "plane",
    roles: ["ADMIN", "MANAGER", "OPERATIONS", "AIR_CARGO"],
  },
  {
    section: "OPERATIONS",
    label: "Dispatch & Fleet",
    href: "/trips",
    icon: "truck",
    roles: ["ADMIN", "MANAGER", "OPERATIONS", "DISPATCH"],
  },
  {
    section: "OPERATIONS",
    label: "Mobile Operations",
    href: "/mobile-ops",
    icon: "truck",
    roles: ["ADMIN", "MANAGER", "OPERATIONS", "DISPATCH", "WAREHOUSE"],
  },
  {
    section: "OPERATIONS",
    label: "Warehouse Control",
    href: "/warehouses",
    icon: "warehouse",
    roles: ["ADMIN", "MANAGER", "OPERATIONS", "WAREHOUSE"],
  },
  {
    section: "OPERATIONS",
    label: "Exceptions",
    href: "/exceptions",
    icon: "bell",
    roles: ["ADMIN", "MANAGER", "OPERATIONS"],
  },
  {
    section: "OPERATIONS",
    label: "Documents & Customs",
    href: "/documents",
    icon: "file",
    roles: ["ADMIN", "MANAGER", "OPERATIONS", "AIR_CARGO"],
  },
  {
    section: "COMMERCIAL & FINANCE",
    label: "Customers",
    href: "/customers",
    icon: "chart",
    roles: ["ADMIN", "MANAGER", "SALES"],
  },
  {
    section: "COMMERCIAL & FINANCE",
    label: "Sales & Quotations",
    href: "/commercial",
    icon: "chart",
    roles: ["ADMIN", "MANAGER", "SALES"],
  },
  {
    section: "COMMERCIAL & FINANCE",
    label: "Billing & Receivables",
    href: "/billing",
    icon: "money",
    roles: ["ADMIN", "MANAGER", "FINANCE"],
  },
  {
    section: "DATA & PLATFORM",
    label: "Excel Data Migration",
    href: "/command-center",
    icon: "file",
    roles: ["ADMIN", "MANAGER", "OPERATIONS"],
  },
  {
    section: "DATA & PLATFORM",
    label: "Audit Trail",
    href: "/audit",
    icon: "shield",
    roles: ["ADMIN", "MANAGER"],
  },
  {
    section: "DATA & PLATFORM",
    label: "Settings",
    href: "/settings",
    icon: "shield",
    roles: ["ADMIN", "MANAGER", "FINANCE", "OPERATIONS"],
  },
  {
    section: "DATA & PLATFORM",
    label: "Multimodal Mode Control",
    href: "/multimodal",
    icon: "globe",
    roles: ["ADMIN", "MANAGER", "OPERATIONS", "AIR_CARGO"],
  },
  {
    section: "ADMINISTRATION",
    label: "Users & Access",
    href: "/users",
    icon: "shield",
    roles: ["ADMIN"],
  },
];

export default function AppShell({ children }: { children: React.ReactNode }) {
  const path = usePathname();
  const router = useRouter();
  const { logout, isLoading, accessToken, role } = useAuth();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [shipments, setShipments] = useState<Shipment[]>([]);

  const bare =
    path === "/login" ||
    path === "/forgot-password" ||
    path === "/reset-password" ||
    path === "/quote" ||
    path === "/book" ||
    path === "/track" ||
    path.startsWith("/track/") ||
    path.startsWith("/quote/view/") ||
    path.startsWith("/quote/results/") ||
    path === "/";

  useEffect(() => {
    if (role === "CUSTOMER" && accessToken) {
      router.replace("/");
    }
  }, [accessToken, role, router]);

  useEffect(() => {
    if (!accessToken || role === "CUSTOMER") {
      setShipments([]);
      return;
    }

    const q = query.trim();
    if (!q) {
      setShipments([]);
      return;
    }

    let active = true;
    const timer = window.setTimeout(() => {
      shipmentsApi
        .list({ page: 0, size: 7, q })
        .then((response) => {
          if (active) setShipments(response.content);
        })
        .catch(() => {
          if (active) setShipments([]);
        });
    }, 220);

    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [accessToken, query]);

  const results = query.trim() ? shipments : [];

  if (bare) return <>{children}</>;

  if (isLoading)
    return (
      <div className="app-loading">
        <div
          className="loading-mark"
          style={{
            width: 64,
            height: 64,
            borderRadius: 16,
            overflow: "hidden",
            padding: 0,
            background: "#071A52",
          }}
        >
          <img
            src="/branding/aal-logo.jpg"
            alt="Aviation Africa Logistics Ltd"
            className="aal-logo"
          />
        </div>
        <div>
          <strong>Aviation Africa Logistics Ltd</strong>
          <span>Loading secure operations workspace…</span>
        </div>
      </div>
    );

  if (!accessToken) return <>{children}</>;

  // Customer accounts are intentionally not supported in the internal UI.
  // AuthProvider clears them before this shell normally renders, but this guard
  // also protects against transient state during session restoration.
  if (role === "CUSTOMER") return null;

  async function doLogout() {
    await logout();
    router.push("/login");
  }

  const visible = internalNav.filter(
    (item) => !item.roles || (role && item.roles.includes(role)),
  );
  let last = "";

  return (
    <div className="app-shell">
      <aside className={`sidebar ${open ? "open" : ""}`}>
        <Brand />
        <div className="workspace-chip">
          <span className="online-dot" />
          <div>
            <strong>Operations Control Tower</strong>
            <span>Internal AAL workspace</span>
          </div>
        </div>
        <div className="nav-list">
          {visible.map((item) => {
            const section = item.section !== last;
            last = item.section;
            const active =
              path === item.href || path.startsWith(`${item.href}/`);

            return (
              <div key={item.href}>
                {section && <div className="nav-section">{item.section}</div>}
                <Link
                  href={item.href}
                  className={`nav-link ${active ? "active" : ""}`}
                  onClick={() => setOpen(false)}
                >
                  <span className="nav-icon">
                    <Icon name={item.icon} size={17} />
                  </span>
                  <span className="nav-link-copy">
                    <strong>{item.label}</strong>
                  </span>
                </Link>
              </div>
            );
          })}
        </div>
        <div className="sidebar-command">
          <div className="sidebar-command-icon">
            <Icon name="spark" size={16} />
          </div>
          <div>
            <strong>AAL Command Intelligence</strong>
            <p>Operations, revenue, cost and risk in one control layer.</p>
          </div>
        </div>
        <div className="sidebar-footer">
          <Link className="nav-link footer-link" href="/quote">
            <span className="nav-icon">
              <Icon name="plus" size={17} />
            </span>
            <span className="nav-link-copy">
              <strong>Public quote flow</strong>
              <small>Open customer-facing quote page</small>
            </span>
          </Link>
          <button
            className="nav-link nav-button footer-link"
            onClick={doLogout}
          >
            <span className="nav-icon">
              <Icon name="logout" size={17} />
            </span>
            <span className="nav-link-copy">
              <strong>Sign out</strong>
            </span>
          </button>
        </div>
      </aside>

      <section className="workspace">
        <header className="topbar">
          <button
            className="icon-btn mobile-menu"
            onClick={() => setOpen(!open)}
            aria-label="Open navigation"
          >
            <Icon name="menu" />
          </button>
          <div className="topbar-context">
            <div className="topbar-brand-mini">
              <img
                src="/branding/aal-logo.jpg"
                alt="Aviation Africa Logistics Ltd"
              />
              <div>
                <strong>
                  AVIATION AFRICA <b>LOGISTICS LTD</b>
                </strong>
                <span>
                  GLOBAL REACH <i>•</i> AFRICAN ROOTS
                </span>
              </div>
            </div>
            <SearchBox query={query} setQuery={setQuery} results={results} />
          </div>
          <div className="top-actions">
            <div className="live-indicator">
              <span /> SYSTEM ONLINE
            </div>
            <button className="icon-btn" aria-label="Notifications">
              <Icon name="bell" />
            </button>
            <div className="top-divider" />
            <div className="operator">
              <div className="avatar">OP</div>
              <div className="operator-copy">
                <strong>Operations</strong>
                <span>{role || "AAL workspace"}</span>
              </div>
            </div>
          </div>
        </header>
        {children}
      </section>
    </div>
  );
}

function Brand() {
  return (
    <div className="brand" aria-label="Aviation Africa Logistics Ltd">
      <div className="brand-logo-frame">
        <img
          src="/branding/aal-logo.jpg"
          alt="Aviation Africa Logistics Ltd logo"
          className="aal-logo"
        />
      </div>
      <div className="brand-copy">
        <div className="brand-title">AVIATION AFRICA</div>
        <div className="brand-subtitle">
          LOGISTICS LTD · GLOBAL REACH · AFRICAN ROOTS
        </div>
      </div>
    </div>
  );
}

function SearchBox({
  query,
  setQuery,
  results,
}: {
  query: string;
  setQuery: (v: string) => void;
  results: Shipment[];
}) {
  return (
    <div className="global-search-wrap">
      <Icon name="search" size={16} />
      <input
        className="global-search"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        placeholder="Search shipment, AWB, client, invoice…"
      />
      <kbd>⌘ K</kbd>
      {results.length > 0 && (
        <div className="search-results">
          {results.map((s) => (
            <Link href={`/shipments/${s.id}`} key={s.id}>
              <div>
                <strong>{s.referenceCode}</strong>
                <span>{s.clientName || "Unassigned client"}</span>
              </div>
              <Icon name="chevron" size={15} />
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
