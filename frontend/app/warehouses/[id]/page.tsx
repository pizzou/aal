"use client";
import { useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import Link from "next/link";
import { inventoryApi, InventoryItem, ApiError } from "@/lib/api-client";
import { useAuth } from "@/lib/auth-context";

export default function WarehouseInventoryPage() {
  const params = useParams<{ id: string }>();
  const router = useRouter();
  const { accessToken, isLoading } = useAuth();
  const [items, setItems] = useState<InventoryItem[]>([]);
  const [form, setForm] = useState({ sku: "", name: "", reorderLevel: 0 });
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!isLoading && !accessToken) router.push("/login");
  }, [isLoading, accessToken, router]);

  useEffect(() => {
    if (accessToken) refresh();
  }, [accessToken]);

  async function refresh() {
    try {
      const page = await inventoryApi.list(params.id);
      setItems(page.content);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to load inventory");
    }
  }

  async function handleCreate(e: React.FormEvent) {
    e.preventDefault();
    try {
      await inventoryApi.create(params.id, form);
      setForm({ sku: "", name: "", reorderLevel: 0 });
      refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to create item");
    }
  }

  if (isLoading || !accessToken) return null;

  return (
    <main style={{ maxWidth: 800, margin: "40px auto", fontFamily: "sans-serif" }}>
      <Link href="/warehouses">&larr; Warehouses</Link>
      <h1>Inventory</h1>

      <form onSubmit={handleCreate} style={{ display: "flex", gap: 8, margin: "20px 0" }}>
        <input placeholder="SKU" value={form.sku}
          onChange={(e) => setForm({ ...form, sku: e.target.value })} required />
        <input placeholder="Item name" value={form.name}
          onChange={(e) => setForm({ ...form, name: e.target.value })} required />
        <input type="number" placeholder="Reorder level" value={form.reorderLevel}
          onChange={(e) => setForm({ ...form, reorderLevel: Number(e.target.value) })} min={0} />
        <button type="submit">Add item</button>
      </form>

      {error && <p style={{ color: "crimson" }}>{error}</p>}

      <table width="100%" cellPadding={8} style={{ borderCollapse: "collapse" }}>
        <thead>
          <tr style={{ textAlign: "left", borderBottom: "1px solid #ccc" }}>
            <th>SKU</th><th>Name</th><th>On hand</th><th>Reorder at</th><th></th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => (
            <tr key={item.id} style={{
              borderBottom: "1px solid #eee",
              background: item.belowReorderLevel ? "#fff4f4" : "transparent",
            }}>
              <td>{item.sku}</td>
              <td>{item.name}</td>
              <td>{item.quantityOnHand}{item.belowReorderLevel ? " ⚠" : ""}</td>
              <td>{item.reorderLevel}</td>
              <td><Link href={`/inventory/${item.id}`}>Adjust stock</Link></td>
            </tr>
          ))}
          {items.length === 0 && <tr><td colSpan={5} style={{ color: "#888" }}>No items yet.</td></tr>}
        </tbody>
      </table>
    </main>
  );
}
