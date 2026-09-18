"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useMemo, useState } from "react";
import { ApiError, commandCenterApi, shipmentsApi } from "@/lib/api-client";

const modes = [
  "AIR",
  "ROAD",
  "SEA",
  "RAIL",
  "INLAND_WATERWAY",
  "COURIER",
  "LAST_MILE",
  "RORO",
  "PROJECT_CARGO",
];

const statuses = [
  "PENDING",
  "PLANNING",
  "BOOKED",
  "PICKED_UP",
  "DEPARTED",
  "IN_TRANSIT",
  "ARRIVED",
  "CUSTOMS",
  "OUT_FOR_DELIVERY",
  "DELIVERED",
  "COMPLETED",
  "ON_HOLD",
  "CANCELLED",
];

const paymentStatuses = ["Outstanding", "Paid"];

type FormState = {
  referenceCode: string;
  dateOpened: string;

  clientName: string;
  contact: string;
  commodity: string;

  originCountry: string;
  originCityPort: string;
  originAddress: string;

  destinationCountry: string;
  destinationCityPort: string;
  destinationAddress: string;

  grossWeightKg: string;
  volumetricWeightKg: string;
  packages: string;

  serviceType: string;
  airlineUsed: string;
  transportMode: string;

  supplierCost: string;
  otherCost: string;
  clientRevenue: string;

  amountPaidByClient: string;
  amountPaidToSupply: string;
  otherExpenses: string;

  paymentStatus: string;
  invoiceNo: string;

  operatorName: string;
  ownerName: string;

  etd: string;
  eta: string;

  nextAction: string;
  nextActionDate: string;

  notes: string;
  currency: string;
  shipmentStatus: string;
};

const initialForm: FormState = {
  referenceCode: "",
  dateOpened: new Date().toISOString().slice(0, 10),

  clientName: "",
  contact: "",
  commodity: "",

  originCountry: "Rwanda",
  originCityPort: "",
  originAddress: "",

  destinationCountry: "",
  destinationCityPort: "",
  destinationAddress: "",

  grossWeightKg: "",
  volumetricWeightKg: "",
  packages: "",

  serviceType: "Air Freight",
  airlineUsed: "",
  transportMode: "AIR",

  supplierCost: "",
  otherCost: "",
  clientRevenue: "",

  amountPaidByClient: "",
  amountPaidToSupply: "",
  otherExpenses: "",

  paymentStatus: "Outstanding",
  invoiceNo: "",

  operatorName: "",
  ownerName: "",

  etd: "",
  eta: "",

  nextAction: "",
  nextActionDate: "",

  notes: "",
  currency: "USD",
  shipmentStatus: "PENDING",
};

function numberOrUndefined(value: string) {
  if (!value.trim()) {
    return undefined;
  }

  const parsed = Number(value);

  return Number.isFinite(parsed) ? parsed : undefined;
}

function ArrowIcon({ size = 20 }: { size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden="true"
    >
      {" "}
      <path
        d="M5 12H19"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinecap="round"
      />{" "}
      <path
        d="M13 6L19 12L13 18"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinecap="round"
        strokeLinejoin="round"
      />{" "}
    </svg>
  );
}

function ShieldIcon({ size = 16 }: { size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden="true"
    >
      {" "}
      <path
        d="M12 3L20 6V11.5C20 16.5 16.8 20.2 12 22C7.2 20.2 4 16.5 4 11.5V6L12 3Z"
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinejoin="round"
      />{" "}
      <path
        d="M8.5 12L10.8 14.3L15.8 9.3"
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinecap="round"
        strokeLinejoin="round"
      />{" "}
    </svg>
  );
}

export default function NewShipmentPage() {
  const router = useRouter();

  const [form, setForm] = useState<FormState>(initialForm);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");

  const update = <K extends keyof FormState>(key: K, value: FormState[K]) => {
    setForm((current) => ({
      ...current,
      [key]: value,
    }));
  };

  const chargeableWeight = useMemo(() => {
    const gross = numberOrUndefined(form.grossWeightKg) ?? 0;
    const volumetric = numberOrUndefined(form.volumetricWeightKg) ?? 0;

    if (!gross && !volumetric) {
      return 0;
    }

    return Math.max(gross, volumetric);
  }, [form.grossWeightKg, form.volumetricWeightKg]);

  const amountRemaining = useMemo(() => {
    const billed = numberOrUndefined(form.clientRevenue) ?? 0;
    const paid = numberOrUndefined(form.amountPaidByClient) ?? 0;

    return Math.max(0, billed - paid);
  }, [form.clientRevenue, form.amountPaidByClient]);

  const estimatedNetIncome = useMemo(() => {
    const revenue = numberOrUndefined(form.clientRevenue) ?? 0;
    const supply = numberOrUndefined(form.amountPaidToSupply) ?? 0;
    const expenses = numberOrUndefined(form.otherExpenses) ?? 0;

    return revenue - supply - expenses;
  }, [form.clientRevenue, form.amountPaidToSupply, form.otherExpenses]);

  async function submit(event: FormEvent) {
    event.preventDefault();

    setSaving(true);
    setError("");
    setSuccess("");

    try {
      if (!form.referenceCode.trim()) {
        throw new Error("Shipment reference / AWB is required.");
      }

      if (!form.clientName.trim()) {
        throw new Error("Client / company is required.");
      }

      if (!form.originAddress.trim()) {
        throw new Error("Origin address is required.");
      }

      if (!form.destinationAddress.trim()) {
        throw new Error("Destination address is required.");
      }

      const created = await shipmentsApi.create({
        referenceCode: form.referenceCode.trim(),
        originAddress: form.originAddress.trim(),
        destinationAddress: form.destinationAddress.trim(),
        transportMode: form.transportMode,
        carrierName: form.airlineUsed.trim() || undefined,
        carrierReferenceNumber: undefined,
      });

      const payload: Record<string, unknown> = {
        clientName: form.clientName.trim(),
        contact: form.contact.trim() || undefined,
        commodity: form.commodity.trim() || undefined,

        originCountry: form.originCountry.trim() || undefined,
        originCityPort: form.originCityPort.trim() || undefined,

        destinationCountry: form.destinationCountry.trim() || undefined,
        destinationCityPort: form.destinationCityPort.trim() || undefined,

        grossWeightKg: numberOrUndefined(form.grossWeightKg),
        volumetricWeightKg: numberOrUndefined(form.volumetricWeightKg),

        packages: numberOrUndefined(form.packages),

        airlineUsed: form.airlineUsed.trim() || undefined,

        serviceType: form.serviceType.trim() || undefined,

        operatorName: form.operatorName.trim() || undefined,

        supplierCost: numberOrUndefined(form.supplierCost),

        otherCost: numberOrUndefined(form.otherCost),

        clientRevenue: numberOrUndefined(form.clientRevenue),

        amountPaidByClient: numberOrUndefined(form.amountPaidByClient),

        amountPaidToSupply: numberOrUndefined(form.amountPaidToSupply),

        otherExpenses: numberOrUndefined(form.otherExpenses),

        paymentStatus:
          amountRemaining === 0 && form.clientRevenue ? "Paid" : "Outstanding",

        ownerName: form.ownerName.trim() || undefined,

        invoiceNo: form.invoiceNo.trim() || undefined,

        nextAction: form.nextAction.trim() || undefined,

        nextActionDate: form.nextActionDate || undefined,

        notes: form.notes.trim() || undefined,

        currency: form.currency.trim() || "USD",

        dateOpened: form.dateOpened || undefined,

        shipmentStatus: form.shipmentStatus || "PENDING",
      };

      if (form.etd) {
        payload.etd = new Date(form.etd).toISOString();
      }

      if (form.eta) {
        payload.eta = new Date(form.eta).toISOString();
      }

      await commandCenterApi.updateShipment(created.id, payload);

      setSuccess(`Shipment ${form.referenceCode} was recorded successfully.`);

      setTimeout(() => {
        router.push(`/shipments/${created.id}`);
      }, 600);
    } catch (exception) {
      setError(
        exception instanceof ApiError || exception instanceof Error
          ? exception.message
          : "Unable to new shipment.",
      );
    } finally {
      setSaving(false);
    }
  }

  return (
    <main className="page">
      {" "}
      <div className="page-head">
        {" "}
        <div>
          {" "}
          <div className="eyebrow">AAL / DAILY OPERATIONS</div>
          <h1 className="page-title">New shipment</h1>
          <p className="page-subtitle">
            Replace the daily spreadsheet entry with one controlled operational
            record. Cargo, weight, commercial and financial information stays
            together throughout the shipment lifecycle.
          </p>
        </div>
        <div className="actions">
          <Link className="btn" href="/shipments">
            Shipment register
          </Link>

          <Link className="btn btn-primary" href="/billing">
            Billing
          </Link>
        </div>
      </div>
      <form onSubmit={submit}>
        <section className="aal-hero-panel">
          <div>
            <span className="aal-hero-kicker">AFRICA LOGISTIC AVIATION</span>

            <h2>Daily shipment record</h2>

            <p>
              The digital equivalent of the AAL MOTHERSHIP shipment register —
              with operational and financial controls built into the record.
            </p>
          </div>

          <div className="aal-hero-metric">
            <span>CHARGEABLE WEIGHT</span>

            <strong>
              {chargeableWeight.toLocaleString(undefined, {
                maximumFractionDigits: 3,
              })}
            </strong>

            <small>KG</small>
          </div>
        </section>

        {error && <div className="alert alert-error aal-alert">{error}</div>}

        {success && (
          <div className="alert alert-success aal-alert">{success}</div>
        )}

        <div className="record-layout">
          <div className="record-main">
            <section className="form-panel">
              <div className="section-heading">
                <div className="section-number">01</div>

                <div>
                  <h2>Shipment identity</h2>

                  <p>The core information that identifies the AAL shipment.</p>
                </div>
              </div>

              <div className="form-grid">
                <label className="field">
                  <span>Shipment / AWB number *</span>

                  <input
                    value={form.referenceCode}
                    onChange={(e) => update("referenceCode", e.target.value)}
                    placeholder="e.g. AAL-2026-0042"
                    required
                  />
                </label>

                <label className="field">
                  <span>Date opened *</span>

                  <input
                    type="date"
                    value={form.dateOpened}
                    onChange={(e) => update("dateOpened", e.target.value)}
                    required
                  />
                </label>

                <label className="field field-span-2">
                  <span>Client / company *</span>

                  <input
                    value={form.clientName}
                    onChange={(e) => update("clientName", e.target.value)}
                    placeholder="Client company name"
                    required
                  />
                </label>

                <label className="field">
                  <span>Client contact</span>

                  <input
                    value={form.contact}
                    onChange={(e) => update("contact", e.target.value)}
                    placeholder="+250 ..."
                  />
                </label>

                <label className="field">
                  <span>Commodity</span>

                  <input
                    value={form.commodity}
                    onChange={(e) => update("commodity", e.target.value)}
                    placeholder="Cargo description"
                  />
                </label>
              </div>
            </section>

            <section className="form-panel">
              <div className="section-heading">
                <div className="section-number">02</div>

                <div>
                  <h2>Cargo & weight</h2>

                  <p>
                    These are the critical weight fields from the AAL MOTHERSHIP
                    sheet.
                  </p>
                </div>
              </div>

              <div className="weight-grid">
                <div className="weight-card">
                  <span>GROSS WEIGHT</span>
                  <strong>{form.grossWeightKg || "0"}</strong>
                  <small>KG</small>
                </div>

                <div className="weight-card">
                  <span>VOLUMETRIC WEIGHT</span>
                  <strong>{form.volumetricWeightKg || "0"}</strong>
                  <small>KG</small>
                </div>

                <div className="weight-card featured">
                  <span>CHARGEABLE WEIGHT</span>

                  <strong>
                    {chargeableWeight.toLocaleString(undefined, {
                      maximumFractionDigits: 3,
                    })}
                  </strong>

                  <small>MAX OF GROSS / VOLUMETRIC</small>
                </div>
              </div>

              <div className="form-grid">
                <label className="field">
                  <span>Gross weight (kg) *</span>

                  <input
                    type="number"
                    min="0"
                    step="0.001"
                    value={form.grossWeightKg}
                    onChange={(e) => update("grossWeightKg", e.target.value)}
                    placeholder="0.000"
                  />
                </label>

                <label className="field">
                  <span>Volumetric weight (kg)</span>

                  <input
                    type="number"
                    min="0"
                    step="0.001"
                    value={form.volumetricWeightKg}
                    onChange={(e) =>
                      update("volumetricWeightKg", e.target.value)
                    }
                    placeholder="0.000"
                  />
                </label>

                <label className="field">
                  <span>Packages</span>

                  <input
                    type="number"
                    min="0"
                    step="1"
                    value={form.packages}
                    onChange={(e) => update("packages", e.target.value)}
                    placeholder="0"
                  />
                </label>

                <label className="field">
                  <span>Service type</span>

                  <input
                    value={form.serviceType}
                    onChange={(e) => update("serviceType", e.target.value)}
                    placeholder="Air Freight"
                  />
                </label>
              </div>
            </section>

            <section className="form-panel">
              <div className="section-heading">
                <div className="section-number">03</div>

                <div>
                  <h2>Route & airline</h2>

                  <p>
                    Capture the same origin, destination and carrier information
                    used by AAL daily operations.
                  </p>
                </div>
              </div>

              <div className="route-grid">
                <div className="route-card">
                  <div className="route-label">ORIGIN</div>

                  <div className="form-grid">
                    <label className="field">
                      <span>Country</span>

                      <input
                        value={form.originCountry}
                        onChange={(e) =>
                          update("originCountry", e.target.value)
                        }
                      />
                    </label>

                    <label className="field">
                      <span>City / Port</span>

                      <input
                        value={form.originCityPort}
                        onChange={(e) =>
                          update("originCityPort", e.target.value)
                        }
                        placeholder="Kigali"
                      />
                    </label>

                    <label className="field field-span-2">
                      <span>Address</span>

                      <input
                        value={form.originAddress}
                        onChange={(e) =>
                          update("originAddress", e.target.value)
                        }
                        placeholder="Pickup / origin address"
                        required
                      />
                    </label>
                  </div>
                </div>

                <div className="route-arrow">
                  <ArrowIcon size={22} />
                </div>

                <div className="route-card">
                  <div className="route-label">DESTINATION</div>

                  <div className="form-grid">
                    <label className="field">
                      <span>Country</span>

                      <input
                        value={form.destinationCountry}
                        onChange={(e) =>
                          update("destinationCountry", e.target.value)
                        }
                      />
                    </label>

                    <label className="field">
                      <span>City / Port</span>

                      <input
                        value={form.destinationCityPort}
                        onChange={(e) =>
                          update("destinationCityPort", e.target.value)
                        }
                        placeholder="Destination"
                      />
                    </label>

                    <label className="field field-span-2">
                      <span>Address</span>

                      <input
                        value={form.destinationAddress}
                        onChange={(e) =>
                          update("destinationAddress", e.target.value)
                        }
                        placeholder="Delivery / destination address"
                        required
                      />
                    </label>
                  </div>
                </div>
              </div>

              <div className="form-grid">
                <label className="field">
                  <span>Transport mode</span>

                  <select
                    value={form.transportMode}
                    onChange={(e) => update("transportMode", e.target.value)}
                  >
                    {modes.map((mode) => (
                      <option key={mode} value={mode}>
                        {mode.replaceAll("_", " ")}
                      </option>
                    ))}
                  </select>
                </label>

                <label className="field">
                  <span>Airline / carrier</span>

                  <input
                    value={form.airlineUsed}
                    onChange={(e) => update("airlineUsed", e.target.value)}
                    placeholder="Airline / carrier"
                  />
                </label>

                <label className="field">
                  <span>ETD</span>

                  <input
                    type="datetime-local"
                    value={form.etd}
                    onChange={(e) => update("etd", e.target.value)}
                  />
                </label>

                <label className="field">
                  <span>ETA</span>

                  <input
                    type="datetime-local"
                    value={form.eta}
                    onChange={(e) => update("eta", e.target.value)}
                  />
                </label>
              </div>
            </section>

            <section className="form-panel">
              <div className="section-heading">
                <div className="section-number">04</div>

                <div>
                  <h2>Commercial & finance</h2>

                  <p>
                    This section replaces the financial columns of the
                    MOTHERSHIP sheet with controlled system records.
                  </p>
                </div>
              </div>

              <div className="finance-entry-grid">
                <label className="field">
                  <span>Currency</span>

                  <select
                    value={form.currency}
                    onChange={(e) => update("currency", e.target.value)}
                  >
                    <option value="USD">USD</option>
                    <option value="RWF">RWF</option>
                    <option value="EUR">EUR</option>
                    <option value="KES">KES</option>
                    <option value="UGX">UGX</option>
                    <option value="TZS">TZS</option>
                  </select>
                </label>

                <label className="field">
                  <span>Amount billed to client</span>

                  <input
                    type="number"
                    min="0"
                    step="0.01"
                    value={form.clientRevenue}
                    onChange={(e) => update("clientRevenue", e.target.value)}
                    placeholder="0.00"
                  />
                </label>

                <label className="field">
                  <span>Amount paid by client</span>

                  <input
                    type="number"
                    min="0"
                    step="0.01"
                    value={form.amountPaidByClient}
                    onChange={(e) =>
                      update("amountPaidByClient", e.target.value)
                    }
                    placeholder="0.00"
                  />
                </label>

                <label className="field">
                  <span>Supplier cost</span>

                  <input
                    type="number"
                    min="0"
                    step="0.01"
                    value={form.supplierCost}
                    onChange={(e) => update("supplierCost", e.target.value)}
                    placeholder="0.00"
                  />
                </label>

                <label className="field">
                  <span>Other cost</span>

                  <input
                    type="number"
                    min="0"
                    step="0.01"
                    value={form.otherCost}
                    onChange={(e) => update("otherCost", e.target.value)}
                    placeholder="0.00"
                  />
                </label>

                <label className="field">
                  <span>Amount paid to supply</span>

                  <input
                    type="number"
                    min="0"
                    step="0.01"
                    value={form.amountPaidToSupply}
                    onChange={(e) =>
                      update("amountPaidToSupply", e.target.value)
                    }
                    placeholder="0.00"
                  />
                </label>

                <label className="field">
                  <span>Other expenses</span>

                  <input
                    type="number"
                    min="0"
                    step="0.01"
                    value={form.otherExpenses}
                    onChange={(e) => update("otherExpenses", e.target.value)}
                    placeholder="0.00"
                  />
                </label>

                <label className="field">
                  <span>Invoice number</span>

                  <input
                    value={form.invoiceNo}
                    onChange={(e) => update("invoiceNo", e.target.value)}
                    placeholder="AAL-INV-2026-..."
                  />
                </label>
              </div>

              <div className="financial-preview">
                <div>
                  <span>AMOUNT REMAINING</span>

                  <strong>
                    {amountRemaining.toLocaleString(undefined, {
                      minimumFractionDigits: 2,
                    })}
                  </strong>
                </div>

                <div>
                  <span>ESTIMATED NET INCOME</span>

                  <strong>
                    {estimatedNetIncome.toLocaleString(undefined, {
                      minimumFractionDigits: 2,
                    })}
                  </strong>
                </div>

                <div>
                  <span>PAYMENT STATUS</span>

                  <strong>
                    {amountRemaining === 0 && form.clientRevenue
                      ? "PAID"
                      : "OUTSTANDING"}
                  </strong>
                </div>
              </div>
            </section>

            <section className="form-panel">
              <div className="section-heading">
                <div className="section-number">05</div>

                <div>
                  <h2>Operations control</h2>

                  <p>Ownership, lifecycle and next-action information.</p>
                </div>
              </div>

              <div className="form-grid">
                <label className="field">
                  <span>Shipment status</span>

                  <select
                    value={form.shipmentStatus}
                    onChange={(e) => update("shipmentStatus", e.target.value)}
                  >
                    {statuses.map((status) => (
                      <option key={status} value={status}>
                        {status.replaceAll("_", " ")}
                      </option>
                    ))}
                  </select>
                </label>

                <label className="field">
                  <span>Payment status</span>

                  <select
                    value={form.paymentStatus}
                    onChange={(e) => update("paymentStatus", e.target.value)}
                  >
                    {paymentStatuses.map((status) => (
                      <option key={status} value={status}>
                        {status}
                      </option>
                    ))}
                  </select>
                </label>

                <label className="field">
                  <span>Operator</span>

                  <input
                    value={form.operatorName}
                    onChange={(e) => update("operatorName", e.target.value)}
                    placeholder="Responsible operator"
                  />
                </label>

                <label className="field">
                  <span>Owner</span>

                  <input
                    value={form.ownerName}
                    onChange={(e) => update("ownerName", e.target.value)}
                    placeholder="Account / operations owner"
                  />
                </label>

                <label className="field field-span-2">
                  <span>Next action</span>

                  <input
                    value={form.nextAction}
                    onChange={(e) => update("nextAction", e.target.value)}
                    placeholder="e.g. Confirm airline booking"
                  />
                </label>

                <label className="field">
                  <span>Next action date</span>

                  <input
                    type="date"
                    value={form.nextActionDate}
                    onChange={(e) => update("nextActionDate", e.target.value)}
                  />
                </label>

                <label className="field field-span-2">
                  <span>Notes</span>

                  <textarea
                    rows={5}
                    value={form.notes}
                    onChange={(e) => update("notes", e.target.value)}
                    placeholder="Operational notes, special instructions, client requirements..."
                  />
                </label>
              </div>
            </section>
          </div>

          <aside className="record-sidebar">
            <div className="sticky-card">
              <div className="sticky-card-label">RECORD SUMMARY</div>

              <h3>{form.referenceCode || "New AAL shipment"}</h3>

              <div className="summary-route">
                <span>
                  {form.originCityPort || form.originCountry || "Origin"}
                </span>

                <ArrowIcon size={16} />

                <span>
                  {form.destinationCityPort ||
                    form.destinationCountry ||
                    "Destination"}
                </span>
              </div>

              <div className="summary-divider" />

              <div className="summary-row">
                <span>Client</span>
                <strong>{form.clientName || "Not assigned"}</strong>
              </div>

              <div className="summary-row">
                <span>Commodity</span>
                <strong>{form.commodity || "Not specified"}</strong>
              </div>

              <div className="summary-row">
                <span>Gross weight</span>
                <strong>{form.grossWeightKg || "0"} kg</strong>
              </div>

              <div className="summary-row">
                <span>Volumetric</span>
                <strong>{form.volumetricWeightKg || "0"} kg</strong>
              </div>

              <div className="summary-row emphasis">
                <span>Chargeable</span>
                <strong>{chargeableWeight.toLocaleString()} kg</strong>
              </div>

              <div className="summary-row">
                <span>Revenue</span>

                <strong>
                  {form.currency}{" "}
                  {(
                    numberOrUndefined(form.clientRevenue) ?? 0
                  ).toLocaleString()}
                </strong>
              </div>

              <div className="summary-row">
                <span>Balance</span>

                <strong>
                  {form.currency} {amountRemaining.toLocaleString()}
                </strong>
              </div>

              <button
                type="submit"
                className="btn btn-primary btn-large"
                disabled={saving}
              >
                {saving ? "Creating shipment…" : "New shipment"}
              </button>

              <Link href="/shipments" className="btn btn-block">
                Cancel
              </Link>

              <div className="system-note">
                <ShieldIcon size={15} />

                <span>
                  Calculated financial fields remain controlled by the server.
                </span>
              </div>
            </div>
          </aside>
        </div>
      </form>
    </main>
  );
}
