"use client";

import Link from "next/link";
import { FormEvent, useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth-context";
import {
  ApiError,
  FinanceBankDestination,
  FinanceProfitAllocationResult,
  FinanceProfitRun,
  FinanceProfitRule,
  Shipment,
  financeIncomeConfigurationApi,
  financeProfitAllocationApi,
  shipmentsApi,
} from "@/lib/api-client";

type RuleDraft = { bankDestinationId: string; percentage: string };
function messageFor(error: unknown) {
  return error instanceof ApiError || error instanceof Error
    ? error.message
    : "Unable to complete profit distribution request.";
}
function money(n: number, currency: string) {
  return `${currency} ${Number(n || 0).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

export default function ProfitAllocationPage() {
  const { accessToken, role, isLoading } = useAuth();
  const router = useRouter();
  const allowed = ["ADMIN", "MANAGER", "FINANCE"].includes(role || "");
  const [banks, setBanks] = useState<FinanceBankDestination[]>([]);
  const [rules, setRules] = useState<FinanceProfitRule[]>([]);
  const [drafts, setDrafts] = useState<RuleDraft[]>([]);
  const [shipments, setShipments] = useState<Shipment[]>([]);
  const [shipmentId, setShipmentId] = useState("");
  const [result, setResult] = useState<FinanceProfitAllocationResult | null>(
    null,
  );
  const [runs, setRuns] = useState<FinanceProfitRun[]>([]);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");

  async function load() {
    if (!accessToken || !allowed) return;
    setBusy(true);
    setError("");
    try {
      const [nextBanks, nextRules, shipmentPage, nextRuns] = await Promise.all([
        financeIncomeConfigurationApi.banks(),
        financeProfitAllocationApi.rules(),
        shipmentsApi.list(),
        financeProfitAllocationApi.runs(),
      ]);
      const activeBanks = nextBanks.filter((bank) => bank.active);
      setBanks(activeBanks);
      setRules(nextRules);
      setShipments(shipmentPage.content || []);
      setRuns(nextRuns);
      const activeRules = nextRules.filter((rule) => rule.active);
      // Never invent bank shares or prefill a 100% allocation. The operator
      // must explicitly configure the real business policy.
      setDrafts(activeRules.map((rule) => ({
        bankDestinationId: rule.bankDestinationId,
        percentage: String(rule.percentage),
      })));
    } catch (e) {
      setError(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  useEffect(() => {
    if (!isLoading && (!accessToken || !allowed)) {
      router.replace("/aal-control-tower");
      return;
    }
    if (accessToken && allowed) void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [accessToken, isLoading, role]);

  const totalPercent = useMemo(
    () =>
      drafts.reduce((total, row) => total + (Number(row.percentage) || 0), 0),
    [drafts],
  );

  function updateDraft(index: number, field: keyof RuleDraft, value: string) {
    setDrafts((current) =>
      current.map((row, i) => (i === index ? { ...row, [field]: value } : row)),
    );
  }

  async function saveRules(event: FormEvent) {
    event.preventDefault();
    setError("");
    setMessage("");
    const chosen = drafts.filter(
      (row) => row.bankDestinationId && row.percentage.trim() !== "",
    );
    const total = chosen.reduce((sum, row) => sum + Number(row.percentage), 0);
    if (!chosen.length) {
      setError("Add at least one active bank destination.");
      return;
    }
    if (chosen.some((row) => !Number.isFinite(Number(row.percentage)) || Number(row.percentage) < 0 || Number(row.percentage) > 100)) {
      setError("Every profit share must be a valid percentage between 0 and 100.");
      return;
    }
    if (
      new Set(chosen.map((row) => row.bankDestinationId)).size !== chosen.length
    ) {
      setError("Each bank can appear only once.");
      return;
    }
    if (Math.abs(total - 100) > 0.0001) {
      setError(
        `Profit shares must total exactly 100%. Current total: ${total.toFixed(2)}%.`,
      );
      return;
    }
    setBusy(true);
    try {
      const saved = await financeProfitAllocationApi.saveRules(
        chosen.map((row) => ({
          bankDestinationId: row.bankDestinationId,
          percentage: Number(row.percentage),
        })),
      );
      setRules(saved);
      setDrafts(
        saved
          .filter((rule) => rule.active)
          .map((rule) => ({
            bankDestinationId: rule.bankDestinationId,
            percentage: String(rule.percentage),
          })),
      );
      setMessage(
        "Profit allocation rules saved. Future distributions will use these percentages.",
      );
    } catch (e) {
      setError(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  async function allocate(event: FormEvent) {
    event.preventDefault();
    if (!shipmentId) {
      setError("Select a shipment first.");
      return;
    }
    setBusy(true);
    setError("");
    setMessage("");
    setResult(null);
    try {
      const created =
        await financeProfitAllocationApi.allocateShipment(shipmentId);
      setResult(created);
      setMessage(
        "Profit distribution instructions created and recorded. No bank transfer was sent.",
      );
      setRuns(await financeProfitAllocationApi.runs());
    } catch (e) {
      setError(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  if (isLoading)
    return (
      <main className="page">
        <section className="card">
          <p>Loading secure finance workspace…</p>
        </section>
      </main>
    );
  if (!accessToken || !allowed) return null;

  return (
    <main
      className="page"
      style={{ maxWidth: 1240, margin: "0 auto", padding: "28px 24px 56px" }}
    >
      <header
        style={{
          display: "flex",
          alignItems: "flex-start",
          justifyContent: "space-between",
          gap: 20,
          flexWrap: "wrap",
          marginBottom: 24,
        }}
      >
        <div>
          <p
            style={{
              margin: "0 0 8px",
              opacity: 0.72,
              fontWeight: 700,
              letterSpacing: ".08em",
              fontSize: 12,
            }}
          >
            FINANCE CONTROL
          </p>
          <h1 style={{ margin: 0 }}>Profit Distribution</h1>
          <p style={{ maxWidth: 760, lineHeight: 1.6 }}>
            Calculate the selected shipment's actual net-profit basis and create auditable distribution instructions using your approved percentages.
          </p>
        </div>
        <Link className="btn" href="/finance/income-allocation">
          Manage bank accounts
        </Link>
      </header>

      {error && (
        <div
          className="alert alert-error"
          role="alert"
          style={{ marginBottom: 16 }}
        >
          {error}
        </div>
      )}
      {message && (
        <div className="alert" role="status" style={{ marginBottom: 16 }}>
          {message}
        </div>
      )}

      <section className="card" style={{ padding: 24, marginBottom: 22 }}>
        <h2 style={{ marginTop: 0 }}>Bank split rules</h2>
        <p>
          Configure percentages of the selected shipment’s calculated positive
          net profit. These are distribution instructions, not bank transfers.
          No default percentages or sample amounts are assumed.
        </p>
        {!banks.length && (
          <p>
            No active bank destinations are configured.{" "}
            <Link href="/finance/income-allocation">
              Add your verified bank destinations under Income Allocation
            </Link>
            , then return here.
          </p>
        )}
        <form onSubmit={saveRules}>
          <div style={{ overflowX: "auto" }}>
            <table
              className="data-table"
              style={{ width: "100%", minWidth: 620 }}
            >
              <thead>
                <tr>
                  <th>Bank destination</th>
                  <th>Profit share (%)</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {drafts.map((row, index) => (
                  <tr key={`${index}-${row.bankDestinationId}`}>
                    <td>
                      <select
                        aria-label={`Bank destination ${index + 1}`}
                        value={row.bankDestinationId}
                        onChange={(e) =>
                          updateDraft(
                            index,
                            "bankDestinationId",
                            e.target.value,
                          )
                        }
                        required
                        style={{ width: "100%", minWidth: 220 }}
                      >
                        <option value="">Select a configured bank</option>
                        {banks.map((bank) => (
                          <option key={bank.id} value={bank.id}>
                            {bank.name}
                            {bank.accountReference
                              ? ` · ${bank.accountReference}`
                              : ""}
                          </option>
                        ))}
                      </select>
                    </td>
                    <td>
                      <input
                        aria-label={`Profit percentage ${index + 1}`}
                        type="number"
                        min="0"
                        max="100"
                        step="0.0001"
                        value={row.percentage}
                        onChange={(e) =>
                          updateDraft(index, "percentage", e.target.value)
                        }
                        required
                        style={{ width: 130 }}
                      />
                    </td>
                    <td>
                      <button
                        type="button"
                        className="btn btn-small"
                        onClick={() =>
                          setDrafts((current) =>
                            current.filter((_, i) => i !== index),
                          )
                        }
                        disabled={busy || drafts.length <= 1}
                      >
                        Remove
                      </button>
                    </td>
                  </tr>
                ))}
                {!drafts.length && (
                  <tr>
                    <td colSpan={3}>Add a bank to configure profit sharing.</td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
          <div
            style={{
              display: "flex",
              alignItems: "center",
              flexWrap: "wrap",
              gap: 12,
              justifyContent: "space-between",
              marginTop: 16,
            }}
          >
            <strong>
              Total:{" "}
              <span
                style={{
                  color:
                    Math.abs(totalPercent - 100) < 0.0001
                      ? "#16794b"
                      : "#b42318",
                }}
              >
                {totalPercent.toFixed(4)}%
              </span>
            </strong>
            <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
              <button
                type="button"
                className="btn"
                disabled={busy || banks.length === 0}
                onClick={() =>
                  setDrafts((current) => [
                    ...current,
                    { bankDestinationId: "", percentage: "0" },
                  ])
                }
              >
                Add destination
              </button>
              <button
                className="btn btn-primary"
                type="submit"
                disabled={
                  busy ||
                  !banks.length ||
                  !drafts.length ||
                  Math.abs(totalPercent - 100) >= 0.0001
                }
              >
                {busy ? "Saving…" : "Save profit rules"}
              </button>
            </div>
          </div>
        </form>
      </section>

      <section className="card" style={{ padding: 24, marginBottom: 22 }}>
        <h2 style={{ marginTop: 0 }}>Create distribution instructions</h2>
        <p>
          The calculation uses billed shipment revenue minus supplier cost (using
          supplier payments only when no cost basis is recorded), other cost, and
          other expenses. A distribution is blocked if the result is zero/negative,
          currency is invalid, or active shares do not total exactly 100%.
        </p>
        <form
          onSubmit={allocate}
          style={{
            display: "flex",
            gap: 12,
            alignItems: "end",
            flexWrap: "wrap",
          }}
        >
          <label style={{ flex: "1 1 460px" }}>
            Shipment
            <select
              value={shipmentId}
              onChange={(e) => setShipmentId(e.target.value)}
              required
              style={{ display: "block", width: "100%", marginTop: 6 }}
            >
              <option value="">Select shipment</option>
              {shipments.map((shipment) => (
                <option key={shipment.id} value={shipment.id}>
                  {shipment.referenceCode} ·{" "}
                  {shipment.clientName || "Unassigned client"} ·{" "}
                  {shipment.currency || "Currency not set"}
                </option>
              ))}
            </select>
          </label>
          <button
            className="btn btn-primary"
            type="submit"
            disabled={busy || !rules.some((rule) => rule.active) || !shipmentId}
          >
            {busy ? "Processing…" : "Calculate & allocate profit"}
          </button>
        </form>
        <p style={{ fontSize: 13, opacity: 0.8, marginBottom: 0 }}>
          This records an instruction and audit snapshot only. It does not
          transfer funds to a bank account. Actual transfers require a
          bank-approved API/payment integration, credentials, authorization and
          reconciliation.
        </p>
      </section>

      {result && (
        <section className="card" style={{ padding: 24, marginBottom: 22 }}>
          <h2 style={{ marginTop: 0 }}>
            Distribution preview · {result.shipmentReference}
          </h2>
          <div
            style={{
              display: "grid",
              gridTemplateColumns: "repeat(auto-fit, minmax(180px, 1fr))",
              gap: 12,
              marginBottom: 16,
            }}
          >
            <Metric
              label="Billed revenue"
              value={money(result.revenue, result.currency)}
            />
            <Metric
              label="Supplier cost basis"
              value={money(result.supplierPaid, result.currency)}
            />
            <Metric
              label="Other costs & expenses"
              value={money(result.otherExpenses, result.currency)}
            />
            <Metric
              label="Net profit to distribute"
              value={money(result.netProfit, result.currency)}
            />
          </div>
          <div style={{ overflowX: "auto" }}>
            <table className="data-table" style={{ width: "100%" }}>
              <thead>
                <tr>
                  <th>Bank</th>
                  <th>Account reference</th>
                  <th>Share</th>
                  <th>Amount</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {result.allocations.map((item) => (
                  <tr key={item.bankDestinationId}>
                    <td>{item.bankName}</td>
                    <td>{item.accountReference || "Not configured"}</td>
                    <td>{Number(item.percentage).toFixed(2)}%</td>
                    <td>
                      <strong>{money(item.amount, item.currency)}</strong>
                    </td>
                    <td>Instruction only</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p>
            <strong>Audit run:</strong> {result.runId}
          </p>
        </section>
      )}

      <section className="card" style={{ padding: 24 }}>
        <h2 style={{ marginTop: 0 }}>Recent distribution runs</h2>
        {runs.length ? (
          <div style={{ overflowX: "auto" }}>
            <table className="data-table" style={{ width: "100%" }}>
              <thead>
                <tr>
                  <th>Created</th>
                  <th>Shipment</th>
                  <th>Net profit</th>
                  <th>Status</th>
                  <th>Run ID</th>
                </tr>
              </thead>
              <tbody>
                {runs.map((run) => (
                  <tr key={run.runId}>
                    <td>{new Date(run.createdAt).toLocaleString()}</td>
                    <td>{run.shipmentReference}</td>
                    <td>{money(run.netProfit, run.currency)}</td>
                    <td>
                      {run.transferStatus === "INSTRUCTIONS_ONLY"
                        ? "Instruction only · not transferred"
                        : run.transferStatus}
                    </td>
                    <td>
                      <code>{run.runId}</code>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <p>No distribution runs have been created yet.</p>
        )}
      </section>
    </main>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div
      style={{
        border: "1px solid var(--border, #ddd)",
        borderRadius: 10,
        padding: 14,
      }}
    >
      <div style={{ fontSize: 12, opacity: 0.7 }}>{label}</div>
      <strong style={{ display: "block", marginTop: 8, fontSize: 18 }}>
        {value}
      </strong>
    </div>
  );
}
