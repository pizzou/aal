"use client";

import { FormEvent, useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth-context";
import {
  ApiError,
  QuoteRecord,
  TaskRecord,
  ExpenseRecord,
  commercialApi,
} from "@/lib/api-client";

function today() {
  return new Date().toISOString().slice(0, 10);
}
function id(prefix: string) {
  return `${prefix}-${new Date().getFullYear()}-${Date.now().toString().slice(-7)}`;
}
function msg(e: unknown) {
  return e instanceof ApiError || e instanceof Error
    ? e.message
    : "Request failed";
}

export default function CommercialPage() {
  const { accessToken, isLoading, role } = useAuth();
  const router = useRouter();
  const [quotes, setQuotes] = useState<QuoteRecord[]>([]);
  const [tasks, setTasks] = useState<TaskRecord[]>([]);
  const [expenses, setExpenses] = useState<ExpenseRecord[]>([]);
  const [tab, setTab] = useState<"quotes" | "tasks" | "expenses">("quotes");
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");
  const [busy, setBusy] = useState(false);
  const [shareQuoteId, setShareQuoteId] = useState<string | null>(null);
  const [shareEmail, setShareEmail] = useState("");
  const [quote, setQuote] = useState({
    quoteId: id("AAL-QT"),
    quoteDate: today(),
    client: "",
    route: "",
    serviceType: "Air Freight",
    commodity: "",
    chargeableWeightKg: "",
    supplierCost: "",
    otherCost: "",
    markupPercent: "",
    quotedAmount: "",
    validUntil: "",
    owner: "",
    followUpDate: "",
    notes: "",
  });
  const canExpense =
    role === "ADMIN" || role === "MANAGER" || role === "FINANCE";

  useEffect(() => {
    if (!isLoading && !accessToken) router.replace("/login");
  }, [isLoading, accessToken, router]);

  async function load() {
    if (!accessToken) return;
    setError("");
    try {
      const [q, t] = await Promise.all([
        commercialApi.quotes(),
        commercialApi.tasks(),
      ]);
      setQuotes(q);
      setTasks(t);
      if (canExpense) setExpenses(await commercialApi.expenses());
    } catch (e) {
      setError(msg(e));
    }
  }
  useEffect(() => {
    void load();
  }, [accessToken, role]);

  const openQuotes = useMemo(
    () =>
      quotes.filter(
        (q) =>
          !["WON", "LOST", "EXPIRED"].includes((q.status || "").toUpperCase()),
      ).length,
    [quotes],
  );
  const pipeline = useMemo(
    () => quotes.reduce((n, q) => n + (q.quotedAmount || 0), 0),
    [quotes],
  );
  const openTasks = useMemo(
    () =>
      tasks.filter(
        (t) =>
          !["COMPLETED", "CANCELLED"].includes((t.status || "").toUpperCase()),
      ).length,
    [tasks],
  );

  async function createQuote(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    setSuccess("");
    try {
      await commercialApi.createQuote({
        ...quote,
        chargeableWeightKg: Number(quote.chargeableWeightKg || 0),
        supplierCost: Number(quote.supplierCost || 0),
        otherCost: Number(quote.otherCost || 0),
        markupPercent: Number(quote.markupPercent || 0),
        quotedAmount: Number(quote.quotedAmount || 0),
        status: "DRAFT",
        pricingMode: "RULES_BASED",
      });
      setQuote({
        ...quote,
        quoteId: id("AAL-QT"),
        client: "",
        route: "",
        commodity: "",
        supplierCost: "",
        otherCost: "",
        markupPercent: "",
        quotedAmount: "",
      });
      setSuccess("Quotation created.");
      await load();
    } catch (e) {
      setError(msg(e));
    } finally {
      setBusy(false);
    }
  }

  async function shareQuote(quoteId: string) {
    setBusy(true);
    setError("");
    setSuccess("");
    setShareQuoteId(quoteId);
    try {
      const result = await commercialApi.shareQuote(
        quoteId,
        shareEmail.trim() || undefined,
      );
      setSuccess(
        `Quotation ${result.quoteReference} was sent to ${result.recipientEmail}.`,
      );
      setShareEmail("");
    } catch (e) {
      setError(msg(e));
    } finally {
      setBusy(false);
      setShareQuoteId(null);
    }
  }

  async function createTask(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    setBusy(true);
    setError("");
    setSuccess("");
    try {
      await commercialApi.createTask({
        taskId: id("AAL-TSK"),
        createdDate: today(),
        department: String(f.get("department") || "Operations"),
        task: String(f.get("task") || ""),
        priority: String(f.get("priority") || "Medium"),
        owner: String(f.get("owner") || ""),
        dueDate: String(f.get("dueDate") || "") || null,
        status: "NOT_STARTED",
        notes: String(f.get("notes") || ""),
      });
      e.currentTarget.reset();
      setSuccess("Operational task created.");
      await load();
    } catch (e) {
      setError(msg(e));
    } finally {
      setBusy(false);
    }
  }

  async function createExpense(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    setBusy(true);
    setError("");
    setSuccess("");
    try {
      await commercialApi.createExpense({
        expenseId: id("AAL-EXP"),
        expenseDate: String(f.get("expenseDate") || today()),
        type: String(f.get("type") || "Operating"),
        category: String(f.get("category") || "General"),
        vendorPayee: String(f.get("vendor") || ""),
        description: String(f.get("description") || ""),
        currency: String(f.get("currency") || "USD"),
        originalAmount: Number(f.get("amount") || 0),
        exchangeRateToUsd: 1,
        paymentMethod: String(f.get("paymentMethod") || "BANK"),
        status: String(f.get("status") || "PENDING"),
      });
      e.currentTarget.reset();
      setSuccess("Expense recorded.");
      await load();
    } catch (e) {
      setError(msg(e));
    } finally {
      setBusy(false);
    }
  }

  if (isLoading || !accessToken) return null;

  return (
    <main className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow">COMMERCIAL CONTROL</div>
          <h1 className="page-title">Sales & Commercial Operations</h1>
          <p className="page-subtitle">
            Quotations, operational follow-ups and controlled expenses. Customer
            master data is maintained only in the canonical Customer 360
            workspace.
          </p>
        </div>
      </div>
      {error && (
        <div className="alert alert-error" style={{ marginBottom: 14 }}>
          {error}
        </div>
      )}
      {success && (
        <div className="alert alert-success" style={{ marginBottom: 14 }}>
          {success}
        </div>
      )}
      <section className="grid grid-3">
        <div className="card kpi">
          <div className="kpi-label">Open quotations</div>
          <div className="kpi-value">{openQuotes}</div>
        </div>
        <div className="card kpi">
          <div className="kpi-label">Quoted pipeline</div>
          <div className="kpi-value" style={{ fontSize: 22 }}>
            {pipeline.toLocaleString()}
          </div>
        </div>
        <div className="card kpi">
          <div className="kpi-label">Open actions</div>
          <div className="kpi-value">{openTasks}</div>
        </div>
      </section>
      <div className="filter-group" style={{ marginTop: 16 }}>
        <button
          className={`filter-btn ${tab === "quotes" ? "active" : ""}`}
          onClick={() => setTab("quotes")}
        >
          Quotations
        </button>
        <button
          className={`filter-btn ${tab === "tasks" ? "active" : ""}`}
          onClick={() => setTab("tasks")}
        >
          Tasks
        </button>
        {canExpense && (
          <button
            className={`filter-btn ${tab === "expenses" ? "active" : ""}`}
            onClick={() => setTab("expenses")}
          >
            Expenses
          </button>
        )}
      </div>

      {tab === "quotes" && (
        <section className="grid grid-2" style={{ marginTop: 14 }}>
          <div className="card">
            <h2 className="card-title">Create quotation</h2>
            <form className="form-grid" onSubmit={createQuote}>
              {(
                [
                  ["quoteId", "Quote ID"],
                  ["quoteDate", "Quote date"],
                  ["client", "Client"],
                  ["route", "Route"],
                  ["serviceType", "Service type"],
                  ["commodity", "Commodity"],
                  ["chargeableWeightKg", "Chargeable weight kg"],
                  ["supplierCost", "Supplier cost"],
                  ["otherCost", "Other cost"],
                  ["markupPercent", "Markup %"],
                  ["quotedAmount", "Quoted amount"],
                  ["validUntil", "Valid until"],
                  ["owner", "Owner"],
                  ["followUpDate", "Follow-up date"],
                ] as const
              ).map(([k, l]) => (
                <div className="field" key={k}>
                  <label>{l}</label>
                  <input
                    type={
                      k.toLowerCase().includes("date") ||
                      k === "validUntil" ||
                      k === "followUpDate"
                        ? "date"
                        : [
                              "chargeableWeightKg",
                              "supplierCost",
                              "otherCost",
                              "markupPercent",
                              "quotedAmount",
                            ].includes(k)
                          ? "number"
                          : "text"
                    }
                    step="any"
                    value={quote[k]}
                    onChange={(e) =>
                      setQuote({ ...quote, [k]: e.target.value })
                    }
                    required={[
                      "quoteId",
                      "quoteDate",
                      "client",
                      "quotedAmount",
                    ].includes(k)}
                  />
                </div>
              ))}
              <div className="field field-span-2">
                <label>Notes</label>
                <textarea
                  value={quote.notes}
                  onChange={(e) =>
                    setQuote({ ...quote, notes: e.target.value })
                  }
                />
              </div>
              <button className="btn btn-primary" disabled={busy}>
                {busy ? "Saving…" : "Create quotation"}
              </button>
            </form>
          </div>
          <div className="card">
            <h2 className="card-title">Quotation pipeline</h2>
            <div className="field" style={{ marginBottom: 12 }}>
              <label>Optional recipient email</label>
              <input
                type="email"
                placeholder="Uses the customer's master email when blank"
                value={shareEmail}
                onChange={(e) => setShareEmail(e.target.value)}
              />
            </div>
            <div className="table-wrap">
              <table className="table">
                <thead>
                  <tr>
                    <th>Quote</th>
                    <th>Client</th>
                    <th>Value</th>
                    <th>Profit</th>
                    <th>Status</th>
                    <th>Customer delivery</th>
                  </tr>
                </thead>
                <tbody>
                  {quotes.map((q) => (
                    <tr key={q.id}>
                      <td>
                        <strong>{q.quoteId}</strong>
                        <small>{q.serviceType}</small>
                      </td>
                      <td>{q.client || "—"}</td>
                      <td>{(q.quotedAmount || 0).toLocaleString()}</td>
                      <td>{(q.expectedProfit || 0).toLocaleString()}</td>
                      <td>
                        <span className="status status-neutral">
                          {q.status}
                        </span>
                      </td>
                      <td>
                        <button
                          className="btn btn-primary"
                          disabled={busy}
                          onClick={() => shareQuote(q.id)}
                          type="button"
                        >
                          {shareQuoteId === q.id
                            ? "Sending…"
                            : "Send to customer"}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </section>
      )}

      {tab === "tasks" && (
        <section className="grid grid-2" style={{ marginTop: 14 }}>
          <div className="card">
            <h2 className="card-title">Create operational task</h2>
            <form className="form-grid" onSubmit={createTask}>
              <div className="field">
                <label>Department</label>
                <input name="department" defaultValue="Operations" />
              </div>
              <div className="field">
                <label>Owner</label>
                <input name="owner" />
              </div>
              <div className="field field-span-2">
                <label>Task</label>
                <input name="task" required />
              </div>
              <div className="field">
                <label>Priority</label>
                <select name="priority" defaultValue="Medium">
                  <option>Low</option>
                  <option>Medium</option>
                  <option>High</option>
                  <option>Urgent</option>
                </select>
              </div>
              <div className="field">
                <label>Due date</label>
                <input name="dueDate" type="date" />
              </div>
              <div className="field field-span-2">
                <label>Notes</label>
                <textarea name="notes" />
              </div>
              <button className="btn btn-primary" disabled={busy}>
                {busy ? "Saving…" : "Create task"}
              </button>
            </form>
          </div>
          <div className="card">
            <h2 className="card-title">Action queue</h2>
            {tasks.slice(0, 40).map((t) => (
              <div className="metric-row" key={t.id}>
                <span>
                  <strong>{t.task}</strong>
                  <small>
                    {t.owner || "Unassigned"} · {t.dueDate || "No due date"}
                  </small>
                </span>
                <span
                  className={
                    t.overdue ? "status status-danger" : "status status-neutral"
                  }
                >
                  {t.status}
                </span>
              </div>
            ))}
          </div>
        </section>
      )}

      {tab === "expenses" && canExpense && (
        <section className="grid grid-2" style={{ marginTop: 14 }}>
          <div className="card">
            <h2 className="card-title">Record expense</h2>
            <form className="form-grid" onSubmit={createExpense}>
              <div className="field">
                <label>Date</label>
                <input name="expenseDate" type="date" defaultValue={today()} />
              </div>
              <div className="field">
                <label>Amount</label>
                <input
                  name="amount"
                  type="number"
                  min="0.01"
                  step="0.01"
                  required
                />
              </div>
              <div className="field">
                <label>Currency</label>
                <input name="currency" defaultValue="USD" />
              </div>
              <div className="field">
                <label>Category</label>
                <input name="category" defaultValue="General" />
              </div>
              <div className="field">
                <label>Vendor / payee</label>
                <input name="vendor" />
              </div>
              <div className="field">
                <label>Payment method</label>
                <input name="paymentMethod" defaultValue="BANK" />
              </div>
              <div className="field">
                <label>Status</label>
                <select name="status" defaultValue="PENDING">
                  <option>PENDING</option>
                  <option>APPROVED</option>
                </select>
              </div>
              <div className="field field-span-2">
                <label>Description</label>
                <textarea name="description" />
              </div>
              <button className="btn btn-primary" disabled={busy}>
                {busy ? "Saving…" : "Record expense"}
              </button>
            </form>
          </div>
          <div className="card">
            <h2 className="card-title">Expense register</h2>
            {expenses.slice(0, 40).map((x) => (
              <div className="metric-row" key={x.id}>
                <span>
                  <strong>{x.category || x.type || "Expense"}</strong>
                  <small>
                    {x.vendorPayee || "—"} · {x.expenseDate}
                  </small>
                </span>
                <strong>
                  {x.originalAmount.toLocaleString()} {x.currency}
                </strong>
              </div>
            ))}
          </div>
        </section>
      )}
    </main>
  );
}
