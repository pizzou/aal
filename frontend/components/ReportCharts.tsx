import { ManagementReport } from "@/lib/api-client";

function finite(value: number | null | undefined) {
  return Number.isFinite(value) ? Number(value) : 0;
}

function shortMoney(value: number, currency: string) {
  const amount = finite(value);
  const abs = Math.abs(amount);
  const compact =
    abs >= 1_000_000
      ? `${(amount / 1_000_000).toFixed(1)}M`
      : abs >= 1_000
        ? `${(amount / 1_000).toFixed(1)}K`
        : Math.round(amount).toLocaleString();
  return currency ? `${currency} ${compact}` : compact;
}

function BarChart({
  title,
  subtitle,
  items,
  currency,
}: {
  title: string;
  subtitle: string;
  items: Array<{ label: string; value: number }>;
  currency: string;
}) {
  const safe = items
    .map((item) => ({ ...item, value: finite(item.value) }))
    .filter((item) => item.label.trim());
  const max = Math.max(...safe.map((item) => Math.abs(item.value)), 1);

  return (
    <div>
      <h2 className="card-title">{title}</h2>
      <p className="page-subtitle">{subtitle}</p>
      {safe.length === 0 ? (
        <div className="card-muted" style={{ padding: "22px 0" }}>
          No data for this period.
        </div>
      ) : (
        <div style={{ display: "grid", gap: 13, marginTop: 18 }}>
          {safe.map((item) => (
            <div key={item.label}>
              <div
                style={{
                  display: "flex",
                  justifyContent: "space-between",
                  gap: 12,
                  fontSize: 13,
                  marginBottom: 6,
                }}
              >
                <strong style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                  {item.label}
                </strong>
                <span style={{ whiteSpace: "nowrap" }}>
                  {shortMoney(item.value, currency)}
                </span>
              </div>
              <div
                aria-hidden="true"
                style={{
                  height: 9,
                  borderRadius: 99,
                  background: "rgba(7, 26, 82, .08)",
                  overflow: "hidden",
                }}
              >
                <div
                  style={{
                    width: `${Math.max(2, (Math.abs(item.value) / max) * 100)}%`,
                    height: "100%",
                    borderRadius: 99,
                    background: "currentColor",
                    opacity: 0.78,
                  }}
                />
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function MonthlyChart({ report }: { report: ManagementReport }) {
  const data = report.monthlyTrend.map((item) => ({
    ...item,
    revenue: finite(item.invoicedRevenue),
    collected: finite(item.collectedRevenue),
    profit: finite(item.grossProfit),
  }));

  const max = Math.max(
    ...data.flatMap((item) => [item.revenue, item.collected, item.profit]),
    1,
  );
  const width = 920;
  const height = 300;
  const padding = { top: 20, right: 24, bottom: 48, left: 54 };
  const chartWidth = width - padding.left - padding.right;
  const chartHeight = height - padding.top - padding.bottom;
  const groupWidth = data.length ? chartWidth / data.length : chartWidth;
  const barWidth = Math.min(24, groupWidth / 4);
  const y = (value: number) =>
    padding.top + chartHeight - (Math.max(0, value) / max) * chartHeight;

  return (
    <div>
      <div style={{ display: "flex", justifyContent: "space-between", gap: 12, alignItems: "flex-start" }}>
        <div>
          <h2 className="card-title">Financial trend</h2>
          <p className="page-subtitle">
            Monthly billed revenue, collections and gross profit.
          </p>
        </div>
        <div style={{ display: "flex", gap: 14, fontSize: 12, whiteSpace: "nowrap" }}>
          <span><i style={{ display: "inline-block", width: 9, height: 9, borderRadius: 99, background: "currentColor", marginRight: 5 }} />Revenue</span>
          <span><i style={{ display: "inline-block", width: 9, height: 9, borderRadius: 99, background: "currentColor", marginRight: 5, opacity: .65 }} />Collections</span>
          <span><i style={{ display: "inline-block", width: 9, height: 9, borderRadius: 99, background: "currentColor", marginRight: 5, opacity: .4 }} />Profit</span>
        </div>
      </div>
      {data.length === 0 ? (
        <div className="card-muted" style={{ padding: "30px 0" }}>
          No monthly financial activity for this period.
        </div>
      ) : (
        <div style={{ overflowX: "auto", marginTop: 14 }}>
          <svg
            viewBox={`0 0 ${width} ${height}`}
            role="img"
            aria-label="Monthly revenue, collections and gross profit chart"
            style={{ width: "100%", minWidth: 620, height: "auto", display: "block" }}
          >
            {[0, .25, .5, .75, 1].map((ratio) => {
              const value = max * ratio;
              const yy = padding.top + chartHeight - ratio * chartHeight;
              return (
                <g key={ratio}>
                  <line
                    x1={padding.left}
                    x2={width - padding.right}
                    y1={yy}
                    y2={yy}
                    stroke="currentColor"
                    opacity=".09"
                  />
                  <text
                    x={padding.left - 8}
                    y={yy + 4}
                    textAnchor="end"
                    fontSize="10"
                    fill="currentColor"
                    opacity=".55"
                  >
                    {shortMoney(value, report.currency)}
                  </text>
                </g>
              );
            })}
            {data.map((item, index) => {
              const center = padding.left + index * groupWidth + groupWidth / 2;
              const baseY = padding.top + chartHeight;
              const bars = [
                { value: item.revenue, opacity: .86, offset: -barWidth * 1.5 },
                { value: item.collected, opacity: .62, offset: -barWidth / 2 },
                { value: item.profit, opacity: .4, offset: barWidth / 2 },
              ];
              return (
                <g key={item.month}>
                  {bars.map((bar, barIndex) => {
                    const top = y(bar.value);
                    return (
                      <rect
                        key={barIndex}
                        x={center + bar.offset}
                        y={top}
                        width={barWidth}
                        height={Math.max(1, baseY - top)}
                        rx="4"
                        fill="currentColor"
                        opacity={bar.opacity}
                      />
                    );
                  })}
                  <text
                    x={center}
                    y={height - 20}
                    textAnchor="middle"
                    fontSize="10"
                    fill="currentColor"
                    opacity=".65"
                  >
                    {item.month.slice(0, 7)}
                  </text>
                </g>
              );
            })}
          </svg>
        </div>
      )}
    </div>
  );
}

export default function ReportCharts({ report }: { report: ManagementReport }) {
  const operations = [
    { label: "Active shipments", value: report.operations.activeShipments },
    { label: "Completed shipments", value: report.operations.completedShipments },
    { label: "Departed shipments", value: report.operations.departedShipments },
    { label: "Delayed shipments", value: report.operations.delayedShipments },
    { label: "Exceptions", value: report.operations.exceptionShipments },
  ];

  const destinations = report.destinationProfitability
    .slice()
    .sort((a, b) => finite(b.revenue) - finite(a.revenue))
    .slice(0, 7)
    .map((item) => ({
      label: item.destination || "Unspecified",
      value: item.revenue,
    }));

  const aging = report.receivablesAging.map((item) => ({
    label: item.bucket,
    value: item.balance,
  }));

  return (
    <section aria-label="Management report visual analytics" style={{ display: "grid", gap: 14, marginTop: 14 }}>
      <div className="card">
        <MonthlyChart report={report} />
      </div>
      <div className="grid grid-3">
        <div className="card">
          <BarChart
            title="Operational workload"
            subtitle={`On-time rate ${finite(report.operations.onTimeRatePercent).toFixed(1)}%`}
            items={operations}
            currency=""
          />
        </div>
        <div className="card">
          <BarChart
            title="Top destinations"
            subtitle="Revenue concentration by destination."
            items={destinations}
            currency={report.currency}
          />
        </div>
        <div className="card">
          <BarChart
            title="Receivables aging"
            subtitle={`${report.receivables.overdueInvoices} overdue invoices`}
            items={aging}
            currency={report.currency}
          />
        </div>
      </div>
    </section>
  );
}
