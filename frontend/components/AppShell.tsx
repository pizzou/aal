"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth-context";
import { shipmentsApi, Shipment } from "@/lib/api-client";
import Icon, { IconName } from "./Icon";
import { useEffect, useState } from "react";

type NavItem = {
  label: string;
  href: string;
  icon: IconName;
  primary?: boolean;
};

// Six familiar daily destinations. Specialist routes remain available through
// the Operations workspace or the collapsed administrative tools area.
const internalNav: Array<NavItem & { section: string; roles?: string[] }> = [
  {
    section: "DAILY WORK",
    label: "Home",
    href: "/aal-control-tower",
    icon: "grid",
    primary: true,
    roles: [
      "ADMIN",
      "MANAGER",
      "OPERATIONS",
      "SALES",
      "FINANCE",
      "DISPATCH",
      "WAREHOUSE",
      "AIR_CARGO",
    ],
  },
  {
    section: "DAILY WORK",
    label: "Shipments",
    href: "/shipments",
    icon: "ship",
    primary: true,
    roles: ["ADMIN", "MANAGER", "OPERATIONS", "SALES", "FINANCE"],
  },
  {
    section: "DAILY WORK",
    label: "Operations",
    href: "/operations",
    icon: "truck",
    primary: true,
    roles: [
      "ADMIN",
      "MANAGER",
      "OPERATIONS",
      "AIR_CARGO",
      "DISPATCH",
      "WAREHOUSE",
    ],
  },
  {
    section: "DAILY WORK",
    label: "Customers & quotes",
    href: "/commercial",
    icon: "chart",
    primary: true,
    roles: ["ADMIN", "MANAGER", "SALES"],
  },
  {
    section: "DAILY WORK",
    label: "Invoices & payments",
    href: "/billing",
    icon: "money",
    primary: true,
    roles: ["ADMIN", "MANAGER", "FINANCE"],
  },
  {
    section: "DAILY WORK",
    label: "Reports",
    href: "/reports",
    icon: "chart",
    primary: true,
    roles: ["ADMIN", "MANAGER", "FINANCE", "OPERATIONS", "SALES"],
  },
  {
    section: "SETUP & CONTROLS",
    label: "Company invoice setup",
    href: "/billing/setup",
    icon: "money",
    roles: ["ADMIN", "FINANCE"],
  },
  {
    section: "SETUP & CONTROLS",
    label: "Advanced finance",
    href: "/billing/hardening",
    icon: "money",
    roles: ["ADMIN", "FINANCE"],
  },
  {
    section: "SETUP & CONTROLS",
    label: "Supplier bills",
    href: "/finance/supplier-bills",
    icon: "money",
    roles: ["ADMIN", "FINANCE"],
  },
  {
    section: "SETUP & CONTROLS",
    label: "Income allocation",
    href: "/finance/income-allocation",
    icon: "money",
    roles: ["ADMIN", "MANAGER", "FINANCE"],
  },
  {
    section: "SETUP & CONTROLS",
    label: "Profit distribution",
    href: "/finance/profit-allocation",
    icon: "money",
    roles: ["ADMIN", "MANAGER", "FINANCE"],
  },
  {
    section: "SETUP & CONTROLS",
    label: "Ledger & periods",
    href: "/finance/ledger-export",
    icon: "money",
    roles: ["ADMIN", "FINANCE"],
  },
  {
    section: "SETUP & CONTROLS",
    label: "Excel migration",
    href: "/legacy-import",
    icon: "file",
    roles: ["ADMIN", "MANAGER"],
  },
  {
    section: "SETUP & CONTROLS",
    label: "Audit trail",
    href: "/audit",
    icon: "shield",
    roles: ["ADMIN", "MANAGER"],
  },
  {
    section: "SETUP & CONTROLS",
    label: "Settings",
    href: "/settings",
    icon: "shield",
    roles: ["ADMIN", "MANAGER", "FINANCE", "OPERATIONS"],
  },
  {
    section: "SETUP & CONTROLS",
    label: "Users & permissions",
    href: "/users",
    icon: "shield",
    roles: ["ADMIN"],
  },
];

export default function AppShell({ children }: { children: React.ReactNode }) {
  const path = usePathname();
  const router = useRouter();
  const { logout, isLoading, accessToken, role, displayName } = useAuth();
  const [open, setOpen] = useState(false);
  const [showTools, setShowTools] = useState(false);
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
    if (bare || isLoading || accessToken) return;
    const next = `${path}${typeof window !== "undefined" ? window.location.search : ""}`;
    router.replace(
      `/login?next=${encodeURIComponent(next || "/aal-control-tower")}`,
    );
  }, [accessToken, bare, isLoading, path, router]);

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

  /*
   * Authentication restoration is deliberately non-blocking. Protected pages
   * render their own recovery state while AuthProvider restores the browser
   * credential. This prevents a slow API/Render cold start from replacing the
   * entire application with a blank global loader.
   */
  if (isLoading && !accessToken) return <>{children}</>;
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
  const primaryNav = visible.filter((item) => item.primary);
  const toolsNav = visible.filter((item) => !item.primary);
  const selectedTool = toolsNav.some(
    (item) => path === item.href || path.startsWith(`${item.href}/`),
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
          {primaryNav.map((item) => {
            const section = item.section !== last;
            last = item.section;
            const active =
              path === item.href ||
              path.startsWith(`${item.href}/`) ||
              (item.href === "/operations" &&
                [
                  "/air-cargo",
                  "/trips",
                  "/warehouses",
                  "/multimodal",
                  "/ocean",
                  "/road",
                  "/rail",
                  "/documents",
                  "/exceptions",
                  "/mobile-ops",
                ].some(
                  (prefix) => path === prefix || path.startsWith(`${prefix}/`),
                )) ||
              (item.href === "/commercial" && path === "/customers");

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
          {toolsNav.length > 0 && (
            <div className="nav-tools">
              <button
                type="button"
                className="nav-link nav-button"
                aria-expanded={showTools || selectedTool}
                onClick={() => setShowTools((current) => !current)}
              >
                <span className="nav-icon">
                  <Icon name="grid" size={17} />
                </span>
                <span className="nav-link-copy">
                  <strong>
                    {showTools || selectedTool
                      ? "Hide advanced tools"
                      : "More tools & setup"}
                  </strong>
                </span>
              </button>
              {(showTools || selectedTool) &&
                toolsNav.map((item) => (
                  <Link
                    key={item.href}
                    href={item.href}
                    className={`nav-link nav-tool-link ${path === item.href || path.startsWith(`${item.href}/`) ? "active" : ""}`}
                    onClick={() => setOpen(false)}
                  >
                    <span className="nav-icon">
                      <Icon name={item.icon} size={15} />
                    </span>
                    <span className="nav-link-copy">
                      <strong>{item.label}</strong>
                    </span>
                  </Link>
                ))}
            </div>
          )}
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
              <span /> SECURE WORKSPACE
            </div>
            <div className="top-divider" />
            <div className="operator">
              <div className="avatar">
                {(displayName || "AU")
                  .trim()
                  .split(/\\s+/)
                  .slice(0, 2)
                  .map((part) => part[0])
                  .join("")
                  .toUpperCase()}
              </div>
              <div className="operator-copy">
                <strong>{displayName || "Authenticated user"}</strong>
                <span>{role || "AAL workspace"}</span>
              </div>
            </div>
          </div>
        </header>
        <GuidedNextStep path={path} role={role} />
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
        placeholder="Search shipments, AWB or customer…"
      />
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

/** Existing screens remain available by direct link; this guidance keeps
 * normal staff on a predictable daily workflow without bypassing permissions. */
function GuidedNextStep({ path, role }: { path: string; role: string | null }) {
  const canShip = ["ADMIN", "MANAGER", "OPERATIONS", "SALES"].includes(
    role ?? "",
  );
  const canInvoice = ["ADMIN", "MANAGER", "FINANCE"].includes(role ?? "");
  const canReport = [
    "ADMIN",
    "MANAGER",
    "FINANCE",
    "OPERATIONS",
    "SALES",
  ].includes(role ?? "");
  const next =
    path === "/billing/setup" && ["ADMIN", "FINANCE"].includes(role ?? "")
      ? {
          href: "/billing",
          label: "Create customer invoice",
          context: "After finance has verified tax setup",
        }
      : path === "/aal-control-tower" && canShip
        ? {
            href: "/shipments",
            label: "Open shipments",
            context: "Start with the daily shipment register",
          }
        : path === "/shipments" && canShip
          ? {
              href: "/shipments?create=1",
              label: "Create shipment",
              context: "Register a new job in this workspace",
            }
          : path.startsWith("/shipments/") && canInvoice
            ? {
                href: "/billing",
                label: "Billing & collections",
                context: "After verifying shipment charges",
              }
            : path === "/billing" && canReport
              ? {
                  href: "/reports",
                  label: "Review reports",
                  context: "Monitor confirmed business records",
                }
              : null;
  if (!next) return null;
  return (
    <nav className="aal-next-step" aria-label="Suggested next workflow step">
      <span>{next.context}</span>
      <Link href={next.href}>Next: {next.label} →</Link>
    </nav>
  );
}
