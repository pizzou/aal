"use client";

import Link from "next/link";
import { useEffect, useMemo, useState } from "react";
import {
  ApiError,
  apiFetch,
  downloadApiFile,
  fetchApiBlob,
  commercialApi,
  shipmentsApi,
  Shipment,
  financeApi,
  FinanceIncomeSource,
  financeIncomeConfigurationApi,
} from "@/lib/api-client";

type TaxPreview = {
  shipmentId: string;
  currency: string;
  taxName: string;
  taxCode: string;
  taxJurisdictionCode: string;
  taxRate: number;
  taxInclusive: boolean;
  subtotalAmount: number;
  taxAmount: number;
  invoiceAmount: number;
  effectiveDate: string;
  taxRegistrationNo?: string | null;
};

type Invoice = {
  id: string;
  tenantId?: string;
  invoiceNo: string;
  issueDate: string;
  client: string | null;
  shipmentId: string | null;
  currency: string;
  invoiceAmount: number;
  subtotalAmount?: number;
  taxRate?: number;
  taxAmount?: number;
  taxCode?: string | null;
  taxInclusive?: boolean;
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

function currencyDigits(currency = "USD") {
  try {
    return new Intl.NumberFormat(undefined, {
      style: "currency",
      currency: currency.trim().toUpperCase(),
    }).resolvedOptions().maximumFractionDigits;
  } catch {
    return 2;
  }
}

function money(value: number | null | undefined, currency = "USD") {
  const code = currency.trim().toUpperCase() || "USD";
  const digits = currencyDigits(code);
  const amount = new Intl.NumberFormat(undefined, {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  }).format(value ?? 0);
  return `${code} ${amount}`;
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
  const [requestedShipmentId, setRequestedShipmentId] = useState("");
  const [workflowAwbNumber, setWorkflowAwbNumber] = useState("");
  const [workflowAwbStatus, setWorkflowAwbStatus] = useState("");
  const [createdInvoice, setCreatedInvoice] = useState<Invoice | null>(null);
  const [invoices, setInvoices] = useState<Invoice[]>([]);
  const [shipments, setShipments] = useState<Shipment[]>([]);

  const [search, setSearch] = useState("");
  const [filter, setFilter] = useState<
    "ALL" | "OUTSTANDING" | "OVERDUE" | "PAID"
  >("ALL");

  const [invoiceShipment, setInvoiceShipment] = useState("");
  const [invoiceDueDate, setInvoiceDueDate] = useState("");
  const [invoiceOwner, setInvoiceOwner] = useState("");
  const [taxPreview, setTaxPreview] = useState<TaxPreview | null>(null);
  const [taxPreviewError, setTaxPreviewError] = useState("");
  const [taxPreviewLoading, setTaxPreviewLoading] = useState(false);

  const [paymentInvoice, setPaymentInvoice] = useState<Invoice | null>(null);
  const [paymentAmount, setPaymentAmount] = useState("");
  const [paymentReference, setPaymentReference] = useState("");
  const [incomeSources, setIncomeSources] = useState<FinanceIncomeSource[]>([]);
  const [incomeSourceId, setIncomeSourceId] = useState("");
  const [reconciliation, setReconciliation] = useState<any>(null);
  const [lastReceipt, setLastReceipt] = useState<{
    paymentId: string;
    receiptNo: string | null;
  } | null>(null);

  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");

  async function load() {
    setLoading(true);
    setError("");

    // Keep each billing panel independently useful. A failed reconciliation or
    // optional income-source request must never hide canonical invoice rows.
    const [invoiceResult, shipmentResult, reconciliationResult, sourceResult] =
      await Promise.allSettled([
        commercialApi.invoices(),
        shipmentsApi.list(),
        financeApi.reconcile(undefined, "USD"),
        financeIncomeConfigurationApi.sources(),
      ]);

    const failures: string[] = [];
    if (invoiceResult.status === "fulfilled") {
      setInvoices(invoiceResult.value as Invoice[]);
    } else {
      failures.push(
        `Invoices: ${invoiceResult.reason instanceof Error ? invoiceResult.reason.message : "could not be loaded"}`,
      );
    }
    if (shipmentResult.status === "fulfilled") {
      setShipments(shipmentResult.value.content);
    } else {
      failures.push(
        `Shipments: ${shipmentResult.reason instanceof Error ? shipmentResult.reason.message : "could not be loaded"}`,
      );
    }
    if (reconciliationResult.status === "fulfilled") {
      setReconciliation(reconciliationResult.value);
    } else {
      setReconciliation(null);
      failures.push(
        `Reconciliation: ${reconciliationResult.reason instanceof Error ? reconciliationResult.reason.message : "unavailable"}`,
      );
    }
    if (sourceResult.status === "fulfilled") {
      setIncomeSources(sourceResult.value);
    } else {
      // Payment recording remains usable without the optional classification.
      setIncomeSources([]);
    }
    if (failures.length) setError(failures.join(" · "));
    setLoading(false);
  }

  useEffect(() => {
    const query = new URLSearchParams(window.location.search);
    setRequestedShipmentId(query.get("shipmentId") || "");
    setWorkflowAwbNumber(query.get("awbNumber") || "");
    setWorkflowAwbStatus(query.get("awbStatus") || "");
  }, []);

  useEffect(() => {
    void load();
  }, []);

  useEffect(() => {
    if (
      requestedShipmentId &&
      shipments.some((item) => item.id === requestedShipmentId)
    ) {
      setInvoiceShipment(requestedShipmentId);
    }
  }, [requestedShipmentId, shipments]);

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

  // Keep receivables separated by invoice currency. Adding RWF, USD and EUR
  // values together without conversion would produce a misleading financial KPI.
  const totalsByCurrency = useMemo(() => {
    const totals: Record<
      string,
      {
        invoiced: number;
        collected: number;
        outstanding: number;
        overdue: number;
      }
    > = {};
    for (const invoice of invoices) {
      const status = (invoice.status || "").trim().toUpperCase();
      if (["DRAFT", "VOID", "CANCELLED", "CANCELED"].includes(status)) continue;
      const currency = (invoice.currency || "USD").trim().toUpperCase();
      const current = (totals[currency] ??= {
        invoiced: 0,
        collected: 0,
        outstanding: 0,
        overdue: 0,
      });
      current.invoiced += Number(invoice.invoiceAmount) || 0;
      current.collected += Number(invoice.amountPaid) || 0;
      current.outstanding += Math.max(Number(invoice.balance) || 0, 0);
      if (
        (Number(invoice.balance) || 0) > 0 &&
        (Number(invoice.daysOverdue) || 0) > 0
      ) {
        current.overdue += Number(invoice.balance) || 0;
      }
    }
    return Object.entries(totals)
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([currency, values]) => ({ currency, ...values }));
  }, [invoices]);

  function formatTotals(
    metric: "invoiced" | "collected" | "outstanding" | "overdue",
  ) {
    return (
      totalsByCurrency
        .map((entry) => money(entry[metric], entry.currency))
        .join(" · ") || "—"
    );
  }

  const selectedInvoiceShipment = shipments.find(
    (shipment) => shipment.id === invoiceShipment,
  );

  useEffect(() => {
    let cancelled = false;
    setTaxPreview(null);
    setTaxPreviewError("");
    if (!invoiceShipment) {
      setTaxPreviewLoading(false);
      return () => {
        cancelled = true;
      };
    }
    setTaxPreviewLoading(true);
    apiFetch<TaxPreview>(
      `/api/billing/shipments/${invoiceShipment}/invoice/vat-preview`,
      { method: "GET" },
    )
      .then((preview) => {
        if (!cancelled) setTaxPreview(preview);
      })
      .catch((exception) => {
        if (!cancelled)
          setTaxPreviewError(
            exception instanceof Error
              ? exception.message
              : "Unable to calculate VAT from the configured server tax rule.",
          );
      })
      .finally(() => {
        if (!cancelled) setTaxPreviewLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [invoiceShipment]);

  function shipmentForInvoice(invoice: Invoice) {
    return shipments.find((shipment) => shipment.id === invoice.shipmentId);
  }

  async function createInvoice() {
    setSaving(true);
    setError("");
    setSuccess("");

    if (!invoiceShipment) {
      setError("Select a shipment before creating an invoice.");
      setSaving(false);
      return;
    }
    if (!invoiceDueDate) {
      setError("Invoice due date is required.");
      setSaving(false);
      return;
    }
    if (!taxPreview) {
      setError(
        "The server has not confirmed the applicable 18% VAT rule and totals. Resolve the tax configuration before creating this invoice.",
      );
      setSaving(false);
      return;
    }

    // Invoice creation is a financial transaction, not a PDF-viewer action.
    // Keep the user on the billing register until the API confirms the saved
    // invoice. This avoids navigating to a blank about:blank tab before the
    // backend has even returned a result.
    try {
      const created = await apiFetch<Invoice>(
        `/api/billing/shipments/${invoiceShipment}/invoice`,
        {
          method: "POST",
          body: JSON.stringify({
            dueDate: invoiceDueDate,
            owner: invoiceOwner.trim() || undefined,
            taxJurisdictionCode: taxPreview.taxJurisdictionCode,
            taxCode: taxPreview.taxCode,
          }),
        },
      );

      if (!created?.id) {
        throw new Error(
          "The billing API did not return an invoice ID. Check the server response before retrying to avoid duplicate billing.",
        );
      }

      setCreatedInvoice(created);
      setInvoiceShipment("");
      setInvoiceOwner("");
      setInvoiceDueDate("");
      setSuccess(
        `Invoice ${created.invoiceNo || ""} is available in the invoice register. Use View / Print or Download PDF when you are ready.`,
      );
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
            incomeSourceId: incomeSourceId || undefined,
          }),
        },
      );

      let receipt: { paymentId: string; receiptNo: string | null } | null =
        null;
      try {
        receipt = await apiFetch<{
          paymentId: string;
          receiptNo: string | null;
        }>(
          `/api/finance/documents/invoices/${paymentInvoice.id}/latest-receipt`,
        );
      } catch {
        // The collection itself has already succeeded; receipt lookup is a
        // convenience step and must not make a successful payment look failed.
      }

      setLastReceipt(receipt);
      setPaymentInvoice(null);
      setPaymentAmount("");
      setPaymentReference("");
      setIncomeSourceId("");

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

  async function viewInvoice(invoice: Invoice) {
    const tab = window.open("about:blank", "_blank");
    if (!tab) {
      setError(
        "Your browser blocked the invoice PDF tab. Allow pop-ups for this AAL site and try again.",
      );
      return;
    }
    tab.document.title = `Invoice ${invoice.invoiceNo}`;
    tab.document.body.innerHTML =
      "<p style='font-family: sans-serif; padding: 24px'>Loading invoice PDF…</p>";
    try {
      const blob = await fetchApiBlob(
        `/api/finance/documents/invoices/${invoice.id}/pdf`,
      );
      const objectUrl = URL.createObjectURL(blob);
      tab.location.href = objectUrl;
      window.setTimeout(() => URL.revokeObjectURL(objectUrl), 5 * 60 * 1000);
    } catch (exception) {
      tab.close();
      setError(
        exception instanceof Error
          ? exception.message
          : "Unable to open invoice PDF.",
      );
    }
  }

  async function downloadInvoice(invoice: Invoice) {
    try {
      await downloadApiFile(
        `/api/finance/documents/invoices/${invoice.id}/pdf`,
        `invoice-${invoice.invoiceNo}.pdf`,
      );
    } catch (exception) {
      setError(
        exception instanceof Error
          ? exception.message
          : "Unable to download invoice.",
      );
    }
  }

  async function emailInvoice(invoice: Invoice) {
    const recipientEmail = window.prompt(
      "Client email (leave blank to use the shipment notification email):",
      "",
    );
    if (recipientEmail === null) return;
    try {
      await apiFetch<void>(
        `/api/finance/documents/invoices/${invoice.id}/email`,
        {
          method: "POST",
          body: JSON.stringify({
            recipientEmail: recipientEmail.trim() || undefined,
          }),
        },
      );
      setSuccess(
        `Invoice ${invoice.invoiceNo} was accepted for email delivery.`,
      );
    } catch (exception) {
      setError(
        exception instanceof Error
          ? exception.message
          : "Unable to email invoice.",
      );
    }
  }

  async function downloadReceipt(paymentId: string) {
    try {
      await downloadApiFile(
        `/api/finance/documents/payments/${paymentId}/receipt/pdf`,
        "payment-receipt.pdf",
      );
    } catch (exception) {
      setError(
        exception instanceof Error
          ? exception.message
          : "Unable to download receipt.",
      );
    }
  }

  async function emailReceipt(paymentId: string) {
    const recipientEmail = window.prompt(
      "Client email (leave blank to use the shipment notification email):",
      "",
    );
    if (recipientEmail === null) return;
    try {
      await apiFetch<void>(
        `/api/finance/documents/payments/${paymentId}/receipt/email`,
        {
          method: "POST",
          body: JSON.stringify({
            recipientEmail: recipientEmail.trim() || undefined,
          }),
        },
      );
      setSuccess("Payment receipt was accepted for email delivery.");
    } catch (exception) {
      setError(
        exception instanceof Error
          ? exception.message
          : "Unable to email receipt.",
      );
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
          <Link className="btn" href="/billing/hardening">
            Financial hardening
          </Link>
        </div>
      </div>

      {error && <div className="alert alert-error aal-alert">{error}</div>}

      {success && (
        <div className="alert alert-success aal-alert">{success}</div>
      )}

      {workflowAwbNumber && (
        <section className="workflow-completion-card" role="status">
          <div className="workflow-completion-icon" aria-hidden="true">
            ✓
          </div>
          <div className="workflow-completion-copy">
            <strong>Air waybill step completed · {workflowAwbNumber}</strong>
            <p>
              {workflowAwbStatus.toUpperCase() === "DRAFT"
                ? "The AAL House AWB draft was generated from shipment data. Confirm any missing legal parties, airport codes, weights and carrier MAWB before tendering it to an airline."
                : "An AWB record is already available for this shipment."}
            </p>
            {requestedShipmentId && (
              <Link
                className="table-link"
                href={`/shipments/${requestedShipmentId}`}
              >
                Open shipment documents and download the AWB PDF
              </Link>
            )}
          </div>
          <span
            className={`status ${workflowAwbStatus.toUpperCase() === "DRAFT" ? "status-warning" : "status-success"}`}
          >
            {workflowAwbStatus || "ON FILE"}
          </span>
        </section>
      )}

      {createdInvoice && (
        <section
          className="workflow-completion-card invoice-created-panel"
          role="status"
        >
          <div className="workflow-completion-icon" aria-hidden="true">
            ✓
          </div>
          <div className="workflow-completion-copy">
            <strong>Invoice {createdInvoice.invoiceNo} ready</strong>
            <p>
              {createdInvoice.client || "Customer"} · Subtotal{" "}
              {money(
                createdInvoice.subtotalAmount ?? createdInvoice.invoiceAmount,
                createdInvoice.currency,
              )}
              {createdInvoice.taxCode || (createdInvoice.taxRate ?? 0) > 0
                ? ` · VAT ${createdInvoice.taxRate ?? 18}% ${money(createdInvoice.taxAmount, createdInvoice.currency)} (${createdInvoice.taxInclusive ? "included" : "added"})`
                : ""}{" "}
              · Total due{" "}
              {money(createdInvoice.invoiceAmount, createdInvoice.currency)} ·
              Due {createdInvoice.dueDate || "on receipt"}. The invoice is
              saved; PDF actions do not affect invoice creation.
            </p>
            {createdInvoice.shipmentId && (
              <Link
                className="table-link"
                href={`/shipments/${createdInvoice.shipmentId}`}
              >
                Open linked shipment
              </Link>
            )}
          </div>
          <div className="actions">
            <button
              type="button"
              className="btn btn-small"
              onClick={() => void viewInvoice(createdInvoice)}
            >
              View / Print
            </button>
            <button
              type="button"
              className="btn btn-small btn-primary"
              onClick={() => void downloadInvoice(createdInvoice)}
            >
              Download PDF
            </button>
          </div>
        </section>
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
          <strong>{formatTotals("outstanding")}</strong>
          <small>
            {invoices.filter((invoice) => invoice.balance > 0).length} open
            invoices
          </small>
        </div>
      </section>

      <div className="grid grid-4 finance-kpis">
        <div className="card kpi premium-kpi">
          <div className="kpi-label">Total invoiced</div>
          <div className="kpi-value">{formatTotals("invoiced")}</div>
          <div className="kpi-meta">Across all AAL invoices</div>
        </div>

        <div className="card kpi premium-kpi">
          <div className="kpi-label">Collected</div>
          <div className="kpi-value">{formatTotals("collected")}</div>
          <div className="kpi-meta">Customer payments recorded</div>
        </div>

        <div className="card kpi premium-kpi">
          <div className="kpi-label">Outstanding</div>
          <div className="kpi-value">{formatTotals("outstanding")}</div>
          <div className="kpi-meta">Current receivables</div>
        </div>

        <div className="card kpi premium-kpi danger-kpi">
          <div className="kpi-label">Overdue</div>
          <div className="kpi-value">{formatTotals("overdue")}</div>
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
                      shipment.amountBilledToClient &&
                        shipment.amountBilledToClient > 0
                        ? shipment.amountBilledToClient
                        : (shipment.clientRevenue ?? 0),
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
          <p className="form-hint">
            VAT is selected automatically from the tenant's default invoice
            jurisdiction. The server requires exactly one active,
            effective-dated 18% VAT rule; tax codes are not entered manually. If
            configuration is missing or ambiguous, invoice creation is blocked
            and a finance administrator must correct it.
          </p>

          {invoiceShipment && selectedInvoiceShipment && (
            <section
              className="card"
              aria-label="Server-calculated invoice tax preview"
              style={{ marginTop: 16 }}
            >
              <div className="section-heading">
                <div className="section-number">VAT</div>
                <div>
                  <h3>Invoice tax preview</h3>
                  <p>
                    Calculated by the backend using the selected effective tax
                    rule; the issued invoice remains the final record.
                  </p>
                </div>
              </div>
              {taxPreviewLoading && (
                <p className="form-hint">
                  Calculating using the configured tax rule…
                </p>
              )}
              {taxPreviewError && (
                <div className="form-hint" role="alert">
                  <p style={{ marginTop: 0 }}>
                    Tax preview unavailable: {taxPreviewError}
                  </p>
                  <p style={{ marginBottom: 0 }}>
                    A finance administrator can review the active default
                    jurisdiction and 18% VAT rule in{" "}
                    <Link href="/billing/hardening">Financial hardening</Link>.
                    Invoice creation remains blocked until the server confirms a
                    valid rule.
                  </p>
                </div>
              )}
              {!taxPreview && !taxPreviewLoading && !taxPreviewError && (
                <p className="form-hint">
                  Select a shipment to calculate VAT using the configured rule.
                </p>
              )}
              {taxPreview && (
                <>
                  <div className="grid grid-2" style={{ gap: 12 }}>
                    <div className="card" style={{ padding: 14 }}>
                      <div className="kpi-label">Subtotal excluding VAT</div>
                      <strong>
                        {money(taxPreview.subtotalAmount, taxPreview.currency)}
                      </strong>
                      <div className="kpi-meta" style={{ marginTop: 8 }}>
                        VAT ({taxPreview.taxRate}%)
                      </div>
                      <strong>
                        {money(taxPreview.taxAmount, taxPreview.currency)}
                      </strong>
                      <div className="kpi-meta" style={{ marginTop: 8 }}>
                        Total including VAT
                      </div>
                      <strong>
                        {money(taxPreview.invoiceAmount, taxPreview.currency)}
                      </strong>
                    </div>
                    <div className="card" style={{ padding: 14 }}>
                      <div className="kpi-label">Configured tax treatment</div>
                      <strong>
                        {taxPreview.taxInclusive
                          ? "VAT included in entered amount"
                          : "VAT added to entered amount"}
                      </strong>
                      <div className="kpi-meta" style={{ marginTop: 8 }}>
                        Tax rule
                      </div>
                      <strong>
                        {taxPreview.taxName} ({taxPreview.taxCode})
                      </strong>
                      <div className="kpi-meta" style={{ marginTop: 8 }}>
                        Effective date
                      </div>
                      <strong>{taxPreview.effectiveDate}</strong>
                    </div>
                  </div>
                  <p className="form-hint" style={{ marginTop: 10 }}>
                    {taxPreview.taxInclusive
                      ? "The shipment amount is treated as VAT-inclusive. VAT is extracted from the gross amount; it is not added a second time."
                      : "The shipment amount is treated as VAT-exclusive. VAT is added to the subtotal to determine the amount payable."}
                  </p>
                </>
              )}
            </section>
          )}

          {invoiceShipment && (
            <div className="selected-shipment">
              {(() => {
                const shipment = shipments.find(
                  (item) => item.id === invoiceShipment,
                );

                if (!shipment) {
                  return null;
                }

                async function viewInvoice(invoice: Invoice) {
                  const tab = window.open("about:blank", "_blank");
                  if (!tab) {
                    setError(
                      "Your browser blocked the invoice PDF tab. Allow pop-ups for this AAL site and try again.",
                    );
                    return;
                  }
                  tab.document.title = `Invoice ${invoice.invoiceNo}`;
                  tab.document.body.innerHTML =
                    "<p style='font-family: sans-serif; padding: 24px'>Loading invoice PDF…</p>";
                  try {
                    const blob = await fetchApiBlob(
                      `/api/finance/documents/invoices/${invoice.id}/pdf`,
                    );
                    const objectUrl = URL.createObjectURL(blob);
                    tab.location.href = objectUrl;
                    window.setTimeout(
                      () => URL.revokeObjectURL(objectUrl),
                      5 * 60 * 1000,
                    );
                  } catch (exception) {
                    tab.close();
                    setError(
                      exception instanceof Error
                        ? exception.message
                        : "Unable to open invoice PDF.",
                    );
                  }
                }

                async function downloadInvoice(invoice: Invoice) {
                  try {
                    await downloadApiFile(
                      `/api/finance/documents/invoices/${invoice.id}/pdf`,
                      `invoice-${invoice.invoiceNo}.pdf`,
                    );
                  } catch (exception) {
                    setError(
                      exception instanceof Error
                        ? exception.message
                        : "Unable to download invoice.",
                    );
                  }
                }

                async function emailInvoice(invoice: Invoice) {
                  const recipientEmail = window.prompt(
                    "Client email (leave blank to use the shipment notification email):",
                    "",
                  );
                  if (recipientEmail === null) return;
                  try {
                    await apiFetch<void>(
                      `/api/finance/documents/invoices/${invoice.id}/email`,
                      {
                        method: "POST",
                        body: JSON.stringify({
                          recipientEmail: recipientEmail.trim() || undefined,
                        }),
                      },
                    );
                    setSuccess(
                      `Invoice ${invoice.invoiceNo} was accepted for email delivery.`,
                    );
                  } catch (exception) {
                    setError(
                      exception instanceof Error
                        ? exception.message
                        : "Unable to email invoice.",
                    );
                  }
                }

                async function downloadReceipt(paymentId: string) {
                  try {
                    await downloadApiFile(
                      `/api/finance/documents/payments/${paymentId}/receipt/pdf`,
                      "payment-receipt.pdf",
                    );
                  } catch (exception) {
                    setError(
                      exception instanceof Error
                        ? exception.message
                        : "Unable to download receipt.",
                    );
                  }
                }

                async function emailReceipt(paymentId: string) {
                  const recipientEmail = window.prompt(
                    "Client email (leave blank to use the shipment notification email):",
                    "",
                  );
                  if (recipientEmail === null) return;
                  try {
                    await apiFetch<void>(
                      `/api/finance/documents/payments/${paymentId}/receipt/email`,
                      {
                        method: "POST",
                        body: JSON.stringify({
                          recipientEmail: recipientEmail.trim() || undefined,
                        }),
                      },
                    );
                    setSuccess(
                      "Payment receipt was accepted for email delivery.",
                    );
                  } catch (exception) {
                    setError(
                      exception instanceof Error
                        ? exception.message
                        : "Unable to email receipt.",
                    );
                  }
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
                          shipment.amountBilledToClient &&
                            shipment.amountBilledToClient > 0
                            ? shipment.amountBilledToClient
                            : (shipment.clientRevenue ?? 0),
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
              <label className="field">
                <span>Income source</span>
                <select
                  value={incomeSourceId}
                  onChange={(event) => setIncomeSourceId(event.target.value)}
                >
                  <option value="">Leave unclassified</option>
                  {incomeSources
                    .filter((source) => source.active)
                    .map((source) => (
                      <option key={source.id} value={source.id}>
                        {source.name} ({source.code})
                      </option>
                    ))}
                </select>
                <small>
                  When selected, the active allocation rules for this source are
                  copied to the payment.
                </small>
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
                <th>Subtotal</th>
                <th>VAT</th>
                <th>Total incl. VAT</th>
                <th>Paid</th>
                <th>Balance</th>
                <th>Status</th>
                <th />
              </tr>
            </thead>

            <tbody>
              {loading && (
                <tr>
                  <td colSpan={12} className="empty">
                    Loading billing register…
                  </td>
                </tr>
              )}

              {!loading &&
                filtered.map((invoice) => {
                  const shipment = shipmentForInvoice(invoice);

                  async function viewInvoice(invoice: Invoice) {
                    const tab = window.open("about:blank", "_blank");
                    if (!tab) {
                      setError(
                        "Your browser blocked the invoice PDF tab. Allow pop-ups for this AAL site and try again.",
                      );
                      return;
                    }
                    tab.document.title = `Invoice ${invoice.invoiceNo}`;
                    tab.document.body.innerHTML =
                      "<p style='font-family: sans-serif; padding: 24px'>Loading invoice PDF…</p>";
                    try {
                      const blob = await fetchApiBlob(
                        `/api/finance/documents/invoices/${invoice.id}/pdf`,
                      );
                      const objectUrl = URL.createObjectURL(blob);
                      tab.location.href = objectUrl;
                      window.setTimeout(
                        () => URL.revokeObjectURL(objectUrl),
                        5 * 60 * 1000,
                      );
                    } catch (exception) {
                      tab.close();
                      setError(
                        exception instanceof Error
                          ? exception.message
                          : "Unable to open invoice PDF.",
                      );
                    }
                  }

                  async function downloadInvoice(invoice: Invoice) {
                    try {
                      await downloadApiFile(
                        `/api/finance/documents/invoices/${invoice.id}/pdf`,
                        `invoice-${invoice.invoiceNo}.pdf`,
                      );
                    } catch (exception) {
                      setError(
                        exception instanceof Error
                          ? exception.message
                          : "Unable to download invoice.",
                      );
                    }
                  }

                  async function emailInvoice(invoice: Invoice) {
                    const recipientEmail = window.prompt(
                      "Client email (leave blank to use the shipment notification email):",
                      "",
                    );
                    if (recipientEmail === null) return;
                    try {
                      await apiFetch<void>(
                        `/api/finance/documents/invoices/${invoice.id}/email`,
                        {
                          method: "POST",
                          body: JSON.stringify({
                            recipientEmail: recipientEmail.trim() || undefined,
                          }),
                        },
                      );
                      setSuccess(
                        `Invoice ${invoice.invoiceNo} was accepted for email delivery.`,
                      );
                    } catch (exception) {
                      setError(
                        exception instanceof Error
                          ? exception.message
                          : "Unable to email invoice.",
                      );
                    }
                  }

                  async function downloadReceipt(paymentId: string) {
                    try {
                      await downloadApiFile(
                        `/api/finance/documents/payments/${paymentId}/receipt/pdf`,
                        "payment-receipt.pdf",
                      );
                    } catch (exception) {
                      setError(
                        exception instanceof Error
                          ? exception.message
                          : "Unable to download receipt.",
                      );
                    }
                  }

                  async function emailReceipt(paymentId: string) {
                    const recipientEmail = window.prompt(
                      "Client email (leave blank to use the shipment notification email):",
                      "",
                    );
                    if (recipientEmail === null) return;
                    try {
                      await apiFetch<void>(
                        `/api/finance/documents/payments/${paymentId}/receipt/email`,
                        {
                          method: "POST",
                          body: JSON.stringify({
                            recipientEmail: recipientEmail.trim() || undefined,
                          }),
                        },
                      );
                      setSuccess(
                        "Payment receipt was accepted for email delivery.",
                      );
                    } catch (exception) {
                      setError(
                        exception instanceof Error
                          ? exception.message
                          : "Unable to email receipt.",
                      );
                    }
                  }

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

                      <td>
                        {money(
                          invoice.subtotalAmount ?? invoice.invoiceAmount,
                          invoice.currency,
                        )}
                      </td>

                      <td>
                        {invoice.taxCode || (invoice.taxRate ?? 0) > 0 ? (
                          <>
                            {money(invoice.taxAmount, invoice.currency)}
                            {invoice.taxRate ? ` (${invoice.taxRate}%)` : ""}
                            <div className="card-muted">
                              {invoice.taxInclusive
                                ? "Included in charge"
                                : "Added to charge"}
                              {invoice.taxCode ? ` · ${invoice.taxCode}` : ""}
                            </div>
                          </>
                        ) : (
                          "—"
                        )}
                      </td>

                      <td>
                        <strong>
                          {money(invoice.invoiceAmount, invoice.currency)}
                        </strong>
                      </td>

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
                        <div className="actions" style={{ flexWrap: "wrap" }}>
                          <button
                            type="button"
                            className="btn btn-small"
                            onClick={() => viewInvoice(invoice)}
                          >
                            View / Print
                          </button>
                          <button
                            type="button"
                            className="btn btn-small"
                            onClick={() => downloadInvoice(invoice)}
                          >
                            Download PDF
                          </button>
                          <button
                            type="button"
                            className="btn btn-small"
                            onClick={() => emailInvoice(invoice)}
                          >
                            Email
                          </button>
                          {invoice.balance > 0 && (
                            <button
                              type="button"
                              className="btn btn-small"
                              onClick={() => setPaymentInvoice(invoice)}
                            >
                              Receive payment
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })}

              {!loading && !filtered.length && (
                <tr>
                  <td colSpan={12} className="empty">
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
