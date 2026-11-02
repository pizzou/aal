"use client";

import Link from "next/link";
import { useEffect, useMemo, useState } from "react";
import { useAuth } from "@/lib/auth-context";
import { ApiError, operationsApi, shipmentsApi, type Shipment } from "@/lib/api-client";

const deliveryReady = new Set(["ARRIVED", "OUT_FOR_DELIVERY", "DELIVERED"]);
const isDelivered = (status: string) => status === "DELIVERED" || status === "COMPLETED";

/** Real persisted delivery evidence; unlike mobile offline sync this endpoint
 * records a POD and advances the shipment through validated state transitions. */
export default function DeliveriesPage() {
  const { accessToken, isLoading } = useAuth();
  const [shipments, setShipments] = useState<Shipment[]>([]);
  const [selectedId, setSelectedId] = useState("");
  const [recipient, setRecipient] = useState("");
  const [phone, setPhone] = useState("");
  const [signatureUri, setSignatureUri] = useState("");
  const [photoUri, setPhotoUri] = useState("");
  const [notes, setNotes] = useState("");
  const [confirmDelivered, setConfirmDelivered] = useState(false);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");
  const [saving, setSaving] = useState(false);
  const [loading, setLoading] = useState(true);
  const [reload, setReload] = useState(0);
  const [query, setQuery] = useState("");

  useEffect(() => {
    const url = new URLSearchParams(window.location.search);
    const wanted = url.get("shipmentId");
    if (wanted && /^[a-f0-9-]{36}$/i.test(wanted)) setSelectedId(wanted);
  }, []);

  useEffect(() => {
    if (!accessToken) return;
    let mounted = true;
    setLoading(true);
    shipmentsApi.list({ page: 0, size: 100 })
      .then(async (page) => {
        if (!mounted) return;
        setShipments(page.content);
        // A direct link must work even when a shipment is no longer in page 1.
        if (selectedId && !page.content.some((s) => s.id === selectedId)) {
          const one = await shipmentsApi.get(selectedId);
          if (mounted) setShipments([one, ...page.content]);
        }
      })
      .catch((e) => { if (mounted) setError(e instanceof Error ? e.message : "Could not load shipments"); })
      .finally(() => { if (mounted) setLoading(false); });
    return () => { mounted = false; };
  }, [accessToken, reload, selectedId]);

  const selected = shipments.find((s) => s.id === selectedId);
  const rows = useMemo(() => shipments.filter((s) => {
    const q = query.toLowerCase().trim();
    return !q || [s.referenceCode, s.clientName, s.destinationAddress].some((v) => String(v || "").toLowerCase().includes(q));
  }), [shipments, query]);

  async function savePod(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(""); setSuccess("");
    if (!selected || !deliveryReady.has(selected.status)) {
      setError("Shipment must reach its final destination (ARRIVED or OUT_FOR_DELIVERY) before POD can be recorded.");
      return;
    }
    if (!confirmDelivered) {
      setError("Confirm that goods were actually received by the named recipient.");
      return;
    }
    if (!recipient.trim()) { setError("Recipient name is required."); return; }
    const proof = [signatureUri, photoUri].filter(Boolean);
    if (!proof.length) {
      setError("Provide an HTTPS link to stored signature or delivery photo evidence. Store the document in your approved company repository first.");
      return;
    }
    if (proof.some((v) => v.length > 240 || !/^https:\/\//i.test(v.trim()))) {
      setError("Evidence URLs must be secure HTTPS links shorter than 240 characters.");
      return;
    }
    setSaving(true);
    try {
      await operationsApi.createPod({
        shipmentId: selected.id,
        recipientName: recipient.trim(),
        recipientPhone: phone.trim() || undefined,
        signatureUri: signatureUri.trim() || undefined,
        photoUri: photoUri.trim() || undefined,
        notes: notes.trim() || undefined,
      });
      setSuccess(`Delivery confirmed for ${selected.referenceCode}. POD was recorded on the server; Finance can now issue the invoice.`);
      setConfirmDelivered(false);
      setSignatureUri(""); setPhotoUri(""); setNotes("");
      setReload((value) => value + 1);
    } catch (e) {
      setError(e instanceof ApiError || e instanceof Error ? e.message : "POD could not be recorded. Do not treat delivery as confirmed.");
    } finally { setSaving(false); }
  }

  if (isLoading || !accessToken) return null;
  return <main className="page">
    <div className="page-head"><div>
      <div className="eyebrow">WORKFLOW · STEP 4 OF 7</div>
      <h1 className="page-title">Delivery & proof of delivery</h1>
      <p className="page-subtitle">Complete transportation and customs first. Record genuine recipient evidence here; an offline queue alone does not complete delivery.</p>
    </div><div className="actions"><Link className="btn" href="/shipments">← Shipments</Link><Link className="btn" href="/operations">Transport & customs</Link></div></div>
    {error && <div role="alert" className="alert alert-error">{error}</div>}
    {success && <div role="status" className="alert alert-success">{success} <Link href={`/billing?shipmentId=${encodeURIComponent(selectedId)}`}>Step 5: Create invoice →</Link></div>}
    <section className="grid grid-2" style={{ marginTop: 18 }}>
      <div className="card">
        <h2 className="card-title">Select shipment</h2>
        <label className="field"><span>Search by reference or customer</span>
          <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Shipment reference / customer" />
        </label>
        <label className="field"><span>Shipment</span>
          <select value={selectedId} onChange={(e) => { setSelectedId(e.target.value); setSuccess(""); setError(""); }}>
            <option value="">Choose shipment…</option>
            {rows.map((s) => <option key={s.id} value={s.id}>{s.referenceCode} · {s.clientName || "No client"} · {s.status.replaceAll("_", " ")}</option>)}
          </select>
        </label>
        {loading && <p>Loading shipment data…</p>}
        {selected && <div className="card-muted" style={{ marginTop: 12 }}>
          <strong>{selected.referenceCode}</strong><p>{selected.originAddress} → {selected.destinationAddress}</p>
          <p>Current status: <strong>{selected.status.replaceAll("_", " ")}</strong></p>
          {!deliveryReady.has(selected.status) && <p>Complete the remaining movement, clearance or dispatch statuses before recording delivery.</p>}
          {isDelivered(selected.status) && <p>Already delivered: open the shipment record to view its status and documents. Do not create duplicate POD.</p>}
          <Link href={`/shipments/${selected.id}`}>Open shipment →</Link>
        </div>}
      </div>
      <form className="card" onSubmit={savePod}>
        <h2 className="card-title">Record actual delivery</h2>
        <p className="card-muted">Record the recipient and a link to the uploaded signature or delivery photo. This writes the canonical POD and updates the shipment state.</p>
        <label className="field"><span>Recipient full name *</span><input required maxLength={160} value={recipient} onChange={(e) => setRecipient(e.target.value)} /></label>
        <label className="field"><span>Recipient phone</span><input maxLength={80} value={phone} onChange={(e) => setPhone(e.target.value)} /></label>
        <label className="field"><span>Signature document HTTPS URL</span><input type="url" value={signatureUri} onChange={(e) => setSignatureUri(e.target.value)} placeholder="https://…" /></label>
        <label className="field"><span>Delivery photo HTTPS URL</span><input type="url" value={photoUri} onChange={(e) => setPhotoUri(e.target.value)} placeholder="https://…" /></label>
        <div className="card-muted">Store the signed document or photo in your approved company document repository and paste its secure HTTPS URL. You can review existing shipment records in <Link href="/documents">Documents</Link>. One evidence URL is required.</div>
        <label className="field"><span>Delivery note</span><textarea maxLength={2000} value={notes} onChange={(e) => setNotes(e.target.value)} placeholder="Condition of cargo, delivery details" /></label>
        <label className="checkbox-row"><input type="checkbox" checked={confirmDelivered} onChange={(e) => setConfirmDelivered(e.target.checked)} /><span>I confirm the named recipient actually received the cargo.</span></label>
        <button className="btn btn-primary" disabled={saving || !selected || !deliveryReady.has(selected.status) || isDelivered(selected.status)} type="submit">{saving ? "Saving POD…" : "Confirm delivery & save POD"}</button>
      </form>
    </section>
    <div className="actions" style={{ marginTop: 18 }}><Link className="btn" href="/trips">Trip & dispatch details</Link><Link className="btn" href="/billing">Step 5 · Invoices →</Link></div>
  </main>;
}
