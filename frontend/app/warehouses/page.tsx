"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  ApiError,
  operationsApi,
  Warehouse,
  warehousesApi,
  WarehouseTask,
} from "@/lib/api-client";
import { useAuth } from "@/lib/auth-context";

export default function WarehousesPage() {
  const { accessToken, isLoading } = useAuth();
  const router = useRouter();
  const [warehouses, setWarehouses] = useState<Warehouse[]>([]);
  const [tasks, setTasks] = useState<WarehouseTask[]>([]);
  const [form, setForm] = useState({ name: "", address: "" });
  const [error, setError] = useState("");
  async function refresh() {
    try {
      const w = await warehousesApi.list();
      setWarehouses(w);
      const taskLists = await Promise.all(
        w.map((x) => operationsApi.warehouseTasks(x.id)),
      );
      setTasks(taskLists.flat());
      setError("");
    } catch (e) {
      setError(
        e instanceof ApiError ? e.message : "Unable to load warehouse control",
      );
    }
  }
  useEffect(() => {
    if (!isLoading && !accessToken) router.push("/login");
  }, [isLoading, accessToken, router]);
  useEffect(() => {
    if (accessToken) refresh();
  }, [accessToken]);
  async function create(e: React.FormEvent) {
    e.preventDefault();
    try {
      await warehousesApi.create(form);
      setForm({ name: "", address: "" });
      refresh();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Warehouse creation failed");
    }
  }
  async function taskAction(id: string, kind: "start" | "complete" | "cancel") {
    try {
      if (kind === "start") await operationsApi.startWarehouseTask(id);
      if (kind === "complete") await operationsApi.completeWarehouseTask(id);
      if (kind === "cancel") await operationsApi.cancelWarehouseTask(id);
      refresh();
    } catch (e) {
      setError(
        e instanceof ApiError ? e.message : "Warehouse task update failed",
      );
    }
  }
  if (isLoading || !accessToken) return null;
  const open = tasks.filter(
    (t) => !["COMPLETED", "CANCELLED"].includes(t.status),
  );
  return (
    <main className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow">WAREHOUSE CONTROL</div>
          <h1 className="page-title">Warehouse Execution</h1>
          <p className="page-subtitle">
            Receiving, put-away, inventory, picking, dispatch and warehouse
            tasks under one operational module.
          </p>
        </div>
        <Link className="btn btn-primary" href="/shipments">
          Link shipment
        </Link>
      </div>
      {error && <div className="alert alert-error">{error}</div>}
      <div className="grid grid-4">
        <div className="card kpi">
          <div className="kpi-label">Warehouses</div>
          <div className="kpi-value">{warehouses.length}</div>
        </div>
        <div className="card kpi">
          <div className="kpi-label">Open tasks</div>
          <div className="kpi-value">{open.length}</div>
        </div>
        <div className="card kpi">
          <div className="kpi-label">Receiving</div>
          <div className="kpi-value">
            {
              open.filter((t) => t.taskType.toUpperCase().includes("RECEIV"))
                .length
            }
          </div>
        </div>
        <div className="card kpi">
          <div className="kpi-label">Picking / dispatch</div>
          <div className="kpi-value">
            {open.filter((t) => /PICK|DISPATCH/i.test(t.taskType)).length}
          </div>
        </div>
      </div>
      <section className="grid grid-2" style={{ marginTop: 14 }}>
        <div className="card">
          <h2 className="card-title">Register warehouse</h2>
          <form className="form-grid" onSubmit={create}>
            <div className="field">
              <label>Name</label>
              <input
                value={form.name}
                onChange={(e) => setForm({ ...form, name: e.target.value })}
                required
              />
            </div>
            <div className="field">
              <label>Address</label>
              <input
                value={form.address}
                onChange={(e) => setForm({ ...form, address: e.target.value })}
                required
              />
            </div>
            <button className="btn btn-primary">Add warehouse</button>
          </form>
        </div>
        <div className="card">
          <h2 className="card-title">Warehouse network</h2>
          {warehouses.map((w) => (
            <div className="metric-row" key={w.id}>
              <span>
                <Link href={`/warehouses/${w.id}`}>
                  <strong>{w.name}</strong>
                </Link>
                <small>{w.address}</small>
              </span>
              <strong>
                {
                  tasks.filter(
                    (t) =>
                      t.warehouseId === w.id &&
                      !["COMPLETED", "CANCELLED"].includes(t.status),
                  ).length
                }{" "}
                open
              </strong>
            </div>
          ))}
        </div>
      </section>
      <section className="card" style={{ marginTop: 14 }}>
        <div className="page-head">
          <div>
            <h2 className="card-title">Warehouse task queue</h2>
            <div className="card-muted">
              The same task lifecycle is used for receiving, put-away, picking
              and dispatch.
            </div>
          </div>
        </div>
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Task</th>
                <th>Warehouse</th>
                <th>Shipment</th>
                <th>Quantity</th>
                <th>Locations</th>
                <th>Due</th>
                <th>Status</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {tasks.map((t) => (
                <tr key={t.id}>
                  <td>
                    <strong>{t.taskType}</strong>
                    <small>{t.assignedTo || "Unassigned"}</small>
                  </td>
                  <td>
                    {warehouses.find((w) => w.id === t.warehouseId)?.name ||
                      t.warehouseId}
                  </td>
                  <td>{t.shipmentId || "—"}</td>
                  <td>{t.quantity ?? "—"}</td>
                  <td>
                    {t.sourceLocation || "—"} → {t.destinationLocation || "—"}
                  </td>
                  <td>{t.dueAt ? new Date(t.dueAt).toLocaleString() : "—"}</td>
                  <td>
                    <span className="status status-neutral">{t.status}</span>
                  </td>
                  <td>
                    <div className="actions">
                      {t.status === "PENDING" && (
                        <button
                          className="btn"
                          onClick={() => taskAction(t.id, "start")}
                        >
                          Start
                        </button>
                      )}
                      {t.status === "IN_PROGRESS" && (
                        <button
                          className="btn btn-primary"
                          onClick={() => taskAction(t.id, "complete")}
                        >
                          Complete
                        </button>
                      )}
                      {["PENDING", "IN_PROGRESS"].includes(t.status) && (
                        <button
                          className="btn"
                          onClick={() => taskAction(t.id, "cancel")}
                        >
                          Cancel
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
              {!tasks.length && (
                <tr>
                  <td colSpan={8} className="empty">
                    No warehouse tasks.
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
