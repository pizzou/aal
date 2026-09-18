"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth-context";
import {
  ApiError,
  driversApi,
  Driver,
  gpsApi,
  GpsPosition,
  loadPlanningApi,
  LoadPlan,
  Trip,
  tripsApi,
  Vehicle,
  vehiclesApi,
} from "@/lib/api-client";

export default function DispatchFleetPage() {
  const { accessToken, isLoading } = useAuth();
  const router = useRouter();
  const [vehicles, setVehicles] = useState<Vehicle[]>([]);
  const [drivers, setDrivers] = useState<Driver[]>([]);
  const [trips, setTrips] = useState<Trip[]>([]);
  const [positions, setPositions] = useState<Record<string, GpsPosition>>({});
  const [loadPlan, setLoadPlan] = useState<LoadPlan | null>(null);
  const [error, setError] = useState("");
  const [vehicle, setVehicle] = useState({
    registrationNumber: "",
    vehicleType: "TRUCK",
    capacityKg: 1000,
  });
  const [driver, setDriver] = useState({
    fullName: "",
    licenseNumber: "",
    phone: "",
  });
  const [trip, setTrip] = useState({
    vehicleId: "",
    driverId: "",
    originAddress: "",
    destinationAddress: "",
  });
  async function refresh() {
    try {
      const [v, d, t] = await Promise.all([
        vehiclesApi.list(),
        driversApi.list(),
        tripsApi.list(),
      ]);
      setVehicles(v);
      setDrivers(d);
      setTrips(t.content);
      setError("");
    } catch (e) {
      setError(
        e instanceof ApiError ? e.message : "Unable to load dispatch control",
      );
    }
  }
  useEffect(() => {
    if (!isLoading && !accessToken) router.push("/login");
  }, [isLoading, accessToken, router]);
  useEffect(() => {
    if (accessToken) refresh();
  }, [accessToken]);
  async function action(fn: () => Promise<unknown>) {
    try {
      setError("");
      await fn();
      await refresh();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Operation failed");
    }
  }
  async function track(id: string) {
    try {
      setPositions({ ...positions, [id]: await gpsApi.latest(id) });
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "No current GPS position");
    }
  }
  if (isLoading || !accessToken) return null;
  const availableVehicles = vehicles.filter((v) => v.status === "AVAILABLE"),
    availableDrivers = drivers.filter((d) => d.status === "AVAILABLE");
  return (
    <main className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow">DISPATCH & FLEET CONTROL</div>
          <h1 className="page-title">Fleet Execution</h1>
          <p className="page-subtitle">
            Vehicles, drivers, trips, live position and load utilization in one
            operational desk.
          </p>
        </div>
        <Link className="btn btn-primary" href="/shipments">
          Assign shipments
        </Link>
      </div>
      {error && <div className="alert alert-error">{error}</div>}
      <div className="grid grid-4">
        <div className="card kpi">
          <div className="kpi-label">Vehicles</div>
          <div className="kpi-value">{vehicles.length}</div>
          <div className="kpi-meta">{availableVehicles.length} available</div>
        </div>
        <div className="card kpi">
          <div className="kpi-label">Drivers</div>
          <div className="kpi-value">{drivers.length}</div>
          <div className="kpi-meta">{availableDrivers.length} available</div>
        </div>
        <div className="card kpi">
          <div className="kpi-label">Active trips</div>
          <div className="kpi-value">
            {trips.filter((t) => t.status === "IN_PROGRESS").length}
          </div>
          <div className="kpi-meta">
            {trips.filter((t) => t.status === "PLANNED").length} planned
          </div>
        </div>
        <div className="card kpi">
          <div className="kpi-label">Utilization</div>
          <div className="kpi-value">
            {loadPlan ? `${loadPlan.utilizationPercent.toFixed(1)}%` : "—"}
          </div>
          <div className="kpi-meta">Select load plan on a vehicle</div>
        </div>
      </div>
      <section className="grid grid-2" style={{ marginTop: 14 }}>
        <div className="card">
          <h2 className="card-title">Register vehicle</h2>
          <form
            className="form-grid"
            onSubmit={(e) => {
              e.preventDefault();
              action(() => vehiclesApi.create(vehicle));
            }}
          >
            <div className="field">
              <label>Registration</label>
              <input
                value={vehicle.registrationNumber}
                onChange={(e) =>
                  setVehicle({ ...vehicle, registrationNumber: e.target.value })
                }
                required
              />
            </div>
            <div className="field">
              <label>Type</label>
              <select
                value={vehicle.vehicleType}
                onChange={(e) =>
                  setVehicle({ ...vehicle, vehicleType: e.target.value })
                }
              >
                <option>TRUCK</option>
                <option>VAN</option>
                <option>MOTORCYCLE</option>
              </select>
            </div>
            <div className="field">
              <label>Capacity kg</label>
              <input
                type="number"
                min="1"
                value={vehicle.capacityKg}
                onChange={(e) =>
                  setVehicle({ ...vehicle, capacityKg: Number(e.target.value) })
                }
              />
            </div>
            <button className="btn btn-primary">Register</button>
          </form>
        </div>
        <div className="card">
          <h2 className="card-title">Register driver</h2>
          <form
            className="form-grid"
            onSubmit={(e) => {
              e.preventDefault();
              action(() => driversApi.create(driver));
            }}
          >
            <div className="field">
              <label>Full name</label>
              <input
                value={driver.fullName}
                onChange={(e) =>
                  setDriver({ ...driver, fullName: e.target.value })
                }
                required
              />
            </div>
            <div className="field">
              <label>License</label>
              <input
                value={driver.licenseNumber}
                onChange={(e) =>
                  setDriver({ ...driver, licenseNumber: e.target.value })
                }
                required
              />
            </div>
            <div className="field">
              <label>Phone</label>
              <input
                value={driver.phone}
                onChange={(e) =>
                  setDriver({ ...driver, phone: e.target.value })
                }
              />
            </div>
            <button className="btn btn-primary">Register</button>
          </form>
        </div>
      </section>
      <section className="card" style={{ marginTop: 14 }}>
        <div className="page-head">
          <div>
            <h2 className="card-title">Vehicle readiness & GPS</h2>
            <div className="card-muted">
              Live positions are read from the GPS integration; no simulated
              production pings are exposed here.
            </div>
          </div>
        </div>
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Vehicle</th>
                <th>Type</th>
                <th>Capacity</th>
                <th>Status</th>
                <th>Last position</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {vehicles.map((v) => (
                <tr key={v.id}>
                  <td>
                    <strong>{v.registrationNumber}</strong>
                  </td>
                  <td>{v.vehicleType}</td>
                  <td>{v.capacityKg} kg</td>
                  <td>{v.status}</td>
                  <td>
                    {positions[v.id]
                      ? `${positions[v.id].latitude.toFixed(4)}, ${positions[v.id].longitude.toFixed(4)} · ${new Date(positions[v.id].recordedAt).toLocaleTimeString()}`
                      : "—"}
                  </td>
                  <td>
                    <div className="actions">
                      <button className="btn" onClick={() => track(v.id)}>
                        Refresh GPS
                      </button>
                      <button
                        className="btn"
                        onClick={() =>
                          action(async () =>
                            setLoadPlan(await loadPlanningApi.plan(v.id)),
                          )
                        }
                      >
                        Load plan
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
      <section className="card" style={{ marginTop: 14 }}>
        <div className="page-head">
          <div>
            <h2 className="card-title">Dispatch board</h2>
            <div className="card-muted">
              Create trips only from available fleet resources; complete or
              cancel through the same lifecycle.
            </div>
          </div>
        </div>
        <form
          className="form-grid"
          onSubmit={(e) => {
            e.preventDefault();
            action(() => tripsApi.create(trip));
          }}
        >
          <div className="field">
            <label>Vehicle</label>
            <select
              value={trip.vehicleId}
              onChange={(e) => setTrip({ ...trip, vehicleId: e.target.value })}
              required
            >
              <option value="">Select…</option>
              {availableVehicles.map((v) => (
                <option key={v.id} value={v.id}>
                  {v.registrationNumber}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label>Driver</label>
            <select
              value={trip.driverId}
              onChange={(e) => setTrip({ ...trip, driverId: e.target.value })}
              required
            >
              <option value="">Select…</option>
              {availableDrivers.map((d) => (
                <option key={d.id} value={d.id}>
                  {d.fullName}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label>Origin</label>
            <input
              value={trip.originAddress}
              onChange={(e) =>
                setTrip({ ...trip, originAddress: e.target.value })
              }
              required
            />
          </div>
          <div className="field">
            <label>Destination</label>
            <input
              value={trip.destinationAddress}
              onChange={(e) =>
                setTrip({ ...trip, destinationAddress: e.target.value })
              }
              required
            />
          </div>
          <button className="btn btn-primary">Plan trip</button>
        </form>
        <div className="table-wrap" style={{ marginTop: 14 }}>
          <table className="table">
            <thead>
              <tr>
                <th>Route</th>
                <th>Vehicle / driver</th>
                <th>Status</th>
                <th>Shipments</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {trips.map((t) => (
                <tr key={t.id}>
                  <td>
                    <strong>
                      {t.originAddress} → {t.destinationAddress}
                    </strong>
                  </td>
                  <td>
                    {t.vehicleId}
                    <small>{t.driverId}</small>
                  </td>
                  <td>
                    <span className="status status-neutral">{t.status}</span>
                  </td>
                  <td>{t.shipmentIds.length}</td>
                  <td>
                    <div className="actions">
                      {t.status === "PLANNED" && (
                        <button
                          className="btn"
                          onClick={() => action(() => tripsApi.start(t.id))}
                        >
                          Start
                        </button>
                      )}
                      {t.status === "IN_PROGRESS" && (
                        <button
                          className="btn btn-primary"
                          onClick={() => action(() => tripsApi.complete(t.id))}
                        >
                          Complete
                        </button>
                      )}
                      {["PLANNED", "IN_PROGRESS"].includes(t.status) && (
                        <button
                          className="btn"
                          onClick={() => action(() => tripsApi.cancel(t.id))}
                        >
                          Cancel
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
      {loadPlan && (
        <section className="card" style={{ marginTop: 14 }}>
          <h2 className="card-title">Latest load plan</h2>
          <div className="metric-row">
            <span>Loaded</span>
            <strong>
              {loadPlan.totalWeightLoadedKg} / {loadPlan.vehicleCapacityKg} kg
            </strong>
          </div>
          <div className="metric-row">
            <span>Utilization</span>
            <strong>{loadPlan.utilizationPercent.toFixed(1)}%</strong>
          </div>
          <div className="metric-row">
            <span>Selected</span>
            <strong>
              {loadPlan.selected.map((x) => x.referenceCode).join(", ") ||
                "None"}
            </strong>
          </div>
        </section>
      )}
    </main>
  );
}
