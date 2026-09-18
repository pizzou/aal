"use client";

import Link from "next/link";
import { useEffect, useMemo, useState } from "react";
import {
  ApiError,
  apiFetch,
  commercialApi,
  shipmentsApi,
  Shipment,
  financeApi,
} from "@/lib/api-client";

type Invoice = {
  id: string;
  tenantId?: string;
  invoiceNo: string;
  issueDate: string;
  client: string | null;
  shipmentId: string | null;
  currency: string;
  invoiceAmount: number;
  amountPaid: number;
  balance: number;
  dueDate: string | null;
  lastFollowUp: string | null;
  nextFollowUp: string | null;
  owner: string | null;
  notes: string | null;
  daysOverdue: number;
  agingBucket: string;
  status: string;
};

function money(value: number | null | undefined, currency = "USD") {
  return `${currency} ${(value ?? 0).toLocaleString(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`;
}

function statusClass(status: string) {
  if (status === "Paid") {
    return "status status-success";
  }

  if (status === "Overdue") {
    return "status status-danger";
  }

  if (status === "Partially Paid") {
    return "status status-warning";
  }

  return "status status-neutral";
}

function makeIdempotencyKey() {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return crypto.randomUUID();
  }

  return `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

export default function BillingPage() {
  const [invoices, setInvoices] = useState<Invoice[]>([]);
  const [shipments, setShipments] = useState<Shipment[]>([]);

  const [search, setSearch] = useState("");
  const [filter, setFilter] = useState<
    "ALL" | "OUTSTANDING" | "OVERDUE" | "PAID"
  >("ALL");

  const [invoiceShipment, setInvoiceShipment] = useState("");
  const [invoiceDueDate, setInvoiceDueDate] = useState("");
  const [invoiceOwner, setInvoiceOwner] = useState("");

  const [paymentInvoice, setPaymentInvoice] = useState<Invoice | null>(null);
  const [paymentAmount, setPaymentAmount] = useState("");
  const [paymentReference, setPaymentReference] = useState("");
  const [reconciliation, setReconciliation] = useState<any>(null);

  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");

  async function load() {
    setLoading(true);
    setError("");

    try {
      const [invoiceRows, shipmentRows, reconciliationRow] = await Promise.all([
        commercialApi.invoices(),
        shipmentsApi.list(),
        financeApi.reconcile(undefined, "USD"),
      ]);

      setInvoices(invoiceRows as Invoice[]);
      setShipments(shipmentRows.content);
      setReconciliation(reconciliationRow);
    } catch (exception) {
      setError(
        exception instanceof Error
          ? exception.message
          : "Unable to load billing data.",
      );
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load();
  }, []);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();

    return invoices.filter((invoice) => {
      const matchesSearch =
        !q ||
        [
          invoice.invoiceNo,
          invoice.client,
          invoice.currency,
          invoice.status,
          invoice.agingBucket,
        ]
          .filter(Boolean)
          .join(" ")
          .toLowerCase()
          .includes(q);

      const matchesFilter =
        filter === "ALL" ||
        (filter === "OUTSTANDING" && invoice.balance > 0) ||
        (filter === "OVERDUE" &&
          invoice.balance > 0 &&
          invoice.daysOverdue > 0) ||
        (filter === "PAID" && invoice.balance <= 0);

      return matchesSearch && matchesFilter;
    });
  }, [invoices, search, filter]);

  const totalInvoiced = invoices.reduce(
    (sum, invoice) => sum + (invoice.invoiceAmount || 0),
    0,
  );

  const totalCollected = invoices.reduce(
    (sum, invoice) => sum + (invoice.amountPaid || 0),
    0,
  );

  const totalOutstanding = invoices.reduce(
    (sum, invoice) => sum + (invoice.balance || 0),
    0,
  );

  const overdue = invoices.reduce(
    (sum, invoice) =>
      sum +
      (invoice.balance > 0 && invoice.daysOverdue > 0 ? invoice.balance : 0),
    0,
  );

  function shipmentForInvoice(invoice: Invoice) {
    return shipments.find((shipment) => shipment.id === invoice.shipmentId);
  }

  async function createInvoice() {
    setSaving(true);
    setError("");
    setSuccess("");

    try {
      if (!invoiceShipment) {
        throw new Error("Select a shipment before creating an invoice.");
      }

      if (!invoiceDueDate) {
        throw new Error("Invoice due date is required.");
      }

      await apiFetch<Invoice>(
        `/api/billing/shipments/${invoiceShipment}/invoice`,
        {
          method: "POST",
          body: JSON.stringify({
            dueDate: invoiceDueDate,
            owner: invoiceOwner.trim() || undefined,
          }),
        },
      );

      setInvoiceShipment("");
      setInvoiceOwner("");
      setInvoiceDueDate("");

      setSuccess("Invoice created successfully.");

      await load();
    } catch (exception) {
      setError(
        exception instanceof ApiError || exception instanceof Error
          ? exception.message
          : "Unable to create invoice.",
      );
    } finally {
      setSaving(false);
    }
  }

  async function recordPayment() {
    if (!paymentInvoice) {
      return;
    }

    setSaving(true);
    setError("");
    setSuccess("");

    try {
      const amount = Number(paymentAmount);

      if (!Number.isFinite(amount) || amount <= 0) {
        throw new Error("Payment amount must be greater than zero.");
      }

      if (amount > paymentInvoice.balance) {
        throw new Error("Payment cannot exceed the invoice balance.");
      }

      await apiFetch<Invoice>(
        `/api/billing/invoices/${paymentInvoice.id}/payments`,
        {
          method: "POST",
          headers: {
            "Idempotency-Key": makeIdempotencyKey(),
          },
          body: JSON.stringify({
            amount,
            reference: paymentReference.trim() || undefined,
          }),
        },
      );

      setPaymentInvoice(null);
      setPaymentAmount("");
      setPaymentReference("");

      setSuccess("Customer payment recorded successfully.");

      await load();
    } catch (exception) {
      setError(
        exception instanceof ApiError || exception instanceof Error
          ? exception.message
          : "Unable to record payment.",
      );
    } finally {
      setSaving(false);
    }
  }

  return (
    <main className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow">AAL / COMMERCIAL & FINANCE</div>

          <h1 className="page-title">Billing & Receivables</h1>

          <p className="page-subtitle">
            Invoice every shipment, record customer collections and maintain a
            live view of outstanding AAL receivables.
          </p>
        </div>

        <div className="actions">
          <Link className="btn" href="/new-shipment">
            <IconFallbackPlus />
            New shipment
          </Link>

          <Link className="btn btn-primary" href="/reports">
            Management reports
          </Link>
        </div>
      </div>

      {error && <div className="alert alert-error aal-alert">{error}</div>}

      {success && (
        <div className="alert alert-success aal-alert">{success}</div>
      )}

      {reconciliation && (
        <section className="card" style={{ marginBottom: 16 }}>
          <div className="page-head" style={{ marginBottom: 10 }}>
            <div>
              <h2 className="card-title">Ledger reconciliation</h2>
              <div className="card-muted">
                Accounts receivable ledger versus invoice balances.
              </div>
            </div>
            <span
              className={
                reconciliation.status === "PASS"
                  ? "status status-success"
                  : "status status-danger"
              }
            >
              {reconciliation.status}
            </span>
          </div>
          <div className="grid grid-4">
            <div className="kpi">
              <div className="kpi-label">Invoice AR</div>
              <div className="kpi-value" style={{ fontSize: 18 }}>
                {money(reconciliation.invoiceReceivable)}
              </div>
            </div>
            <div className="kpi">
              <div className="kpi-label">Ledger AR</div>
              <div className="kpi-value" style={{ fontSize: 18 }}>
                {money(reconciliation.ledgerReceivable)}
              </div>
            </div>
            <div className="kpi">
              <div className="kpi-label">Difference</div>
              <div className="kpi-value" style={{ fontSize: 18 }}>
                {money(reconciliation.receivableDifference)}
              </div>
            </div>
            <div className="kpi">
              <div className="kpi-label">Supplier posted</div>
              <div className="kpi-value" style={{ fontSize: 18 }}>
                {money(reconciliation.supplierPaymentsPosted)}
              </div>
            </div>
          </div>
        </section>
      )}

      <section className="finance-hero">
        <div>
          <span className="aal-hero-kicker">RECEIVABLES CONTROL</span>

          <h2>Know what AAL has billed, collected and is still owed.</h2>

          <p>
            Billing is connected directly to shipment records and customer
            payments.
          </p>
        </div>

        <div className="finance-hero-total">
          <span>OUTSTANDING RECEIVABLES</span>
          <strong>{money(totalOutstanding)}</strong>
          <small>
            {invoices.filter((invoice) => invoice.balance > 0).length} open
            invoices
          </small>
        </div>
      </section>

      <div className="grid grid-4 finance-kpis">
        <div className="card kpi premium-kpi">
          <div className="kpi-label">Total invoiced</div>
          <div className="kpi-value">{money(totalInvoiced)}</div>
          <div className="kpi-meta">Across all AAL invoices</div>
        </div>

        <div className="card kpi premium-kpi">
          <div className="kpi-label">Collected</div>
          <div className="kpi-value">{money(totalCollected)}</div>
          <div className="kpi-meta">Customer payments recorded</div>
        </div>

        <div className="card kpi premium-kpi">
          <div className="kpi-label">Outstanding</div>
          <div className="kpi-value">{money(totalOutstanding)}</div>
          <div className="kpi-meta">Current receivables</div>
        </div>

        <div className="card kpi premium-kpi danger-kpi">
          <div className="kpi-label">Overdue</div>
          <div className="kpi-value">{money(overdue)}</div>
          <div className="kpi-meta">Requires collection action</div>
        </div>
      </div>

      <div className="billing-layout">
        <section className="form-panel billing-create">
          <div className="section-heading">
            <div className="section-number">01</div>

            <div>
              <h2>Create invoice</h2>
              <p>
                Select a shipment that already contains a billable client
                revenue amount.
              </p>
            </div>
          </div>

          <div className="form-grid">
            <label className="field field-span-2">
              <span>Shipment</span>

              <select
                value={invoiceShipment}
                onChange={(event) => setInvoiceShipment(event.target.value)}
              >
                <option value="">Select shipment…</option>

                {shipments.map((shipment) => (
                  <option key={shipment.id} value={shipment.id}>
                    {shipment.referenceCode} —{" "}
                    {shipment.clientName || "No client"} —{" "}
                    {money(
                      shipment.amountBilledToClient ??
                        shipment.amountBilledToClient ??
                        0,
                      shipment.currency || "USD",
                    )}
                  </option>
                ))}
              </select>
            </label>

            <label className="field">
              <span>Due date</span>

              <input
                type="date"
                value={invoiceDueDate}
                onChange={(event) => setInvoiceDueDate(event.target.value)}
              />
            </label>

            <label className="field">
              <span>Owner</span>

              <input
                value={invoiceOwner}
                onChange={(event) => setInvoiceOwner(event.target.value)}
                placeholder="Responsible person"
              />
            </label>
          </div>

          {invoiceShipment && (
            <div className="selected-shipment">
              {(() => {
                const shipment = shipments.find(
                  (item) => item.id === invoiceShipment,
                );

                if (!shipment) {
                  return null;
                }

                return (
                  <>
                    <div>
                      <span>SHIPMENT</span>
                      <strong>{shipment.referenceCode}</strong>
                    </div>

                    <div>
                      <span>CLIENT</span>
                      <strong>{shipment.clientName || "—"}</strong>
                    </div>

                    <div>
                      <span>BILLABLE</span>
                      <strong>
                        {money(
                          shipment.amountBilledToClient ??
                            shipment.amountBilledToClient ??
                            0,
                          shipment.currency || "USD",
                        )}
                      </strong>
                    </div>

                    <div>
                      <span>CHARGEABLE WEIGHT</span>
                      <strong>
                        {shipment.chargeableWeightKg ?? shipment.weightKg ?? 0}{" "}
                        KG
                      </strong>
                    </div>
                  </>
                );
              })()}
            </div>
          )}

          <button
            className="btn btn-primary btn-large"
            type="button"
            onClick={createInvoice}
            disabled={saving}
          >
            {saving ? "Creating invoice…" : "Create invoice"}
          </button>
        </section>

        <section className="form-panel collection-panel">
          <div className="section-heading">
            <div className="section-number">02</div>

            <div>
              <h2>Record collection</h2>
              <p>Apply a customer payment to an existing invoice.</p>
            </div>
          </div>

          {paymentInvoice ? (
            <div className="payment-dialog">
              <div className="payment-invoice">
                <span>{paymentInvoice.invoiceNo}</span>
                <strong>
                  {money(paymentInvoice.balance, paymentInvoice.currency)}
                </strong>
                <small>remaining balance</small>
              </div>

              <label className="field">
                <span>Payment amount</span>
                <input
                  type="number"
                  min="0"
                  max={paymentInvoice.balance}
                  step="0.01"
                  value={paymentAmount}
                  onChange={(event) => setPaymentAmount(event.target.value)}
                  placeholder="0.00"
                />
              </label>

              <label className="field">
                <span>Payment reference</span>
                <input
                  value={paymentReference}
                  onChange={(event) => setPaymentReference(event.target.value)}
                  placeholder="Bank / mobile money / receipt reference"
                />
              </label>

              <div className="actions">
                <button
                  className="btn btn-primary"
                  type="button"
                  onClick={recordPayment}
                  disabled={saving}
                >
                  {saving ? "Recording…" : "Record payment"}
                </button>

                <button
                  className="btn"
                  type="button"
                  onClick={() => setPaymentInvoice(null)}
                >
                  Cancel
                </button>
              </div>
            </div>
          ) : (
            <div className="collection-empty">
              <div className="collection-empty-icon">
                <span>₣</span>
              </div>

              <strong>Select an invoice below</strong>

              <p>
                Use the payment action in the receivables register to record a
                collection.
              </p>
            </div>
          )}
        </section>
      </div>

      <section className="card billing-register">
        <div className="register-head">
          <div>
            <div className="eyebrow">ACCOUNTS RECEIVABLE</div>

            <h2 className="card-title">Invoice register</h2>

            <p className="card-muted">
              Every invoice remains linked to the shipment that generated it.
            </p>
          </div>

          <div className="register-tools">
            <input
              className="search"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="Search invoice or client…"
            />

            <div className="filter-group">
              {(
                [
                  ["ALL", "All"],
                  ["OUTSTANDING", "Outstanding"],
                  ["OVERDUE", "Overdue"],
                  ["PAID", "Paid"],
                ] as const
              ).map(([value, label]) => (
                <button
                  key={value}
                  type="button"
                  className={
                    filter === value ? "filter-button active" : "filter-button"
                  }
                  onClick={() => setFilter(value)}
                >
                  {label}
                </button>
              ))}
            </div>
          </div>
        </div>

        <div className="table-wrap">
          <table className="table finance-table">
            <thead>
              <tr>
                <th>Invoice</th>
                <th>Shipment</th>
                <th>Client</th>
                <th>Issue</th>
                <th>Due</th>
                <th>Amount</th>
                <th>Paid</th>
                <th>Balance</th>
                <th>Status</th>
                <th />
              </tr>
            </thead>

            <tbody>
              {loading && (
                <tr>
                  <td colSpan={10} className="empty">
                    Loading billing register…
                  </td>
                </tr>
              )}

              {!loading &&
                filtered.map((invoice) => {
                  const shipment = shipmentForInvoice(invoice);

                  return (
                    <tr key={invoice.id}>
                      <td>
                        <strong>{invoice.invoiceNo}</strong>

                        <div className="card-muted">{invoice.currency}</div>
                      </td>

                      <td>
                        {shipment ? (
                          <Link
                            className="table-link"
                            href={`/shipments/${shipment.id}`}
                          >
                            {shipment.referenceCode}
                          </Link>
                        ) : (
                          "—"
                        )}
                      </td>

                      <td>{invoice.client || "—"}</td>

                      <td>{invoice.issueDate || "—"}</td>

                      <td>{invoice.dueDate || "—"}</td>

                      <td>{money(invoice.invoiceAmount, invoice.currency)}</td>

                      <td>{money(invoice.amountPaid, invoice.currency)}</td>

                      <td>
                        <strong>
                          {money(invoice.balance, invoice.currency)}
                        </strong>
                      </td>

                      <td>
                        <span className={statusClass(invoice.status)}>
                          {invoice.status}
                        </span>

                        {invoice.daysOverdue > 0 && (
                          <div className="aging-note">
                            {invoice.daysOverdue} days
                          </div>
                        )}
                      </td>

                      <td>
                        {invoice.balance > 0 && (
                          <button
                            type="button"
                            className="btn btn-small"
                            onClick={() => setPaymentInvoice(invoice)}
                          >
                            Receive payment
                          </button>
                        )}
                      </td>
                    </tr>
                  );
                })}

              {!loading && !filtered.length && (
                <tr>
                  <td colSpan={10} className="empty">
                    No invoices match the selected view.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>
    </main>
  );
}

function IconFallbackPlus() {
  return <span className="button-plus">+</span>;
}
