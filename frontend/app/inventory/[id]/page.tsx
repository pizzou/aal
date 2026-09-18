"use client";
import { useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { inventoryApi, StockMovementRecord, ApiError } from "@/lib/api-client";
import { useAuth } from "@/lib/auth-context";

export default function InventoryItemPage() {
  const params = useParams<{ id: string }>();
  const router = useRouter();
  const { accessToken, isLoading } = useAuth();
  const [movements, setMovements] = useState<StockMovementRecord[]>([]);
  const [quantityOnHand, setQuantityOnHand] = useState<number | null>(null);
  const [form, setForm] = useState({ movementType: "IN", quantity: 1, reason: "" });
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!isLoading && !accessToken) router.push("/login");
  }, [isLoading, accessToken, router]);

  useEffect(() => {
    if (accessToken) refresh();
  }, [accessToken]);

  async function refresh() {
    try {
      const page = await inventoryApi.history(params.id);
      setMovements(page.content);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to load movement history");
    }
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      const updated = await inventoryApi.recordMovement(params.id, form);
      setQuantityOnHand(updated.quantityOnHand);
      setForm({ ...form, quantity: 1, reason: "" });
      refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to record movement");
    }
  }

  if (isLoading || !accessToken) return null;

  return (
    <main style={{ maxWidth: 700, margin: "40px auto", fontFamily: "sans-serif" }}>
      <h1>Stock movement</h1>
      {quantityOnHand !== null && <p>Current quantity on hand: <strong>{quantityOnHand}</strong></p>}

      <form onSubmit={handleSubmit} style={{ display: "flex", gap: 8, margin: "20px 0" }}>
        <select value={form.movementType} onChange={(e) => setForm({ ...form, movementType: e.target.value })}>
          <option value="IN">Stock in</option>
          <option value="OUT">Stock out</option>
          <option value="ADJUSTMENT_IN">Adjustment (+)</option>
          <option value="ADJUSTMENT_OUT">Adjustment (-)</option>
        </select>
        <input type="number" min={1} value={form.quantity}
          onChange={(e) => setForm({ ...form, quantity: Number(e.target.value) })} required />
        <input placeholder="Reason (optional)" value={form.reason}
          onChange={(e) => setForm({ ...form, reason: e.target.value })} />
        <button type="submit">Record</button>
      </form>

      {error && <p style={{ color: "crimson" }}>{error}</p>}

      <h2>History</h2>
      <table width="100%" cellPadding={8} style={{ borderCollapse: "collapse" }}>
        <thead>
          <tr style={{ textAlign: "left", borderBottom: "1px solid #ccc" }}>
            <th>Type</th><th>Qty</th><th>Reason</th><th>When</th>
          </tr>
        </thead>
        <tbody>
          {movements.map((m) => (
            <tr key={m.id} style={{ borderBottom: "1px solid #eee" }}>
              <td>{m.movementType}</td>
              <td>{m.quantity}</td>
              <td>{m.reason ?? "—"}</td>
              <td>{new Date(m.createdAt).toLocaleString()}</td>
            </tr>
          ))}
          {movements.length === 0 && <tr><td colSpan={4} style={{ color: "#888" }}>No movements yet.</td></tr>}
        </tbody>
      </table>
    </main>
  );
}
