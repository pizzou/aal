"use client";

import { FormEvent, useEffect, useMemo, useState } from "react";
import {
  ApiError,
  FinanceBankDestination,
  FinanceIncomeAllocationRule,
  FinanceIncomeSource,
  financeIncomeConfigurationApi,
} from "@/lib/api-client";
import { useAuth } from "@/lib/auth-context";
import { useRouter } from "next/navigation";

function messageFor(error: unknown): string {
  return error instanceof ApiError || error instanceof Error
    ? error.message
    : "Unable to complete the finance configuration request.";
}

export default function IncomeAllocationPage() {
  const { accessToken, role, isLoading } = useAuth();
  const router = useRouter();

  const [sources, setSources] = useState<FinanceIncomeSource[]>([]);
  const [banks, setBanks] = useState<FinanceBankDestination[]>([]);
  const [rules, setRules] = useState<FinanceIncomeAllocationRule[]>([]);
  const [sourceCode, setSourceCode] = useState("");
  const [sourceName, setSourceName] = useState("");
  const [sourceDescription, setSourceDescription] = useState("");
  const [bankCode, setBankCode] = useState("");
  const [bankName, setBankName] = useState("");
  const [accountReference, setAccountReference] = useState("");
  const [ruleSource, setRuleSource] = useState("");
  const [ruleBank, setRuleBank] = useState("");
  const [rulePercentage, setRulePercentage] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");

  const allowed = ["ADMIN", "MANAGER", "FINANCE"].includes(role || "");

  async function load() {
    if (!accessToken) return;
    setBusy(true);
    try {
      const [nextSources, nextBanks, nextRules] = await Promise.all([
        financeIncomeConfigurationApi.sources(),
        financeIncomeConfigurationApi.banks(),
        financeIncomeConfigurationApi.rules(),
      ]);
      setSources(nextSources);
      setBanks(nextBanks);
      setRules(nextRules);
      setMessage("");
    } catch (error) {
      setMessage(messageFor(error));
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

  const totals = useMemo(() => {
    const bySource = new Map<string, number>();
    rules.filter((rule) => rule.active).forEach((rule) => {
      bySource.set(
        rule.incomeSourceId,
        (bySource.get(rule.incomeSourceId) || 0) + Number(rule.percentage),
      );
    });
    return bySource;
  }, [rules]);

  async function createSource(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setMessage("");
    try {
      await financeIncomeConfigurationApi.createSource({
        code: sourceCode,
        name: sourceName,
        description: sourceDescription || undefined,
      });
      setSourceCode("");
      setSourceName("");
      setSourceDescription("");
      await load();
    } catch (error) {
      setMessage(messageFor(error));
      setBusy(false);
    }
  }

  async function createBank(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setMessage("");
    try {
      await financeIncomeConfigurationApi.createBank({
        code: bankCode,
        name: bankName,
        accountReference: accountReference || undefined,
      });
      setBankCode("");
      setBankName("");
      setAccountReference("");
      await load();
    } catch (error) {
      setMessage(messageFor(error));
      setBusy(false);
    }
  }

  async function createRule(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setMessage("");
    try {
      await financeIncomeConfigurationApi.createRule({
        incomeSourceId: ruleSource,
        bankDestinationId: ruleBank,
        percentage: Number(rulePercentage),
      });
      setRulePercentage("");
      await load();
    } catch (error) {
      setMessage(messageFor(error));
      setBusy(false);
    }
  }

  if (isLoading || !accessToken || !allowed) return null;

  return (
    <main className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow">FINANCE CONTROL</div>
          <h1 className="page-title">Income allocation</h1>
          <p className="page-subtitle">
            Configure income sources, bank destinations and allocation rules
            without maintaining a second spreadsheet ledger.
          </p>
        </div>
        <button className="btn" onClick={() => void load()} disabled={busy}>
          {busy ? "Working…" : "Refresh"}
        </button>
      </div>

      {message && <div className="alert alert-info" style={{ marginBottom: 16 }}>{message}</div>}

      <section className="grid grid-2" style={{ alignItems: "start" }}>
        <form className="card" onSubmit={createSource}>
          <div className="eyebrow">INCOME CLASSIFICATION</div>
          <h2>Income sources</h2>
          <p className="page-subtitle">Create the classifications staff can select when recording a canonical customer payment.</p>
          <div className="form-grid">
            <label>Code<input value={sourceCode} onChange={(e) => setSourceCode(e.target.value)} required maxLength={80} /></label>
            <label>Name<input value={sourceName} onChange={(e) => setSourceName(e.target.value)} required maxLength={160} /></label>
            <label style={{ gridColumn: "1 / -1" }}>Description<input value={sourceDescription} onChange={(e) => setSourceDescription(e.target.value)} maxLength={500} /></label>
          </div>
          <button className="btn btn-primary" disabled={busy}>Add income source</button>
          <div className="stack-list" style={{ marginTop: 18 }}>
            {sources.map((source) => <div className="list-row" key={source.id}><div><strong>{source.name}</strong><small>{source.code}{source.active ? "" : " · inactive"}</small></div></div>)}
            {!sources.length && <div className="dashboard-empty">No income sources configured yet.</div>}
          </div>
        </form>

        <form className="card" onSubmit={createBank}>
          <div className="eyebrow">RECEIPT DESTINATION</div>
          <h2>Bank destinations</h2>
          <p className="page-subtitle">Store configurable destination labels/references. No workbook bank names are preloaded.</p>
          <div className="form-grid">
            <label>Code<input value={bankCode} onChange={(e) => setBankCode(e.target.value)} required maxLength={80} /></label>
            <label>Name<input value={bankName} onChange={(e) => setBankName(e.target.value)} required maxLength={160} /></label>
            <label style={{ gridColumn: "1 / -1" }}>Account/reference<input value={accountReference} onChange={(e) => setAccountReference(e.target.value)} maxLength={160} /></label>
          </div>
          <button className="btn btn-primary" disabled={busy}>Add bank destination</button>
          <div className="stack-list" style={{ marginTop: 18 }}>
            {banks.map((bank) => <div className="list-row" key={bank.id}><div><strong>{bank.name}</strong><small>{bank.code}{bank.accountReference ? ` · ${bank.accountReference}` : ""}</small></div></div>)}
            {!banks.length && <div className="dashboard-empty">No bank destinations configured yet.</div>}
          </div>
        </form>
      </section>

      <section className="card" style={{ marginTop: 20 }}>
        <div className="eyebrow">AUTOMATION RULES</div>
        <h2>Allocation rules</h2>
        <p className="page-subtitle">
          Rules are copied to a payment as percentages when a source is selected.
          The payment amount remains the canonical amount; allocated income is calculated from the percentage.
        </p>
        <form onSubmit={createRule} className="form-grid" style={{ marginBottom: 20 }}>
          <label>Income source<select value={ruleSource} onChange={(e) => setRuleSource(e.target.value)} required><option value="">Select source</option>{sources.filter((x) => x.active).map((x) => <option value={x.id} key={x.id}>{x.name} ({x.code})</option>)}</select></label>
          <label>Bank destination<select value={ruleBank} onChange={(e) => setRuleBank(e.target.value)} required><option value="">Select bank</option>{banks.filter((x) => x.active).map((x) => <option value={x.id} key={x.id}>{x.name} ({x.code})</option>)}</select></label>
          <label>Percentage<input type="number" min="0" max="100" step="0.01" value={rulePercentage} onChange={(e) => setRulePercentage(e.target.value)} required /></label>
          <div style={{ display: "flex", alignItems: "end" }}><button className="btn btn-primary" disabled={busy}>Add rule</button></div>
        </form>

        <div className="dashboard-table-wrap">
          <table className="dashboard-table">
            <thead><tr><th>Income source</th><th>Bank destination</th><th>Percentage</th><th>Active total</th><th>Status</th></tr></thead>
            <tbody>
              {rules.map((rule) => {
                const source = sources.find((x) => x.id === rule.incomeSourceId);
                const bank = banks.find((x) => x.id === rule.bankDestinationId);
                const total = totals.get(rule.incomeSourceId) || 0;
                return <tr key={rule.id}>
                  <td>{source?.name || rule.incomeSourceId}</td>
                  <td>{bank?.name || rule.bankDestinationId}</td>
                  <td>{Number(rule.percentage).toFixed(2)}%</td>
                  <td>{total.toFixed(2)}%</td>
                  <td>{rule.active ? (Math.abs(total - 100) < 0.001 ? "Ready" : "Incomplete") : "Inactive"}</td>
                </tr>;
              })}
              {!rules.length && <tr><td colSpan={5} className="dashboard-empty">No allocation rules configured.</td></tr>}
            </tbody>
          </table>
        </div>
      </section>
    </main>
  );
}
