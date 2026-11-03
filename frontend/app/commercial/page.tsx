"use client";

import { FormEvent, Suspense, useEffect, useMemo, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import CustomerWorkspace from "@/components/CustomerWorkspace";
import { useAuth } from "@/lib/auth-context";
import {
  ApiError,
  QuoteRecord,
  TaskRecord,
  ExpenseRecord,
  ClientRecord,
  commercialApi,
} from "@/lib/api-client";

function today() {
  return new Date().toISOString().slice(0, 10);
}
function msg(e: unknown) {
  return e instanceof ApiError || e instanceof Error
    ? e.message
    : "Request failed";
}

export default function CommercialPage() {
  return (
    <Suspense
      fallback={
        <main className="page">
          <p>Loading customers and sales…</p>
        </main>
      }
    >
      <CommercialWorkspace />
    </Suspense>
  );
}

function CommercialWorkspace() {
  const searchParams = useSearchParams();
  if (searchParams.get("tab") === "customers") return <CustomerWorkspace />;
  return <CommercialQuotesWorkspace />;
}

function CommercialQuotesWorkspace() {
  const { accessToken, isLoading, role } = useAuth();
  const router = useRouter();
  const searchParams = useSearchParams();
  const preselectedClientId = searchParams.get("clientId") || "";
  const [quotes, setQuotes] = useState<QuoteRecord[]>([]);
  const [tasks, setTasks] = useState<TaskRecord[]>([]);
  const [expenses, setExpenses] = useState<ExpenseRecord[]>([]);
  const [clients, setClients] = useState<ClientRecord[]>([]);
  const [selectedClientId, setSelectedClientId] = useState("");
  const [tab, setTab] = useState<"quotes" | "tasks" | "expenses">("quotes");
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");
  const [busy, setBusy] = useState(false);
  const [shareQuoteId, setShareQuoteId] = useState<string | null>(null);
  const [convertingQuoteId, setConvertingQuoteId] = useState<string | null>(
    null,
  );
  const [shareEmail, setShareEmail] = useState("");
  const [sendImmediately, setSendImmediately] = useState(true);
  const [quote, setQuote] = useState({
    quoteId: "",
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
    customerEmail: "",
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
      const [quotesResult, tasksResult, clientsResult, expensesResult] =
        await Promise.allSettled([
          commercialApi.quotes(),
          commercialApi.tasks(),
          commercialApi.clients(),
          canExpense
            ? commercialApi.expenses()
            : Promise.resolve([] as ExpenseRecord[]),
        ]);
      const failures: string[] = [];
      if (quotesResult.status === "fulfilled") setQuotes(quotesResult.value);
      else failures.push("quotations");
      if (tasksResult.status === "fulfilled") setTasks(tasksResult.value);
      else failures.push("follow-up tasks");
      if (clientsResult.status === "fulfilled")
        setClients(
          clientsResult.value.filter(
            (client) =>
              (client.clientStatus || "ACTIVE").toUpperCase() === "ACTIVE",
          ),
        );
      else failures.push("customers");
      if (expensesResult.status === "fulfilled")
        setExpenses(expensesResult.value);
      else if (canExpense) failures.push("expenses");
      if (failures.length)
        setError(
          `The following sections could not be loaded: ${failures.join(", ")}. Other data remains available; retry when connected.`,
        );
    } catch (e) {
      setError(msg(e));
    }
  }
  useEffect(() => {
    void load();
  }, [accessToken, role]);

  useEffect(() => {
    if (!preselectedClientId || selectedClientId || !clients.length) return;
    const chosen = clients.find((client) => client.id === preselectedClientId);
    if (!chosen) return;
    setSelectedClientId(chosen.id);
    setQuote((current) => ({
      ...current,
      client: chosen.clientCompany || "",
      customerEmail: chosen.email || "",
    }));
  }, [preselectedClientId, clients, selectedClientId]);

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
      const created = await commercialApi.createQuote({
        ...quote,
        chargeableWeightKg: Number(quote.chargeableWeightKg || 0),
        supplierCost: Number(quote.supplierCost || 0),
        otherCost: Number(quote.otherCost || 0),
        markupPercent: Number(quote.markupPercent || 0),
        quotedAmount: Number(quote.quotedAmount || 0),
        status: "DRAFT",
        pricingMode: "RULES_BASED",
        customerEmail: quote.customerEmail || undefined,
      });
      // Creating a quote and emailing it are separate operations. If email fails
      // after persistence, do not suggest re-creating the quote (duplicate risk).
      if (sendImmediately) {
        try {
          const sent = await commercialApi.shareQuote(
            created.id,
            quote.customerEmail.trim() || undefined,
          );
          setSuccess(
            `Quotation ${sent.quoteReference} created and sent to ${sent.recipientEmail}. Await customer acceptance before creating a shipment.`,
          );
        } catch (sendError) {
          setSuccess(
            `Quotation ${created.quoteId} was saved, but sending failed: ${msg(sendError)}. Use Send for acceptance in the quotation pipeline; do not create another quotation.`,
          );
        }
      } else {
        setSuccess(
          `Quotation ${created.quoteId} saved as a draft. Send it to the customer for acceptance.`,
        );
      }
      setSelectedClientId("");
      setQuote({
        ...quote,
        quoteId: "",
        client: "",
        route: "",
        commodity: "",
        supplierCost: "",
        otherCost: "",
        markupPercent: "",
        quotedAmount: "",
        customerEmail: "",
      });
      if (!sendImmediately) setSuccess("Quotation created as a draft.");
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

  async function convertToShipment(quote: QuoteRecord) {
    if (
      !window.confirm(
        `Create shipment from accepted quotation ${quote.quoteId}? This uses the approved price and will not create a duplicate on retry.`,
      )
    )
      return;
    // Existing quotations may have free-text routes. Ask for locations only
    // when the quote does not contain a recognizable origin → destination.
    let route: { origin: string; destination: string } | undefined;
    const parsed = (quote.route || "").split(/\s*(?:→|->|\bTO\b)\s*/i);
    if (parsed.length < 2 || !parsed[0]?.trim() || !parsed[1]?.trim()) {
      const origin = window.prompt(
        "Shipment origin (as accepted on the quote):",
        "",
      );
      if (origin === null) return;
      const destination = window.prompt(
        "Shipment destination (as accepted on the quote):",
        "",
      );
      if (destination === null) return;
      if (!origin.trim() || !destination.trim()) {
        setError(
          "Both shipment origin and destination are required for conversion.",
        );
        return;
      }
      route = { origin: origin.trim(), destination: destination.trim() };
    }
    setConvertingQuoteId(quote.id);
    setBusy(true);
    setError("");
    try {
      const converted = await commercialApi.convertAcceptedQuote(
        quote.id,
        route,
      );
      router.push(`/shipments/${encodeURIComponent(converted.shipmentId)}`);
    } catch (e) {
      setError(msg(e));
      await load();
    } finally {
      setConvertingQuoteId(null);
      setBusy(false);
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
        taskId: undefined,
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
        expenseId: undefined,
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
          <h1 className="page-title">Step 2 · Customer quotations</h1>
          <p className="page-subtitle">
            Start with an existing customer. Issue a quotation, obtain customer
            acceptance, then convert the accepted quote into one shipment.
            Invoices are created only after delivery and POD.
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
      <div className="actions" style={{ marginBottom: 14 }}>
        <button
          type="button"
          className="btn"
          onClick={() => router.push("/commercial?tab=customers")}
        >
          ← Step 1: Customers
        </button>
        <button
          type="button"
          className="btn"
          onClick={() => router.push("/shipments")}
        >
          Step 3: Shipment register →
        </button>
      </div>
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
                  ["quoteId", "Quote number (system assigned)"],
                  ["quoteDate", "Quote date"],
                  ["client", "Client"],
                  ["customerEmail", "Customer email"],
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
                  <label>
                    {k === "client"
                      ? "Assign to customer"
                      : k === "route"
                        ? "Route (Origin → Destination)"
                        : l}
                  </label>
                  {k === "client" ? (
                    <select
                      value={selectedClientId}
                      onChange={(e) => {
                        const chosen = clients.find(
                          (client) => client.id === e.target.value,
                        );
                        setSelectedClientId(e.target.value);
                        setQuote({
                          ...quote,
                          client: chosen?.clientCompany || "",
                          customerEmail: chosen?.email || "",
                        });
                      }}
                      required
                    >
                      <option value="">Select an existing customer…</option>
                      {clients.map((client) => (
                        <option key={client.id} value={client.id}>
                          {client.clientCompany || "Unnamed customer"} ·{" "}
                          {client.clientId}
                        </option>
                      ))}
                    </select>
                  ) : (
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
                      readOnly={k === "quoteId"}
                      placeholder={
                        k === "quoteId"
                          ? "Assigned automatically when saved"
                          : undefined
                      }
                      onChange={(e) => {
                        if (k !== "quoteId")
                          setQuote({ ...quote, [k]: e.target.value });
                      }}
                      required={["quoteDate", "quotedAmount"].includes(k)}
                    />
                  )}
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
              <label className="checkbox-row">
                <input
                  type="checkbox"
                  checked={sendImmediately}
                  onChange={(e) => setSendImmediately(e.target.checked)}
                />
                <span>
                  <strong>Send quotation to customer immediately</strong>
                  <small>
                    Uses the email attached to the selected Customer 360 record.
                    You can change it before sending.
                  </small>
                </span>
              </label>
              <button className="btn btn-primary" disabled={busy}>
                {busy
                  ? "Saving…"
                  : sendImmediately
                    ? "Create & send quotation"
                    : "Create quotation"}
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
                    <th>Customer delivery / next step</th>
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
                        {q.status?.toUpperCase() === "WON" ? (
                          <button
                            className="btn btn-primary"
                            type="button"
                            disabled={busy}
                            onClick={() => convertToShipment(q)}
                          >
                            {convertingQuoteId === q.id
                              ? "Converting…"
                              : "Create shipment →"}
                          </button>
                        ) : q.status?.toUpperCase() === "CONVERTED" ? (
                          <a className="btn" href="/shipments">
                            Shipment created · Open shipment register →
                          </a>
                        ) : ["LOST", "EXPIRED"].includes(
                            q.status?.toUpperCase() || "",
                          ) ? (
                          <span className="card-muted">
                            Closed · Not convertible
                          </span>
                        ) : (
                          <button
                            className="btn"
                            disabled={busy}
                            type="button"
                            onClick={() => shareQuote(q.id)}
                          >
                            {shareQuoteId === q.id
                              ? "Sending…"
                              : "Send for acceptance"}
                          </button>
                        )}
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
