"use client";

import { FormEvent, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import Icon from "@/components/Icon";
import { ApiError, publicCommercialApi } from "@/lib/api-client";

const modes = [
  ["ALL", "All modes", "globe"],
  ["AIR", "Air freight", "plane"],
  ["SEA", "Sea freight", "ship"],
  ["ROAD", "Road freight", "truck"],
] as const;

type PackageLine = {
  id: number;
  quantity: string;
  length: string;
  width: string;
  height: string;
  weight: string;
};

const blankPackage = (id: number): PackageLine => ({
  id,
  quantity: "1",
  length: "",
  width: "",
  height: "",
  weight: "",
});

export default function QuotePage() {
  const router = useRouter();
  const [mode, setMode] = useState<(typeof modes)[number][0]>("ALL");
  const [form, setForm] = useState({
    origin: "",
    destination: "",
    pickupRequired: true,
    deliveryRequired: true,
    commodity: "",
    company: "",
    contactName: "",
    email: "",
    phone: "",
    notes: "",
  });
  const [packages, setPackages] = useState<PackageLine[]>([blankPackage(1)]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const totals = useMemo(() => {
    let quantity = 0;
    let volume = 0;
    let weight = 0;

    for (const row of packages) {
      const q = Number(row.quantity) || 0;
      const l = Number(row.length) || 0;
      const w = Number(row.width) || 0;
      const h = Number(row.height) || 0;
      const kg = Number(row.weight) || 0;

      quantity += q;
      volume += (q * l * w * h) / 1_000_000;
      weight += q * kg;
    }

    const airVolumetric = volume * 167;
    const chargeable =
      mode === "AIR" || mode === "ALL"
        ? Math.max(weight, airVolumetric)
        : weight;

    return { quantity, volume, weight, chargeable };
  }, [packages, mode]);

  function update(key: keyof typeof form, value: string | boolean) {
    setForm((current) => ({ ...current, [key]: value }));
  }

  function updatePackage(
    id: number,
    key: keyof Omit<PackageLine, "id">,
    value: string,
  ) {
    setPackages((rows) =>
      rows.map((row) => (row.id === id ? { ...row, [key]: value } : row)),
    );
  }

  function addPackage() {
    setPackages((rows) => [
      ...rows,
      blankPackage(Math.max(...rows.map((row) => row.id), 0) + 1),
    ]);
  }

  function removePackage(id: number) {
    setPackages((rows) =>
      rows.length === 1 ? rows : rows.filter((row) => row.id !== id),
    );
  }

  async function getQuotes(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError("");

    try {
      const response = await publicCommercialApi.requestQuote({
        origin: form.origin,
        destination: form.destination,
        serviceType: mode,
        commodity: form.commodity,
        chargeableWeightKg: totals.chargeable,
        volumeCbm: totals.volume,
        packages: totals.quantity,
        company: form.company,
        contactName: form.contactName,
        email: form.email,
        phone: form.phone,
        notes: `${form.notes || ""}${form.pickupRequired ? " | Pickup required" : ""}${form.deliveryRequired ? " | Delivery required" : ""}`,
      });

      router.push(
        `/quote/results/${encodeURIComponent(response.requestToken)}`,
      );
    } catch (e) {
      setError(
        e instanceof ApiError
          ? e.message
          : "Unable to prepare your freight quote.",
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="kn-public-shell">
      <PublicHeader />
      <section className="kn-page-frame">
        <div className="kn-breadcrumb">
          Home <span>›</span> Quote
        </div>

        <div className="kn-stepper" aria-label="Quote steps">
          <Step number="1" label="Quote request" active />
          <span className="kn-step-arrow">→</span>
          <Step number="2" label="Quote results" />
          <span className="kn-step-arrow">→</span>
          <Step number="3" label="Quote details" />
          <span className="kn-step-arrow">→</span>
          <Step number="4" label="Booking" />
        </div>

        <header className="kn-page-head">
          <div>
            <div className="kn-eyebrow">QUOTE REQUEST</div>
            <h1>Get freight options in one request.</h1>
            <p>
              Enter the route and cargo details once. AAL will calculate the
              available options, save your request and email you a secure link
              to review the results.
            </p>
          </div>
        </header>

        {error && <div className="kn-error">{error}</div>}

        <form onSubmit={getQuotes} className="kn-form">
          <section className="kn-card">
            <div className="kn-card-title-row">
              <div>
                <h2>Origin</h2>
                <span>Shipment pickup location</span>
              </div>
              <div>
                <h2>Destination</h2>
                <span>Delivery location</span>
              </div>
            </div>
            <div className="kn-route-grid">
              <Field
                label="From origin location"
                value={form.origin}
                onChange={(value) => update("origin", value)}
                required
              />
              <Field
                label="To destination location"
                value={form.destination}
                onChange={(value) => update("destination", value)}
                required
              />
              <label className="kn-check">
                <input
                  type="checkbox"
                  checked={form.pickupRequired}
                  onChange={(event) =>
                    update("pickupRequired", event.target.checked)
                  }
                />
                Pickup required?
              </label>
              <label className="kn-check">
                <input
                  type="checkbox"
                  checked={form.deliveryRequired}
                  onChange={(event) =>
                    update("deliveryRequired", event.target.checked)
                  }
                />
                Delivery to consignee?
              </label>
            </div>
          </section>

          <section className="kn-card">
            <div className="kn-section-tabs">
              <strong>Package details</strong>
              <span>Totals update automatically</span>
            </div>
            <div className="kn-unit-row">
              <span>kg / cm</span>
              <span>Volume in m³</span>
            </div>
            <div className="kn-package-table">
              <div className="kn-package-head">
                <span>Quantity</span>
                <span>Length</span>
                <span>Width</span>
                <span>Height</span>
                <span>Weight / piece</span>
                <span />
              </div>
              {packages.map((row) => (
                <div className="kn-package-row" key={row.id}>
                  <NumberField
                    value={row.quantity}
                    onChange={(value) =>
                      updatePackage(row.id, "quantity", value)
                    }
                  />
                  <NumberField
                    value={row.length}
                    onChange={(value) => updatePackage(row.id, "length", value)}
                    suffix="cm"
                  />
                  <NumberField
                    value={row.width}
                    onChange={(value) => updatePackage(row.id, "width", value)}
                    suffix="cm"
                  />
                  <NumberField
                    value={row.height}
                    onChange={(value) => updatePackage(row.id, "height", value)}
                    suffix="cm"
                  />
                  <NumberField
                    value={row.weight}
                    onChange={(value) => updatePackage(row.id, "weight", value)}
                    suffix="kg"
                  />
                  <button
                    type="button"
                    className="kn-remove"
                    onClick={() => removePackage(row.id)}
                    aria-label="Remove package"
                  >
                    ×
                  </button>
                </div>
              ))}
              <button
                type="button"
                className="kn-add-package"
                onClick={addPackage}
              >
                + Add another package
              </button>
            </div>
            <div className="kn-total-strip">
              <Metric label="Total quantity" value={String(totals.quantity)} />
              <Metric
                label="Total volume"
                value={`${totals.volume.toFixed(3)} m³`}
              />
              <Metric
                label="Total weight"
                value={`${totals.weight.toFixed(2)} kg`}
              />
              <Metric
                label="Chargeable weight"
                value={`${totals.chargeable.toFixed(2)} kg`}
              />
            </div>
          </section>

          <section className="kn-card">
            <div className="kn-card-title-row single">
              <div>
                <h2>Transport & customer details</h2>
                <span>Used for pricing and quotation delivery</span>
              </div>
            </div>
            <div className="kn-mode-grid">
              {modes.map(([value, label, icon]) => (
                <button
                  key={value}
                  type="button"
                  className={mode === value ? "active" : ""}
                  onClick={() => setMode(value)}
                >
                  <Icon
                    name={icon as "globe" | "plane" | "ship" | "truck"}
                    size={18}
                  />
                  <span>{label}</span>
                </button>
              ))}
            </div>
            <div className="kn-fields-3">
              <Field
                label="Commodity"
                value={form.commodity}
                onChange={(value) => update("commodity", value)}
              />
              <Field
                label="Company"
                value={form.company}
                onChange={(value) => update("company", value)}
              />
              <Field
                label="Contact name"
                value={form.contactName}
                onChange={(value) => update("contactName", value)}
                required
              />
              <Field
                label="Email"
                type="email"
                value={form.email}
                onChange={(value) => update("email", value)}
                required
              />
              <Field
                label="Phone"
                value={form.phone}
                onChange={(value) => update("phone", value)}
              />
              <Field
                label="Additional notes"
                value={form.notes}
                onChange={(value) => update("notes", value)}
              />
            </div>
          </section>

          <div className="kn-form-actions">
            <div>
              <strong>Ready to compare freight options?</strong>
              <span>
                AAL will email your secure quote-results link immediately.
              </span>
            </div>
            <button
              className="kn-primary-button"
              type="submit"
              disabled={
                busy ||
                totals.quantity <= 0 ||
                (totals.weight <= 0 && totals.volume <= 0)
              }
            >
              {busy ? "Preparing…" : "Get quotes"}
              <Icon name="arrow" size={15} />
            </button>
          </div>
        </form>
      </section>
    </main>
  );
}

function PublicHeader() {
  return (
    <header className="kn-header">
      <a href="/" className="kn-brand" aria-label="AAL home">
        <span className="kn-brand-mark">
          <Icon name="plane" size={19} />
        </span>
        <span>
          <strong>AAL</strong>
          <small>AFRICA LOGISTIC AVIATION</small>
        </span>
      </a>
      <div className="kn-header-search">
        <Icon name="search" size={17} />
        <span>Search by tracking reference</span>
        <a href="/track">Track</a>
      </div>
      <div className="kn-header-actions">
        <a href="/book">Book</a>
        <a href="/login" className="kn-login-button">
          AAL team
        </a>
      </div>
    </header>
  );
}

function Step({
  number,
  label,
  active = false,
  complete = false,
}: {
  number: string;
  label: string;
  active?: boolean;
  complete?: boolean;
}) {
  return (
    <span
      className={`kn-step ${active ? "active" : ""} ${complete ? "complete" : ""}`}
    >
      <b>{number}</b>
      {label}
    </span>
  );
}

function Field({
  label,
  value,
  onChange,
  required = false,
  type = "text",
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  required?: boolean;
  type?: string;
}) {
  return (
    <label className="kn-field">
      <span>{label}</span>
      <input
        required={required}
        type={type}
        value={value}
        onChange={(event) => onChange(event.target.value)}
      />
    </label>
  );
}

function NumberField({
  value,
  onChange,
  suffix,
}: {
  value: string;
  onChange: (value: string) => void;
  suffix?: string;
}) {
  return (
    <div className="kn-number-field">
      <input
        inputMode="decimal"
        value={value}
        onChange={(event) => onChange(event.target.value)}
      />
      {suffix && <span>{suffix}</span>}
    </div>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="kn-metric">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}
