"use client";

import Link from "next/link";
import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth-context";
import {
  ApiError,
  FinanceBankDestination,
  FinanceIncomeAllocationRule,
  FinanceIncomeSource,
  financeIncomeConfigurationApi,
} from "@/lib/api-client";

type SourceForm = { code: string; name: string; description: string };
type BankForm = {
  code: string;
  name: string;
  accountReference: string;
  description: string;
};
type RuleForm = {
  incomeSourceId: string;
  bankDestinationId: string;
  percentage: string;
};
const emptySource: SourceForm = { code: "", name: "", description: "" };
const emptyBank: BankForm = {
  code: "",
  name: "",
  accountReference: "",
  description: "",
};
const emptyRule: RuleForm = {
  incomeSourceId: "",
  bankDestinationId: "",
  percentage: "",
};

function messageFor(error: unknown) {
  return error instanceof ApiError || error instanceof Error
    ? error.message
    : "Unable to complete the income allocation request.";
}

export default function IncomeAllocationPage() {
  const { accessToken, role, isLoading } = useAuth();
  const router = useRouter();
  const allowed = ["ADMIN", "MANAGER", "FINANCE"].includes(role || "");
  const [sources, setSources] = useState<FinanceIncomeSource[]>([]);
  const [banks, setBanks] = useState<FinanceBankDestination[]>([]);
  const [rules, setRules] = useState<FinanceIncomeAllocationRule[]>([]);
  const [sourceForm, setSourceForm] = useState<SourceForm>(emptySource);
  const [bankForm, setBankForm] = useState<BankForm>(emptyBank);
  const [editingBankId, setEditingBankId] = useState<string | null>(null);
  const [editingBankForm, setEditingBankForm] = useState<BankForm>(emptyBank);
  const [editingSourceId, setEditingSourceId] = useState<string | null>(null);
  const [editingSourceForm, setEditingSourceForm] =
    useState<SourceForm>(emptySource);
  const [ruleForm, setRuleForm] = useState<RuleForm>(emptyRule);
  const [editingRule, setEditingRule] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");

  const load = useCallback(async () => {
    if (!accessToken || !allowed) return;
    setBusy(true);
    setError("");
    try {
      const [nextSources, nextBanks, nextRules] = await Promise.all([
        financeIncomeConfigurationApi.sources(),
        financeIncomeConfigurationApi.banks(),
        financeIncomeConfigurationApi.rules(),
      ]);
      setSources(nextSources);
      setBanks(nextBanks);
      setRules(nextRules);
      setEditingRule(
        Object.fromEntries(
          nextRules.map((rule) => [rule.id, String(rule.percentage)]),
        ),
      );
      setRuleForm((current) => ({
        incomeSourceId: nextSources.some(
          (source) => source.id === current.incomeSourceId && source.active,
        )
          ? current.incomeSourceId
          : nextSources.find((source) => source.active)?.id || "",
        bankDestinationId: nextBanks.some(
          (bank) => bank.id === current.bankDestinationId && bank.active,
        )
          ? current.bankDestinationId
          : nextBanks.find((bank) => bank.active)?.id || "",
        percentage: current.percentage,
      }));
    } catch (e) {
      setError(messageFor(e));
    } finally {
      setBusy(false);
    }
  }, [accessToken, allowed]);

  useEffect(() => {
    if (!isLoading && (!accessToken || !allowed)) {
      router.replace("/aal-control-tower");
      return;
    }
    if (accessToken && allowed) void load();
  }, [accessToken, allowed, isLoading, load, router]);

  const activeSources = useMemo(
    () => sources.filter((item) => item.active),
    [sources],
  );
  const activeBanks = useMemo(
    () => banks.filter((item) => item.active),
    [banks],
  );

  async function submitSource(event: FormEvent) {
    event.preventDefault();
    setError("");
    setMessage("");
    setBusy(true);
    try {
      await financeIncomeConfigurationApi.createSource({
        code: sourceForm.code.trim().toUpperCase(),
        name: sourceForm.name.trim(),
        description: sourceForm.description.trim() || undefined,
      });
      setSourceForm(emptySource);
      setMessage("Income source created.");
      await load();
    } catch (e) {
      setError(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  async function submitBank(event: FormEvent) {
    event.preventDefault();
    setError("");
    setMessage("");
    setBusy(true);
    try {
      await financeIncomeConfigurationApi.createBank({
        code: bankForm.code.trim().toUpperCase(),
        name: bankForm.name.trim(),
        accountReference: bankForm.accountReference.trim() || undefined,
        description: bankForm.description.trim() || undefined,
      });
      setBankForm(emptyBank);
      setMessage("Bank destination created.");
      await load();
    } catch (e) {
      setError(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  async function submitRule(event: FormEvent) {
    event.preventDefault();
    setError("");
    setMessage("");
    const percentage = Number(ruleForm.percentage);
    if (
      !ruleForm.incomeSourceId ||
      !ruleForm.bankDestinationId ||
      !ruleForm.percentage.trim() ||
      !Number.isFinite(percentage) ||
      percentage < 0 ||
      percentage > 100
    ) {
      setError(
        "Choose an active source and bank, and enter a percentage from 0 to 100.",
      );
      return;
    }
    setBusy(true);
    try {
      await financeIncomeConfigurationApi.createRule({
        incomeSourceId: ruleForm.incomeSourceId,
        bankDestinationId: ruleForm.bankDestinationId,
        percentage,
      });
      setRuleForm((current) => ({ ...current, percentage: "" }));
      setMessage(
        "Income allocation rule created. Ensure active rules for the source total exactly 100% before recording a payment.",
      );
      await load();
    } catch (e) {
      setError(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  async function saveSourceEdit(source: FinanceIncomeSource) {
    setBusy(true);
    setError("");
    setMessage("");
    try {
      await financeIncomeConfigurationApi.updateSource(source.id, {
        code: editingSourceForm.code.trim().toUpperCase(),
        name: editingSourceForm.name.trim(),
        description: editingSourceForm.description.trim(),
        active: source.active,
      });
      setEditingSourceId(null);
      setMessage("Income source updated.");
      await load();
    } catch (e) {
      setError(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  async function saveBankEdit(bank: FinanceBankDestination) {
    setBusy(true);
    setError("");
    setMessage("");
    try {
      await financeIncomeConfigurationApi.updateBank(bank.id, {
        code: editingBankForm.code.trim().toUpperCase(),
        name: editingBankForm.name.trim(),
        accountReference: editingBankForm.accountReference.trim(),
        description: editingBankForm.description.trim(),
        active: bank.active,
      });
      setEditingBankId(null);
      setMessage("Bank destination updated.");
      await load();
    } catch (e) {
      setError(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  async function toggleSource(source: FinanceIncomeSource) {
    setBusy(true);
    setError("");
    setMessage("");
    try {
      await financeIncomeConfigurationApi.updateSource(source.id, {
        code: source.code,
        name: source.name,
        description: source.description || "",
        active: !source.active,
      });
      setMessage(
        `Income source ${source.active ? "deactivated" : "activated"}.`,
      );
      await load();
    } catch (e) {
      setError(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  async function toggleBank(bank: FinanceBankDestination) {
    setBusy(true);
    setError("");
    setMessage("");
    try {
      await financeIncomeConfigurationApi.updateBank(bank.id, {
        code: bank.code,
        name: bank.name,
        accountReference: bank.accountReference || "",
        description: bank.description || "",
        active: !bank.active,
      });
      setMessage(
        `Bank destination ${bank.active ? "deactivated" : "activated"}.`,
      );
      await load();
    } catch (e) {
      setError(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  async function saveRule(rule: FinanceIncomeAllocationRule) {
    const percentage = Number(editingRule[rule.id]);
    if (!Number.isFinite(percentage) || percentage < 0 || percentage > 100) {
      setError("Rule percentage must be between 0 and 100.");
      return;
    }
    setBusy(true);
    setError("");
    setMessage("");
    try {
      await financeIncomeConfigurationApi.updateRule(rule.id, {
        percentage,
        active: rule.active,
      });
      setMessage("Income allocation rule updated.");
      await load();
    } catch (e) {
      setError(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  async function toggleRule(rule: FinanceIncomeAllocationRule) {
    setBusy(true);
    setError("");
    setMessage("");
    try {
      await financeIncomeConfigurationApi.updateRule(rule.id, {
        percentage: Number(editingRule[rule.id] ?? rule.percentage),
        active: !rule.active,
      });
      setMessage(`Rule ${rule.active ? "deactivated" : "activated"}.`);
      await load();
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
          <h1 style={{ margin: 0 }}>Income Allocation & Bank Destinations</h1>
          <p style={{ maxWidth: 800, lineHeight: 1.6 }}>
            Manage real bank destinations, income sources and the percentage
            rules used to allocate recorded customer receipts. Changes affect
            future payment allocation snapshots; existing payment records are
            not rewritten.
          </p>
        </div>
        <Link className="btn" href="/finance/profit-allocation">
          Open profit distribution
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
        <h2 style={{ marginTop: 0 }}>Bank destinations</h2>
        <p>
          Use verified account references only. Do not enter online banking
          passwords, PINs, card numbers or API secrets here.
        </p>
        <form
          onSubmit={submitBank}
          style={{
            display: "grid",
            gridTemplateColumns: "repeat(auto-fit, minmax(190px, 1fr))",
            gap: 12,
            marginBottom: 20,
          }}
        >
          <label>
            Bank code
            <input
              required
              maxLength={80}
              value={bankForm.code}
              onChange={(e) =>
                setBankForm({ ...bankForm, code: e.target.value })
              }
            />
          </label>
          <label>
            Bank / destination name
            <input
              required
              maxLength={160}
              value={bankForm.name}
              onChange={(e) =>
                setBankForm({ ...bankForm, name: e.target.value })
              }
            />
          </label>
          <label>
            Account reference (masked or internal)
            <input
              maxLength={160}
              value={bankForm.accountReference}
              onChange={(e) =>
                setBankForm({ ...bankForm, accountReference: e.target.value })
              }
            />
          </label>
          <label>
            Description
            <input
              maxLength={500}
              value={bankForm.description}
              onChange={(e) =>
                setBankForm({ ...bankForm, description: e.target.value })
              }
            />
          </label>
          <div style={{ display: "flex", alignItems: "end" }}>
            <button className="btn btn-primary" disabled={busy} type="submit">
              {busy ? "Saving…" : "Add bank destination"}
            </button>
          </div>
        </form>
        <div style={{ overflowX: "auto" }}>
          <table
            className="data-table"
            style={{ width: "100%", minWidth: 700 }}
          >
            <thead>
              <tr>
                <th>Code</th>
                <th>Destination</th>
                <th>Account reference</th>
                <th>Status</th>
                <th>Action</th>
              </tr>
            </thead>
            <tbody>
              {banks.map((bank) => (
                <tr key={bank.id}>
                  <td>
                    {editingBankId === bank.id ? (
                      <input
                        aria-label="Edit bank code"
                        maxLength={80}
                        value={editingBankForm.code}
                        onChange={(e) =>
                          setEditingBankForm({
                            ...editingBankForm,
                            code: e.target.value,
                          })
                        }
                      />
                    ) : (
                      bank.code
                    )}
                  </td>
                  <td>
                    {editingBankId === bank.id ? (
                      <div style={{ display: "grid", gap: 6 }}>
                        <input
                          aria-label="Edit bank name"
                          maxLength={160}
                          value={editingBankForm.name}
                          onChange={(e) =>
                            setEditingBankForm({
                              ...editingBankForm,
                              name: e.target.value,
                            })
                          }
                        />
                        <input
                          aria-label="Edit bank description"
                          maxLength={500}
                          value={editingBankForm.description}
                          onChange={(e) =>
                            setEditingBankForm({
                              ...editingBankForm,
                              description: e.target.value,
                            })
                          }
                        />
                      </div>
                    ) : (
                      <>
                        {bank.name}
                        <div style={{ opacity: 0.7, fontSize: 12 }}>
                          {bank.description}
                        </div>
                      </>
                    )}
                  </td>
                  <td>
                    {editingBankId === bank.id ? (
                      <input
                        aria-label="Edit account reference"
                        maxLength={160}
                        value={editingBankForm.accountReference}
                        onChange={(e) =>
                          setEditingBankForm({
                            ...editingBankForm,
                            accountReference: e.target.value,
                          })
                        }
                      />
                    ) : (
                      bank.accountReference || "—"
                    )}
                  </td>
                  <td>{bank.active ? "Active" : "Inactive"}</td>
                  <td style={{ display: "flex", gap: 6, flexWrap: "wrap" }}>
                    {editingBankId === bank.id ? (
                      <>
                        <button
                          className="btn btn-small"
                          type="button"
                          disabled={busy}
                          onClick={() => void saveBankEdit(bank)}
                        >
                          Save
                        </button>
                        <button
                          className="btn btn-small"
                          type="button"
                          disabled={busy}
                          onClick={() => setEditingBankId(null)}
                        >
                          Cancel
                        </button>
                      </>
                    ) : (
                      <>
                        <button
                          className="btn btn-small"
                          type="button"
                          disabled={busy}
                          onClick={() => {
                            setEditingBankId(bank.id);
                            setEditingBankForm({
                              code: bank.code,
                              name: bank.name,
                              accountReference: bank.accountReference || "",
                              description: bank.description || "",
                            });
                          }}
                        >
                          Edit
                        </button>
                        <button
                          className="btn btn-small"
                          type="button"
                          disabled={busy}
                          onClick={() => void toggleBank(bank)}
                        >
                          {bank.active ? "Deactivate" : "Activate"}
                        </button>
                      </>
                    )}
                  </td>
                </tr>
              ))}
              {!banks.length && (
                <tr>
                  <td colSpan={5}>
                    No bank destinations configured yet. Add the real
                    destinations used by your finance team.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>

      <section className="card" style={{ padding: 24, marginBottom: 22 }}>
        <h2 style={{ marginTop: 0 }}>Income sources</h2>
        <form
          onSubmit={submitSource}
          style={{
            display: "grid",
            gridTemplateColumns: "repeat(auto-fit, minmax(190px, 1fr))",
            gap: 12,
            marginBottom: 20,
          }}
        >
          <label>
            Source code
            <input
              required
              maxLength={80}
              value={sourceForm.code}
              onChange={(e) =>
                setSourceForm({ ...sourceForm, code: e.target.value })
              }
            />
          </label>
          <label>
            Source name
            <input
              required
              maxLength={160}
              value={sourceForm.name}
              onChange={(e) =>
                setSourceForm({ ...sourceForm, name: e.target.value })
              }
            />
          </label>
          <label>
            Description
            <input
              maxLength={500}
              value={sourceForm.description}
              onChange={(e) =>
                setSourceForm({ ...sourceForm, description: e.target.value })
              }
            />
          </label>
          <div style={{ display: "flex", alignItems: "end" }}>
            <button className="btn btn-primary" disabled={busy} type="submit">
              {busy ? "Saving…" : "Add income source"}
            </button>
          </div>
        </form>
        <div style={{ overflowX: "auto" }}>
          <table
            className="data-table"
            style={{ width: "100%", minWidth: 600 }}
          >
            <thead>
              <tr>
                <th>Code</th>
                <th>Source</th>
                <th>Description</th>
                <th>Status</th>
                <th>Action</th>
              </tr>
            </thead>
            <tbody>
              {sources.map((source) => (
                <tr key={source.id}>
                  <td>
                    {editingSourceId === source.id ? (
                      <input
                        aria-label="Edit source code"
                        maxLength={80}
                        value={editingSourceForm.code}
                        onChange={(e) =>
                          setEditingSourceForm({
                            ...editingSourceForm,
                            code: e.target.value,
                          })
                        }
                      />
                    ) : (
                      source.code
                    )}
                  </td>
                  <td>
                    {editingSourceId === source.id ? (
                      <input
                        aria-label="Edit source name"
                        maxLength={160}
                        value={editingSourceForm.name}
                        onChange={(e) =>
                          setEditingSourceForm({
                            ...editingSourceForm,
                            name: e.target.value,
                          })
                        }
                      />
                    ) : (
                      source.name
                    )}
                  </td>
                  <td>
                    {editingSourceId === source.id ? (
                      <input
                        aria-label="Edit source description"
                        maxLength={500}
                        value={editingSourceForm.description}
                        onChange={(e) =>
                          setEditingSourceForm({
                            ...editingSourceForm,
                            description: e.target.value,
                          })
                        }
                      />
                    ) : (
                      source.description || "—"
                    )}
                  </td>
                  <td>{source.active ? "Active" : "Inactive"}</td>
                  <td style={{ display: "flex", gap: 6, flexWrap: "wrap" }}>
                    {editingSourceId === source.id ? (
                      <>
                        <button
                          className="btn btn-small"
                          type="button"
                          disabled={busy}
                          onClick={() => void saveSourceEdit(source)}
                        >
                          Save
                        </button>
                        <button
                          className="btn btn-small"
                          type="button"
                          disabled={busy}
                          onClick={() => setEditingSourceId(null)}
                        >
                          Cancel
                        </button>
                      </>
                    ) : (
                      <>
                        <button
                          className="btn btn-small"
                          type="button"
                          disabled={busy}
                          onClick={() => {
                            setEditingSourceId(source.id);
                            setEditingSourceForm({
                              code: source.code,
                              name: source.name,
                              description: source.description || "",
                            });
                          }}
                        >
                          Edit
                        </button>
                        <button
                          className="btn btn-small"
                          type="button"
                          disabled={busy}
                          onClick={() => void toggleSource(source)}
                        >
                          {source.active ? "Deactivate" : "Activate"}
                        </button>
                      </>
                    )}
                  </td>
                </tr>
              ))}
              {!sources.length && (
                <tr>
                  <td colSpan={5}>
                    No income sources configured yet. Create a source before
                    defining receipt-allocation rules.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>

      <section className="card" style={{ padding: 24, marginBottom: 22 }}>
        <h2 style={{ marginTop: 0 }}>Receipt allocation rules</h2>
        <p>
          Active rules for each income source must total exactly 100% before the
          payment is allocated. Configure each source independently; these rules
          allocate recorded receipts, not shipment profit.
        </p>
        <form
          onSubmit={submitRule}
          style={{
            display: "grid",
            gridTemplateColumns: "repeat(auto-fit, minmax(190px, 1fr))",
            gap: 12,
            marginBottom: 20,
          }}
        >
          <label>
            Income source
            <select
              required
              value={ruleForm.incomeSourceId}
              onChange={(e) =>
                setRuleForm({ ...ruleForm, incomeSourceId: e.target.value })
              }
            >
              <option value="">Select source</option>
              {activeSources.map((source) => (
                <option key={source.id} value={source.id}>
                  {source.name} ({source.code})
                </option>
              ))}
            </select>
          </label>
          <label>
            Bank destination
            <select
              required
              value={ruleForm.bankDestinationId}
              onChange={(e) =>
                setRuleForm({ ...ruleForm, bankDestinationId: e.target.value })
              }
            >
              <option value="">Select destination</option>
              {activeBanks.map((bank) => (
                <option key={bank.id} value={bank.id}>
                  {bank.name} ({bank.code})
                </option>
              ))}
            </select>
          </label>
          <label>
            Allocation percentage
            <input
              required
              type="number"
              min="0"
              max="100"
              step="0.0001"
              value={ruleForm.percentage}
              onChange={(e) =>
                setRuleForm({ ...ruleForm, percentage: e.target.value })
              }
              placeholder="Enter actual percentage"
            />
          </label>
          <div style={{ display: "flex", alignItems: "end" }}>
            <button
              className="btn btn-primary"
              disabled={busy || !activeSources.length || !activeBanks.length}
              type="submit"
            >
              Add allocation rule
            </button>
          </div>
        </form>
        <div style={{ overflowX: "auto" }}>
          <table
            className="data-table"
            style={{ width: "100%", minWidth: 800 }}
          >
            <thead>
              <tr>
                <th>Income source</th>
                <th>Bank destination</th>
                <th>Share (%)</th>
                <th>Status</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {rules.map((rule) => {
                const source = sources.find(
                  (item) => item.id === rule.incomeSourceId,
                );
                const bank = banks.find(
                  (item) => item.id === rule.bankDestinationId,
                );
                return (
                  <tr key={rule.id}>
                    <td>
                      {source
                        ? `${source.name} (${source.code})`
                        : "Unknown source"}
                    </td>
                    <td>
                      {bank
                        ? `${bank.name} (${bank.code})`
                        : "Unknown destination"}
                    </td>
                    <td>
                      <input
                        aria-label={`Percentage for ${rule.id}`}
                        type="number"
                        min="0"
                        max="100"
                        step="0.0001"
                        value={editingRule[rule.id] ?? String(rule.percentage)}
                        onChange={(e) =>
                          setEditingRule((current) => ({
                            ...current,
                            [rule.id]: e.target.value,
                          }))
                        }
                        style={{ width: 120 }}
                      />
                    </td>
                    <td>{rule.active ? "Active" : "Inactive"}</td>
                    <td style={{ display: "flex", gap: 8 }}>
                      <button
                        className="btn btn-small"
                        type="button"
                        disabled={busy}
                        onClick={() => void saveRule(rule)}
                      >
                        Save
                      </button>
                      <button
                        className="btn btn-small"
                        type="button"
                        disabled={busy}
                        onClick={() => void toggleRule(rule)}
                      >
                        {rule.active ? "Deactivate" : "Activate"}
                      </button>
                    </td>
                  </tr>
                );
              })}
              {!rules.length && (
                <tr>
                  <td colSpan={5}>
                    No receipt-allocation rules configured. Add rules after
                    creating the relevant sources and destinations.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>
      <p style={{ fontSize: 13, opacity: 0.8 }}>
        Bank account setup here stores destination metadata only. It does not
        connect to online banking or execute transfers. Every actual payment
        still needs to be recorded and reconciled through the billing workflow.
      </p>
    </main>
  );
}
