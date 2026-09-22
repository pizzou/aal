"use client";

import Link from "next/link";
import { useEffect, useMemo, useState } from "react";
import {
  AdvancedDashboard,
  ApiError,
  Shipment,
  commandCenterApi,
  shipmentsApi,
  platformHealthApi,
  operationsApi,
  openOperationsEventStream,
} from "@/lib/api-client";
import { useAuth } from "@/lib/auth-context";
import { useRouter } from "next/navigation";
import Icon, { IconName } from "@/components/Icon";

const today = () => new Date().toLocaleDateString("en-CA");

function money(value: number | null | undefined, currency: string): string {
  return `${currency} ${(value ?? 0).toLocaleString(undefined, {
    minimumFractionDigits: 0,
    maximumFractionDigits: 0,
  })}`;
}

function pct(value: number | null | undefined): string {
  return `${(value ?? 0).toFixed(1)}%`;
}

function statusTone(status: string): string {
  const value = status.toUpperCase();
  if (["DELIVERED", "COMPLETED"].includes(value))
    return "dashboard-status success";
  if (["ON_HOLD", "CANCELLED", "CUSTOMS"].includes(value))
    return "dashboard-status danger";
  if (["DEPARTED", "IN_TRANSIT", "ARRIVED", "OUT_FOR_DELIVERY"].includes(value))
    return "dashboard-status info";
  return "dashboard-status warning";
}

function modeLabel(mode: string): string {
  const map: Record<string, string> = {
    AIR: "Air",
    SEA: "Ocean",
    ROAD: "Road",
    RAIL: "Rail",
    INLAND_WATERWAY: "Water",
    COURIER: "Courier",
    LAST_MILE: "Last Mile",
    RORO: "RoRo",
    PROJECT_CARGO: "Project",
  };
  return map[mode] ?? mode.replaceAll("_", " ");
}

export default function AalControlTower() {
  const { accessToken, isLoading } = useAuth();
  const router = useRouter();
  const [data, setData] = useState<AdvancedDashboard | null>(null);
  const [shipments, setShipments] = useState<Shipment[]>([]);
  const [fleetLive, setFleetLive] = useState<
    import("@/lib/api-client").FleetLive[]
  >([]);
  const [asOf, setAsOf] = useState(today());
  const [error, setError] = useState("");
  const [refreshing, setRefreshing] = useState(false);
  const [systemHealth, setSystemHealth] = useState<{
    healthy: boolean;
    status: string;
  } | null>(null);
  const [liveEventAt, setLiveEventAt] = useState<string | null>(null);

  async function load() {
    if (!accessToken) return;
    setRefreshing(true);
    setError("");
    try {
      const [dashboard, recent, health] = await Promise.all([
        commandCenterApi.advanced(asOf),
        shipmentsApi.list({ page: 0, size: 5 }),
        platformHealthApi.current(),
      ]);
      const fleet = await operationsApi.fleetLive().catch(() => []);
      setData(dashboard);
      setShipments(recent.content);
      setSystemHealth({ healthy: health.healthy, status: health.status });
      setFleetLive(fleet);
    } catch (e) {
      setError(
        e instanceof ApiError
          ? e.message
          : "Unable to load the operations control tower",
      );
    } finally {
      setRefreshing(false);
    }
  }

  useEffect(() => {
    if (!isLoading && !accessToken) router.replace("/login");
  }, [isLoading, accessToken, router]);

  useEffect(() => {
    if (!accessToken) return;
    void load();
    const close = openOperationsEventStream({
      onEvent: () => {
        setLiveEventAt(new Date().toISOString());
        void load();
      },
      onError: () =>
        setSystemHealth((current) =>
          current
            ? { ...current, healthy: false, status: "STREAM_DEGRADED" }
            : current,
        ),
    });
    return close;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [accessToken, asOf]);

  const trendMax = useMemo(
    () =>
      Math.max(
        1,
        ...(data?.trend ?? []).map((x) => Math.max(x.revenue, x.operatingCost)),
      ),
    [data],
  );

  if (isLoading || !accessToken) return null;

  const currency = data?.financial.currency ?? "USD";
  const total = data?.operations.totalShipments ?? 0;
  const inTransit = data?.operations.activeShipments ?? 0;
  const delivered = data?.operations.deliveredShipments ?? 0;
  const exceptions = data?.operations.exceptionShipments ?? 0;

  return (
    <main className="dashboard-page">
      <section className="dashboard-heading">
        <div>
          <div className="eyebrow">
            AVIATION AFRICA LOGISTICS · CONTROL TOWER
          </div>
          <h1>Command Center</h1>
          <p>Real-time visibility. Smarter decisions. Faster deliveries.</p>
        </div>
        <div className="dashboard-heading-actions">
          <div className="dashboard-date">
            <Icon name="calendar" size={15} />
            <input
              aria-label="As of date"
              type="date"
              value={asOf}
              onChange={(e) => setAsOf(e.target.value)}
            />
          </div>
          <span
            className={`dashboard-online ${systemHealth?.healthy === false ? "dashboard-online-danger" : ""}`}
          >
            <span />{" "}
            {systemHealth?.healthy === false
              ? "System Degraded"
              : systemHealth?.healthy === true
                ? "System Operational"
                : "Checking system"}
            {liveEventAt ? (
              <small>
                {" "}
                · live {new Date(liveEventAt).toLocaleTimeString()}
              </small>
            ) : null}
          </span>
          <button
            className="btn"
            onClick={() => void load()}
            disabled={refreshing}
          >
            {refreshing ? "Refreshing…" : "Refresh"}
          </button>
        </div>
      </section>

      {error && (
        <div className="alert alert-error dashboard-alert">{error}</div>
      )}

      {!data ? (
        <section className="dashboard-loading card">
          <img
            src="/branding/aal-logo.jpg"
            alt="Aviation Africa Logistics Ltd"
          />
          <strong>Loading live control-tower data…</strong>
          <span>Connecting to shipments, finance and operations services.</span>
        </section>
      ) : (
        <>
          <section className="dashboard-kpis">
            <DashboardKpi
              icon="ship"
              accent="navy"
              title="Total Shipments"
              value={total.toLocaleString()}
              meta={`${pct(data.operations.completionRatePercent)} completion`}
              trend="Operations"
              href="/shipments"
            />
            <DashboardKpi
              icon="plane"
              accent="red"
              title="In Transit"
              value={inTransit.toLocaleString()}
              meta={`${pct(data.operations.onTimeRatePercent)} on time`}
              trend="Live movement"
              href="/track"
            />
            <DashboardKpi
              icon="check"
              accent="yellow"
              title="Delivered"
              value={delivered.toLocaleString()}
              meta={`${data.operations.dueToday.toLocaleString()} due today`}
              trend="Completed"
              href="/shipments?status=DELIVERED"
            />
            <DashboardKpi
              icon="bell"
              accent="red"
              title="Exceptions"
              value={exceptions.toLocaleString()}
              meta={`${data.operations.delayedShipments.toLocaleString()} delayed`}
              trend="Attention required"
              href="/exceptions"
            />
            <DashboardKpi
              icon="money"
              accent="navy"
              title={`Revenue (${currency})`}
              value={money(data.financial.billed, "").trim()}
              meta={`${money(data.financial.collected, currency)} collected`}
              trend={`${pct(data.financial.grossMarginPercent)} margin`}
              href="/billing"
            />
          </section>

          <section className="dashboard-main-grid">
            <div className="dashboard-card dashboard-chart-card">
              <DashboardCardHeader
                eyebrow="NETWORK PERFORMANCE"
                title="Shipment Overview"
                href="/reports"
              />
              <div className="chart-legend">
                {data.modeMix.slice(0, 4).map((item, index) => (
                  <span key={item.mode}>
                    <i className={`legend-dot dot-${index}`} />
                    {modeLabel(item.mode)}
                  </span>
                ))}
              </div>
              <div className="line-chart">
                <div className="chart-y-labels">
                  <span>100%</span>
                  <span>75%</span>
                  <span>50%</span>
                  <span>25%</span>
                  <span>0</span>
                </div>
                <svg
                  viewBox="0 0 760 250"
                  role="img"
                  aria-label="Shipment trend chart"
                  preserveAspectRatio="none"
                >
                  {[20, 70, 120, 170, 220].map((y) => (
                    <line
                      key={y}
                      x1="24"
                      y1={y}
                      x2="748"
                      y2={y}
                      className="chart-grid-line"
                    />
                  ))}
                  {data.trend.length > 1 && (
                    <>
                      <polyline
                        className="chart-line chart-line-red"
                        points={data.trend
                          .map(
                            (x, i) =>
                              `${30 + (i * 710) / Math.max(1, data.trend.length - 1)},${230 - (x.shipments / Math.max(1, total)) * 185}`,
                          )
                          .join(" ")}
                      />
                      <polyline
                        className="chart-line chart-line-blue"
                        points={data.trend
                          .map(
                            (x, i) =>
                              `${30 + (i * 710) / Math.max(1, data.trend.length - 1)},${230 - (x.revenue / trendMax) * 165}`,
                          )
                          .join(" ")}
                      />
                      <polyline
                        className="chart-line chart-line-yellow"
                        points={data.trend
                          .map(
                            (x, i) =>
                              `${30 + (i * 710) / Math.max(1, data.trend.length - 1)},${230 - (x.operatingCost / trendMax) * 145}`,
                          )
                          .join(" ")}
                      />
                    </>
                  )}
                </svg>
              </div>
              <div className="chart-x-labels">
                {data.trend.map((x) => (
                  <span key={x.date}>{x.date.slice(5)}</span>
                ))}
              </div>
            </div>

            <div className="dashboard-card tracking-card">
              <DashboardCardHeader
                eyebrow="LIVE VISIBILITY"
                title="Global Tracking"
                href="/track"
              />
              <div
                className="tracking-map"
                aria-label="Global shipment tracking visualization"
              >
                <div className="map-grid" />
                {fleetLive
                  .filter((x) => x.latitude != null && x.longitude != null)
                  .slice(0, 40)
                  .map((x) => (
                    <MapPoint
                      key={x.vehicleId}
                      x={`${Math.max(3, Math.min(97, ((Number(x.longitude) + 180) / 360) * 100))}%`}
                      y={`${Math.max(5, Math.min(95, ((90 - Number(x.latitude)) / 180) * 100))}%`}
                      label={x.registrationNumber}
                      tone={
                        x.vehicleStatus === "ON_TRIP"
                          ? "red"
                          : x.vehicleStatus === "MAINTENANCE"
                            ? "yellow"
                            : "green"
                      }
                    />
                  ))}
                {!fleetLive.some(
                  (x) => x.latitude != null && x.longitude != null,
                ) && (
                  <div className="map-empty-state">
                    No live GPS positions available
                  </div>
                )}
              </div>
              <div className="tracking-legend">
                <span>
                  <i className="legend-dot dot-red" />
                  In Transit
                </span>
                <span>
                  <i className="legend-dot dot-green" />
                  Delivered
                </span>
                <span>
                  <i className="legend-dot dot-yellow" />
                  Exception
                </span>
              </div>
            </div>

            <div className="dashboard-card quick-actions-card">
              <DashboardCardHeader eyebrow="OPERATIONS" title="Quick Actions" />
              <QuickAction
                href="/new-shipment"
                icon="ship"
                label="Create Shipment"
                tone="navy"
              />
              <QuickAction
                href="/air-cargo"
                icon="plane"
                label="Book Air Freight"
                tone="red"
              />
              <QuickAction
                href="/track"
                icon="search"
                label="Track Shipment"
                tone="yellow"
              />
              <QuickAction
                href="/commercial"
                icon="file"
                label="Request Quote"
                tone="navy"
              />
            </div>
          </section>

          <section className="dashboard-lower-grid">
            <div className="dashboard-card recent-card">
              <DashboardCardHeader
                eyebrow="EXECUTION"
                title="Recent Shipments"
                href="/shipments"
              />
              <div className="dashboard-table-wrap">
                <table className="dashboard-table">
                  <thead>
                    <tr>
                      <th>Tracking No.</th>
                      <th>Origin → Destination</th>
                      <th>Mode</th>
                      <th>Status</th>
                      <th>ETA</th>
                    </tr>
                  </thead>
                  <tbody>
                    {shipments.map((shipment) => (
                      <tr key={shipment.id}>
                        <td>
                          <Link
                            href={`/shipments/${shipment.id}`}
                            className="dashboard-link"
                          >
                            {shipment.referenceCode}
                          </Link>
                        </td>
                        <td>
                          {shipment.originCityPort ||
                            shipment.originCountry ||
                            "—"}{" "}
                          →{" "}
                          {shipment.destinationCityPort ||
                            shipment.destinationCountry ||
                            "—"}
                        </td>
                        <td>
                          <span className="mode-cell">
                            <Icon
                              name={
                                shipment.transportMode === "AIR"
                                  ? "plane"
                                  : shipment.transportMode === "SEA"
                                    ? "ship"
                                    : shipment.transportMode === "RAIL"
                                      ? "train"
                                      : "truck"
                              }
                              size={14}
                            />{" "}
                            {modeLabel(shipment.transportMode)}
                          </span>
                        </td>
                        <td>
                          <span className={statusTone(shipment.status)}>
                            {shipment.status.replaceAll("_", " ")}
                          </span>
                        </td>
                        <td>
                          {shipment.eta
                            ? new Date(shipment.eta).toLocaleDateString(
                                undefined,
                                { day: "2-digit", month: "short" },
                              )
                            : "—"}
                        </td>
                      </tr>
                    ))}
                    {!shipments.length && (
                      <tr>
                        <td colSpan={5} className="dashboard-empty">
                          No shipments available.
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>

            <div className="dashboard-card activity-card">
              <DashboardCardHeader
                eyebrow="OPERATIONS FEED"
                title="Activity Feed"
                href="/exceptions"
              />
              <div className="activity-feed">
                {data.actions.slice(0, 5).map((item, index) => (
                  <Link
                    href={item.href}
                    className="activity-item"
                    key={`${item.title}-${index}`}
                  >
                    <span className={`activity-icon activity-${index % 4}`}>
                      <Icon
                        name={
                          index === 0
                            ? "check"
                            : index === 1
                              ? "bell"
                              : index === 2
                                ? "truck"
                                : "file"
                        }
                        size={14}
                      />
                    </span>
                    <span className="activity-copy">
                      <strong>{item.title}</strong>
                      <small>{item.detail}</small>
                    </span>
                    <span className="activity-arrow">→</span>
                  </Link>
                ))}
                {!data.actions.length && (
                  <div className="dashboard-empty">
                    No new operational activity.
                  </div>
                )}
              </div>
            </div>

            <div className="dashboard-brand-card">
              <img
                src="/branding/aal-brand.png"
                alt="Aviation Africa Logistics Ltd — Global Reach African Roots"
              />
              <div className="dashboard-brand-overlay">
                <span>SMARTER LOGISTICS</span>
                <strong>Across Africa &amp; Beyond</strong>
                <small>Air · Ocean · Road · Rail · Warehousing</small>
              </div>
            </div>
          </section>

          <section className="dashboard-bottom-grid">
            <div className="dashboard-card compact-performance">
              <DashboardCardHeader
                eyebrow="FLEET & CAPACITY"
                title="Operational readiness"
              />
              <Readiness
                label="Vehicles available"
                value={`${data.fleet.availableVehicles} / ${data.fleet.totalVehicles}`}
                percent={data.fleet.vehicleUtilizationPercent}
              />
              <Readiness
                label="Drivers available"
                value={`${data.fleet.availableDrivers} / ${data.fleet.totalDrivers}`}
                percent={data.fleet.driverUtilizationPercent}
              />
              <Readiness
                label="On-time delivery"
                value={pct(data.operations.onTimeRatePercent)}
                percent={data.operations.onTimeRatePercent}
              />
            </div>
            <div className="dashboard-card compact-performance">
              <DashboardCardHeader
                eyebrow="FINANCE"
                title="Cash exposure"
                href="/billing"
              />
              <Readiness
                label="Receivables"
                value={money(data.financial.receivables, currency)}
                percent={Math.min(
                  100,
                  (data.financial.receivables /
                    Math.max(1, data.financial.billed)) *
                    100,
                )}
              />
              <Readiness
                label="Collected"
                value={money(data.financial.collected, currency)}
                percent={Math.min(
                  100,
                  (data.financial.collected /
                    Math.max(1, data.financial.billed)) *
                    100,
                )}
              />
              <Readiness
                label="Gross margin"
                value={pct(data.financial.grossMarginPercent)}
                percent={data.financial.grossMarginPercent}
              />
            </div>
            <div className="dashboard-card compact-performance">
              <DashboardCardHeader
                eyebrow="EXCEPTIONS"
                title="Priority attention"
                href="/exceptions"
              />
              {data.exceptions.slice(0, 3).map((item) => (
                <Link
                  key={`${item.reference}-${item.type}`}
                  href={`/shipments?q=${encodeURIComponent(item.reference)}`}
                  className="priority-row"
                >
                  <span
                    className={
                      item.severity.toUpperCase() === "CRITICAL"
                        ? "priority-dot red"
                        : "priority-dot yellow"
                    }
                  />
                  <span>
                    <strong>{item.reference}</strong>
                    <small>{item.message}</small>
                  </span>
                </Link>
              ))}
              {!data.exceptions.length && (
                <div className="dashboard-empty">No open exceptions.</div>
              )}
            </div>
          </section>
        </>
      )}
    </main>
  );
}

function DashboardKpi({
  icon,
  accent,
  title,
  value,
  meta,
  trend,
  href,
}: {
  icon: IconName;
  accent: string;
  title: string;
  value: string;
  meta: string;
  trend: string;
  href: string;
}) {
  return (
    <Link href={href} className={`dashboard-kpi dashboard-kpi-${accent}`}>
      <span className="dashboard-kpi-icon">
        <Icon name={icon} size={20} />
      </span>
      <span className="dashboard-kpi-copy">
        <small>{title}</small>
        <strong>{value}</strong>
        <em>{trend}</em>
        <span>{meta}</span>
      </span>
    </Link>
  );
}

function DashboardCardHeader({
  eyebrow,
  title,
  href,
}: {
  eyebrow: string;
  title: string;
  href?: string;
}) {
  return (
    <div className="dashboard-card-header">
      <div>
        <span className="eyebrow">{eyebrow}</span>
        <h2>{title}</h2>
      </div>
      {href && (
        <Link href={href}>
          View all <span>→</span>
        </Link>
      )}
    </div>
  );
}

function QuickAction({
  href,
  icon,
  label,
  tone,
}: {
  href: string;
  icon: IconName;
  label: string;
  tone: string;
}) {
  return (
    <Link href={href} className={`quick-action quick-${tone}`}>
      <Icon name={icon} size={17} />
      <strong>{label}</strong>
      <span>→</span>
    </Link>
  );
}

function MapPoint({
  x,
  y,
  label,
  tone,
}: {
  x: string;
  y: string;
  label: string;
  tone: string;
}) {
  return (
    <span className={`map-point map-${tone}`} style={{ left: x, top: y }}>
      <i />
      {label}
    </span>
  );
}

function Readiness({
  label,
  value,
  percent,
}: {
  label: string;
  value: string;
  percent: number;
}) {
  return (
    <div className="readiness-row">
      <div>
        <span>{label}</span>
        <strong>{value}</strong>
      </div>
      <div className="readiness-track">
        <i style={{ width: `${Math.max(0, Math.min(100, percent || 0))}%` }} />
      </div>
    </div>
  );
}
