"use client";

import Link from "next/link";
import { FormEvent, useState } from "react";
import { apiFetch } from "@/lib/api-client";

type AwbCreated = { id: string; shipmentId: string; awbNumber: string; validationStatus: string; submissionStatus: string };
const fieldClass = "form-input";

function Field({ label, name, required = false, type = "text", placeholder = "" }: { label: string; name: string; required?: boolean; type?: string; placeholder?: string }) {
  return <label className="form-field"><span>{label}{required ? " *" : ""}</span><input className={fieldClass} name={name} type={type} required={required} placeholder={placeholder} /></label>;
}
function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return <section className="card" style={{ marginTop: 16 }}><h2 className="card-title">{title}</h2><div className="grid grid-2" style={{ gap: 12, marginTop: 12 }}>{children}</div></section>;
}

export default function StructuredAwbPage() {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [created, setCreated] = useState<AwbCreated | null>(null);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setError(""); setCreated(null);
    const form = new FormData(event.currentTarget);
    const value = (key: string) => String(form.get(key) ?? "").trim();
    const decimal = (key: string) => value(key) ? Number(value(key)) : null;
    const integer = (key: string) => value(key) ? Number.parseInt(value(key), 10) : null;
    const payload = {
      shipmentId: value("shipmentId"), awbNumber: value("awbNumber"), awbType: value("awbType"), mawbNumber: value("mawbNumber") || null, hawbNumber: value("hawbNumber") || null,
      shipperName: value("shipperName"), shipperAddress: value("shipperStreetAddress"), consigneeName: value("consigneeName"), consigneeAddress: value("consigneeStreetAddress"),
      issuingAgent: value("issuingAgent"), originAirport: value("originAirport").toUpperCase(), destinationAirport: value("destinationAirport").toUpperCase(), pieces: integer("pieces"),
      grossWeightKg: decimal("grossWeightKg"), chargeableWeightKg: decimal("chargeableWeightKg"), commodity: value("natureQuantityGoods"), hsCode: value("hsCode") || null, specialHandling: value("specialHandling") || null, dangerousGoods: value("dangerousGoods") === "true",
      airlinePrefix: value("airlinePrefix") || null, airlineSerial: value("airlineSerial") || null,
      shipperStreetAddress: value("shipperStreetAddress"), shipperPostalCode: value("shipperPostalCode"), shipperContactName: value("shipperContactName"), shipperPhone: value("shipperPhone"), shipperEmail: value("shipperEmail"), shipperTaxId: value("shipperTaxId"), shipperEoriNumber: value("shipperEoriNumber"),
      consigneeStreetAddress: value("consigneeStreetAddress"), consigneePostalCode: value("consigneePostalCode"), consigneeContactName: value("consigneeContactName"), consigneePhone: value("consigneePhone"), consigneeEmail: value("consigneeEmail"), consigneeTaxId: value("consigneeTaxId"), consigneeEoriNumber: value("consigneeEoriNumber"),
      issuingAgentCity: value("issuingAgentCity"), iataCargoAgentCode: value("iataCargoAgentCode"), agentAccountNumber: value("agentAccountNumber"), firstToAirport: value("firstToAirport"), firstByCarrier: value("firstByCarrier"), secondToAirport: value("secondToAirport"), secondByCarrier: value("secondByCarrier"),
      currencyCode: value("currencyCode"), paymentTermsCode: value("paymentTermsCode"), rateClass: value("rateClass"), weightUnit: value("weightUnit"), ratePerKg: decimal("ratePerKg"), freightCharge: decimal("freightCharge"),
      lengthCm: decimal("lengthCm"), widthCm: decimal("widthCm"), heightCm: decimal("heightCm"), natureQuantityGoods: value("natureQuantityGoods"), declaredValueCarriage: decimal("declaredValueCarriage"), carriageValueCode: value("carriageValueCode"), declaredValueCustoms: decimal("declaredValueCustoms"), customsValueCode: value("customsValueCode"), insuranceAmount: decimal("insuranceAmount"),
      routingSegmentsJson: value("routingSegmentsJson") || null, cargoRatingLinesJson: value("cargoRatingLinesJson") || null,
    };
    try {
      const result = await apiFetch<AwbCreated>("/api/air-cargo/documents/awb", { method: "POST", body: JSON.stringify(payload) });
      setCreated(result); event.currentTarget.reset();
    } catch (e) { setError(e instanceof Error ? e.message : "Could not create the AWB record."); }
    finally { setBusy(false); }
  }

  return <main className="page">
    <div className="page-head"><div><div className="eyebrow">AIR CARGO DOCUMENTATION</div><h1 className="page-title">Structured Air Waybill</h1><p className="page-subtitle">Capture legal parties, carrier routing, cargo rating, customs values and insurance in structured shipment records.</p></div><Link className="btn btn-secondary" href="/air-cargo">Back to air cargo</Link></div>
    {error && <div className="alert alert-error" role="alert">{error}</div>}
    {created && <div className="alert alert-success" role="status">AWB record created: <strong>{created.awbNumber}</strong> · Validation: {created.validationStatus}. <Link href={`/shipments/${created.shipmentId}`}>Open record</Link> (use the shipment ID to open shipment documents).</div>}
    <form onSubmit={submit}>
      <Section title="1. Header & shipment tracking"><Field label="Shipment UUID" name="shipmentId" required placeholder="Existing shipment ID"/><label className="form-field"><span>AWB type *</span><select className={fieldClass} name="awbType" required defaultValue="MAWB"><option value="MAWB">Master AWB (MAWB)</option><option value="HAWB">House AWB (HAWB)</option></select></label><Field label="AWB number (MAWB requires 11 digits incl. check digit)" name="awbNumber" required/><Field label="Airline 3-digit prefix" name="airlinePrefix" placeholder="e.g. 459"/><Field label="8-digit serial incl. check digit" name="airlineSerial" placeholder="e.g. 00205774"/><Field label="Parent MAWB number (for HAWB)" name="mawbNumber"/><Field label="HAWB number" name="hawbNumber"/><Field label="Origin airport IATA code" name="originAirport" required placeholder="KGL"/><Field label="Destination airport IATA code" name="destinationAirport" required placeholder="LHR"/></Section>
      <Section title="2. Shipper / consignor legal identification"><Field label="Company legal name" name="shipperName" required/><Field label="Street address" name="shipperStreetAddress" required/><Field label="Postal code" name="shipperPostalCode"/><Field label="Contact person" name="shipperContactName"/><Field label="Phone" name="shipperPhone"/><Field label="Email" name="shipperEmail" type="email"/><Field label="Tax ID" name="shipperTaxId"/><Field label="EORI number" name="shipperEoriNumber"/></Section>
      <Section title="3. Consignee / importer legal identification"><Field label="Company legal name" name="consigneeName" required/><Field label="Street address" name="consigneeStreetAddress" required/><Field label="Postal code" name="consigneePostalCode"/><Field label="Contact person" name="consigneeContactName"/><Field label="Phone" name="consigneePhone"/><Field label="Email" name="consigneeEmail" type="email"/><Field label="Tax ID" name="consigneeTaxId"/><Field label="EORI number" name="consigneeEoriNumber"/></Section>
      <Section title="4. Issuing carrier agent & routing"><Field label="Issuing agent / forwarder" name="issuingAgent"/><Field label="Agent city" name="issuingAgentCity"/><Field label="IATA cargo agent code" name="iataCargoAgentCode"/><Field label="Airline account number" name="agentAccountNumber"/><Field label="First transit airport (To)" name="firstToAirport" placeholder="NBO"/><Field label="First carrier (By, 2-character IATA code)" name="firstByCarrier" placeholder="WB"/><Field label="Second transit airport (To)" name="secondToAirport"/><Field label="Second carrier (By, 2-character IATA code)" name="secondByCarrier"/><label className="form-field"><span>Currency</span><select className={fieldClass} name="currencyCode" defaultValue="RWF"><option>RWF</option><option>GBP</option><option>USD</option><option>EUR</option></select></label><label className="form-field"><span>Freight payment terms</span><select className={fieldClass} name="paymentTermsCode" defaultValue="PPD"><option value="PPD">PPD — Prepaid</option><option value="COLL">COLL — Collect</option></select></label><Field label="Additional routing segments JSON (optional)" name="routingSegmentsJson" placeholder='[{"to":"NBO","by":"WB"}]'/></Section>
      <Section title="5. Cargo rating matrix"><Field label="Number of pieces" name="pieces" required type="number"/><Field label="Gross weight (kg)" name="grossWeightKg" required type="number"/><Field label="Chargeable weight (kg, auto recalculated if dimensions entered)" name="chargeableWeightKg" required type="number"/><label className="form-field"><span>Weight unit</span><select className={fieldClass} name="weightUnit" defaultValue="K"><option value="K">K — Kilograms</option><option value="L">L — Pounds (display)</option></select></label><label className="form-field"><span>Rate class</span><select className={fieldClass} name="rateClass" defaultValue="G"><option value="G">G — General cargo</option><option value="C">C — Specific commodity</option><option value="R">R — Class rate</option><option value="M">M — Minimum charge</option><option value="N">N — Normal rate</option><option value="Q">Q — Quantity rate</option></select></label><Field label="Rate per kg" name="ratePerKg" type="number"/><Field label="Freight charge" name="freightCharge" type="number"/><Field label="Length (cm)" name="lengthCm" type="number"/><Field label="Width (cm)" name="widthCm" type="number"/><Field label="Height (cm)" name="heightCm" type="number"/><Field label="HS code" name="hsCode"/><Field label="Nature & quantity of goods" name="natureQuantityGoods" required/><Field label="Additional cargo rating lines JSON (optional)" name="cargoRatingLinesJson" placeholder='[{"pieces":2,"grossWeightKg":40,"rateClass":"G"}]'/><label className="form-field"><span>Dangerous goods</span><select className={fieldClass} name="dangerousGoods" defaultValue="false"><option value="false">No</option><option value="true">Yes — ensure DG documents are attached</option></select></label><Field label="Special handling instructions" name="specialHandling"/></Section>
      <Section title="6. Valuation & insurance"><Field label="Declared value for carriage" name="declaredValueCarriage" type="number"/><label className="form-field"><span>Carriage value code</span><select className={fieldClass} name="carriageValueCode" defaultValue="NVD"><option value="NVD">NVD — No value declared</option><option value="DECLARED">Declared</option></select></label><Field label="Declared value for customs" name="declaredValueCustoms" type="number"/><label className="form-field"><span>Customs value code</span><select className={fieldClass} name="customsValueCode" defaultValue="NCV"><option value="NCV">NCV — No customs value</option><option value="DECLARED">Declared</option></select></label><Field label="Insurance amount / premium" name="insuranceAmount" type="number"/></Section>
      <div style={{ display: "flex", gap: 12, marginTop: 18 }}><button className="btn btn-primary" type="submit" disabled={busy}>{busy ? "Saving AWB…" : "Save structured AWB"}</button><Link className="btn btn-secondary" href="/shipments">Open shipments</Link></div>
      <p className="card-muted" style={{ marginTop: 12 }}>Only enter an airline MAWB assigned by the carrier. IATA agent codes and carrier account numbers must reflect your actual authorizations. This form stores routing and cargo-line JSON as supplied; it does not certify carrier acceptance or customs clearance.</p>
    </form>
  </main>;
}
