"use client";

import Link from "next/link";
import { useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { ApiError, Shipment, apiFetch, shipmentsApi } from "@/lib/api-client";
import { useAuth } from "@/lib/auth-context";

type AwbCreated = {
  id: string;
  shipmentId: string;
  awbNumber: string;
  hawbNumber?: string | null;
  awbType: string;
  validationStatus: string;
  submissionStatus: string;
};

function airportSeed(value?: string | null): string {
  const candidate = (value || "").trim().toUpperCase();
  return /^[A-Z]{3}$/.test(candidate) ? candidate : "";
}

function numberLabel(value?: number | null): string {
  return value == null || !Number.isFinite(value)
    ? "Not captured"
    : value.toLocaleString(undefined, { maximumFractionDigits: 3 });
}

export default function StructuredAwbPage() {
  const router = useRouter();
  const { accessToken, isLoading: authLoading } = useAuth();

  const [shipments, setShipments] = useState<Shipment[]>([]);
  const [shipmentId, setShipmentId] = useState("");
  const [shipment, setShipment] = useState<Shipment | null>(null);
  const [originAirport, setOriginAirport] = useState("");
  const [destinationAirport, setDestinationAirport] = useState("");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    if (authLoading || !accessToken) return;
    let active = true;

    async function loadContext() {
      setLoading(true);
      setError("");
      try {
        const requestedShipmentId =
          new URLSearchParams(window.location.search).get("shipmentId") || "";
        const result = await shipmentsApi.list({
          page: 0,
          size: 100,
          mode: "AIR",
        });
        if (!active) return;
        const airShipments = result.content.filter(
          (item) => item.transportMode === "AIR",
        );
        setShipments(airShipments);

        if (requestedShipmentId) {
          const selected = await shipmentsApi.get(requestedShipmentId);
          if (!active) return;
          setShipment(selected);
          setShipmentId(selected.id);
          setOriginAirport(airportSeed(selected.originCityPort));
          setDestinationAirport(airportSeed(selected.destinationCityPort));
          if (!airShipments.some((item) => item.id === selected.id)) {
            setShipments((current) => [selected, ...current]);
          }
        } else {
          setShipment(null);
          setShipmentId("");
          setOriginAirport("");
          setDestinationAirport("");
        }
      } catch (exception) {
        if (active) {
          setError(
            exception instanceof Error
              ? exception.message
              : "Unable to load shipments for AWB preparation.",
          );
        }
      } finally {
        if (active) setLoading(false);
      }
    }

    void loadContext();
    return () => {
      active = false;
    };
  }, [accessToken, authLoading]);

  const selectedShipment = useMemo(
    () => shipment || shipments.find((item) => item.id === shipmentId) || null,
    [shipment, shipments, shipmentId],
  );

  function selectShipment(nextId: string) {
    setShipmentId(nextId);
    const next = shipments.find((item) => item.id === nextId) || null;
    setShipment(next);
    setOriginAirport(airportSeed(next?.originCityPort));
    setDestinationAirport(airportSeed(next?.destinationCityPort));
    setError("");
  }

  async function generateFromShipment() {
    if (!selectedShipment) {
      setError("Select an air shipment before generating its AWB.");
      return;
    }
    if (selectedShipment.transportMode !== "AIR") {
      setError("An air waybill can only be generated for an AIR shipment.");
      return;
    }

    setSaving(true);
    setError("");
    try {
      const created = await apiFetch<AwbCreated>(
        `/api/air-cargo/documents/awb/from-shipment/${encodeURIComponent(selectedShipment.id)}`,
        {
          method: "POST",
          body: JSON.stringify({
            originAirport: originAirport.trim().toUpperCase() || undefined,
            destinationAirport:
              destinationAirport.trim().toUpperCase() || undefined,
          }),
        },
      );

      // The completed step becomes visible on the next screen; the shipment
      // remains the source of truth and can be used to download the AWB PDF.
      const query = new URLSearchParams({
        shipmentId: selectedShipment.id,
        awbNumber: created.awbNumber,
        awbStatus: created.validationStatus || "DRAFT",
      });
      const hasBillableValue =
        (selectedShipment.amountBilledToClient ?? 0) > 0 ||
        (selectedShipment.clientRevenue ?? 0) > 0;
      router.replace(
        hasBillableValue
          ? `/billing?${query.toString()}`
          : `/shipments/${encodeURIComponent(selectedShipment.id)}?${query.toString()}&next=pricing`,
      );
    } catch (exception) {
      setError(
        exception instanceof ApiError || exception instanceof Error
          ? exception.message
          : "Unable to generate the AWB from this shipment.",
      );
    } finally {
      setSaving(false);
    }
  }

  const grossWeight =
    selectedShipment?.grossWeightKg ?? selectedShipment?.weightKg ?? null;
  const chargeableWeight =
    selectedShipment?.chargeableWeightKg ??
    (Math.max(grossWeight ?? 0, selectedShipment?.volumetricWeightKg ?? 0) ||
      null);
  const pieces = selectedShipment?.packages ?? null;
  const billable =
    (selectedShipment?.amountBilledToClient ?? 0) > 0 ||
    (selectedShipment?.clientRevenue ?? 0) > 0;

  return (
    <main className="page awb-workflow-page">
      <div className="page-head">
        <div>
          <div className="eyebrow">AIR CARGO · DOCUMENT WORKFLOW</div>
          <h1 className="page-title">Generate an air waybill</h1>
          <p className="page-subtitle">
            Select a shipment and create an AAL House Air Waybill using the
            customer, route, cargo and commercial information already recorded
            in the platform.
          </p>
        </div>
        <div className="actions">
          <Link
            className="btn btn-secondary"
            href={
              selectedShipment
                ? `/shipments/${selectedShipment.id}`
                : "/shipments"
            }
          >
            Back to shipment
          </Link>
          <Link className="btn" href="/air-cargo">
            Air cargo workspace
          </Link>
        </div>
      </div>

      <div className="workflow-steps" aria-label="Shipment creation workflow">
        <div className="workflow-step is-complete">
          <span>1</span>
          <div>
            <strong>Shipment</strong>
            <small>Recorded</small>
          </div>
        </div>
        <div className="workflow-connector is-active" />
        <div className="workflow-step is-current">
          <span>2</span>
          <div>
            <strong>Air waybill</strong>
            <small>Generate from shipment</small>
          </div>
        </div>
        <div className="workflow-connector" />
        <div className={`workflow-step ${billable ? "" : "is-pending"}`}>
          <span>3</span>
          <div>
            <strong>Invoice</strong>
            <small>{billable ? "Next step" : "Pricing may be needed"}</small>
          </div>
        </div>
      </div>

      {error && (
        <div className="alert alert-error" role="alert">
          {error}
        </div>
      )}

      <div className="awb-workflow-grid">
        <section className="card awb-selection-card">
          <div className="section-heading">
            <div className="section-number">02</div>
            <div>
              <h2>Select shipment</h2>
              <p>
                Existing records are loaded here. No shipment UUID or customer
                details need to be retyped.
              </p>
            </div>
          </div>

          <label className="form-field">
            <span>Air shipment</span>
            <select
              className="form-input"
              value={shipmentId}
              onChange={(event) => selectShipment(event.target.value)}
              disabled={loading || saving}
              required
            >
              <option value="">Choose an air shipment…</option>
              {shipments.map((item) => (
                <option value={item.id} key={item.id}>
                  {item.referenceCode} ·{" "}
                  {item.clientName || "Client not captured"} ·{" "}
                  {item.originCityPort || item.originAddress} →{" "}
                  {item.destinationCityPort || item.destinationAddress}
                </option>
              ))}
            </select>
          </label>

          {loading && (
            <p className="form-hint" role="status">
              Loading tenant shipments…
            </p>
          )}
          {!loading && shipments.length === 0 && (
            <div className="empty-state">
              <strong>No air shipments found</strong>
              <p>
                Create an AIR shipment first. Once it is saved, this step will
                prefill the AWB from it.
              </p>
              <Link className="btn btn-primary" href="/new-shipment">
                Create shipment
              </Link>
            </div>
          )}

          {selectedShipment && (
            <div className="awb-shipment-summary">
              <div className="awb-summary-topline">
                <div>
                  <span className="summary-kicker">SHIPMENT REFERENCE</span>
                  <strong className="awb-reference">
                    {selectedShipment.referenceCode}
                  </strong>
                </div>
                <span className="status status-neutral">
                  {selectedShipment.status.replaceAll("_", " ")}
                </span>
              </div>
              <div className="awb-party-columns">
                <div>
                  <span className="summary-kicker">
                    CUSTOMER / SHIPPER SOURCE
                  </span>
                  <strong>
                    {selectedShipment.clientName || "Shipper to be confirmed"}
                  </strong>
                  <p>
                    {selectedShipment.contact || "No contact person captured"}
                  </p>
                  <p>
                    {selectedShipment.originAddress ||
                      "Origin address not captured"}
                  </p>
                </div>
                <div>
                  <span className="summary-kicker">
                    CONSIGNEE / DESTINATION SOURCE
                  </span>
                  <strong>Consignee details</strong>
                  <p>
                    {selectedShipment.destinationAddress ||
                      "Destination address not captured"}
                  </p>
                  <p>
                    {selectedShipment.destinationCityPort ||
                      selectedShipment.destinationCountry ||
                      "Destination port not captured"}
                  </p>
                </div>
              </div>
              <div className="awb-facts-grid">
                <div>
                  <span>Pieces</span>
                  <strong>{numberLabel(pieces)}</strong>
                </div>
                <div>
                  <span>Gross weight</span>
                  <strong>
                    {numberLabel(grossWeight)}
                    {grossWeight == null ? "" : " kg"}
                  </strong>
                </div>
                <div>
                  <span>Chargeable weight</span>
                  <strong>
                    {numberLabel(chargeableWeight)}
                    {chargeableWeight == null ? "" : " kg"}
                  </strong>
                </div>
                <div>
                  <span>Commodity</span>
                  <strong>
                    {selectedShipment.commodity || "To be confirmed"}
                  </strong>
                </div>
              </div>
            </div>
          )}
        </section>

        <section className="card awb-generation-card">
          <div className="section-heading">
            <div className="section-number">03</div>
            <div>
              <h2>Generate document</h2>
              <p>
                The system fills the AWB from shipment data; only confirm
                airport codes if they were not recorded as IATA codes.
              </p>
            </div>
          </div>

          <div className="form-grid">
            <label className="field">
              <span>Origin airport IATA code</span>
              <input
                value={originAirport}
                onChange={(event) =>
                  setOriginAirport(event.target.value.toUpperCase().slice(0, 3))
                }
                placeholder="Auto from shipment, e.g. KGL"
                maxLength={3}
                autoCapitalize="characters"
              />
              <small>
                {airportSeed(selectedShipment?.originCityPort)
                  ? "Prefilled from the shipment port"
                  : "Not captured as a 3-letter airport code"}
              </small>
            </label>
            <label className="field">
              <span>Destination airport IATA code</span>
              <input
                value={destinationAirport}
                onChange={(event) =>
                  setDestinationAirport(
                    event.target.value.toUpperCase().slice(0, 3),
                  )
                }
                placeholder="Auto from shipment, e.g. NBO"
                maxLength={3}
                autoCapitalize="characters"
              />
              <small>
                {airportSeed(selectedShipment?.destinationCityPort)
                  ? "Prefilled from the shipment port"
                  : "Not captured as a 3-letter airport code"}
              </small>
            </label>
          </div>

          <div className="awb-draft-notice">
            <strong>Document integrity</strong>
            <p>
              AAL can generate its internal House AWB reference automatically.
              The airline-issued Master AWB number is never fabricated. Where
              the shipment has no confirmed airport code, consignee legal
              identity, weight or pieces, the PDF is clearly marked DRAFT and
              cannot be tendered to a carrier until completed.
            </p>
          </div>

          <button
            className="btn btn-primary btn-large btn-block"
            type="button"
            onClick={generateFromShipment}
            disabled={!selectedShipment || loading || saving}
          >
            {saving
              ? "Generating AWB…"
              : billable
                ? "Generate AWB → Continue to invoice"
                : "Generate AWB → Review shipment pricing"}
          </button>
          <p className="form-hint">
            Creation is tenant-scoped and idempotent: generating again for the
            same shipment returns its existing AWB record instead of creating
            duplicates.
          </p>
        </section>
      </div>
    </main>
  );
}
