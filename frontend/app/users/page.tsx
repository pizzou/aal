"use client";

import { FormEvent, useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { ApiError } from "@/lib/api-client";
import { useAuth } from "@/lib/auth-context";
import { AalRole, AalRoleDefinition, AalUser, usersApi } from "@/lib/users-api";

const fallbackRoles: AalRoleDefinition[] = [
  ["ADMIN", "Administrator", "Full platform administration and governance."],
  ["MANAGER", "Manager", "Operational and commercial management."],
  ["OPERATIONS", "Operations", "Shipment execution and operational control."],
  ["SALES", "Sales", "Customers, quotations and commercial pipeline."],
  ["FINANCE", "Finance", "Billing, receivables and financial control."],
  ["DISPATCH", "Dispatch", "Fleet, drivers and dispatch execution."],
  ["WAREHOUSE", "Warehouse", "Warehouse and inventory execution."],
  [
    "AIR_CARGO",
    "Air Cargo",
    "Air cargo, flights, AWB and air-freight execution.",
  ],
  ["CUSTOMER", "Customer", "Customer portal access for shipments, quotations, documents and invoices."],
].map(([roleName, displayName, description]) => ({
  roleName: roleName as AalRole,
  displayName,
  description,
}));

function messageFor(error: unknown) {
  return error instanceof ApiError || error instanceof Error
    ? error.message
    : "The request could not be completed.";
}

export default function UsersPage() {
  const { accessToken, role, isLoading } = useAuth();
  const router = useRouter();
  const [users, setUsers] = useState<AalUser[]>([]);
  const [roles, setRoles] = useState<AalRoleDefinition[]>(fallbackRoles);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");
  const [search, setSearch] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [email, setEmail] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [phone, setPhone] = useState("");
  const [newRole, setNewRole] = useState<AalRole>("OPERATIONS");
  const [draftRoles, setDraftRoles] = useState<Record<string, AalRole>>({});
  const [editUser, setEditUser] = useState<AalUser | null>(null);
  const [editEmail, setEditEmail] = useState("");
  const [editPassword, setEditPassword] = useState("");

  useEffect(() => {
    if (!isLoading && (!accessToken || role !== "ADMIN")) {
      router.replace("/dashboard");
    }
  }, [isLoading, accessToken, role, router]);

  async function load() {
    if (!accessToken || role !== "ADMIN") return;
    setLoading(true);
    setError("");
    try {
      const [u, r] = await Promise.all([usersApi.list(), usersApi.roles()]);
      setUsers(u);
      if (r.length) setRoles(r);
      setDraftRoles(Object.fromEntries(u.map((x) => [x.id, x.role])));
    } catch (e) {
      setError(messageFor(e));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, [accessToken, role]);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return users;
    return users.filter((u) =>
      `${u.email} ${u.role} ${u.active ? "active" : "inactive"}`
        .toLowerCase()
        .includes(q),
    );
  }, [users, search]);

  async function createUser(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError("");
    setSuccess("");
    try {
      const user = await usersApi.create({
        email: email.trim().toLowerCase(),
        displayName: displayName.trim() || undefined,
        phone: phone.trim() || undefined,
        role: newRole,
      });
      setUsers((current) => [user, ...current]);
      setDraftRoles((current) => ({ ...current, [user.id]: user.role }));
      setEmail("");
      setDisplayName("");
      setPhone("");
      setNewRole("OPERATIONS");
      setCreateOpen(false);
      setSuccess(`${user.email} was created successfully.`);
    } catch (e) {
      setError(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  async function saveRole(user: AalUser) {
    const selected = draftRoles[user.id] ?? user.role;
    if (selected === user.role) return;
    setBusy(true);
    setError("");
    setSuccess("");
    try {
      const updated = await usersApi.changeRole(user.id, selected);
      setUsers((current) =>
        current.map((x) => (x.id === updated.id ? updated : x)),
      );
      setDraftRoles((current) => ({ ...current, [updated.id]: updated.role }));
      setSuccess(`Role updated for ${updated.email}.`);
    } catch (e) {
      setError(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  async function saveEmail() {
    if (!editUser) return;
    setBusy(true); setError(""); setSuccess("");
    try {
      const updated = await usersApi.updateEmail(editUser.id, editEmail.trim().toLowerCase());
      setUsers(current => current.map(x => x.id === updated.id ? updated : x));
      setEditUser(null); setEditEmail("");
      setSuccess(`Email updated for ${updated.email}.`);
    } catch (e) { setError(messageFor(e)); } finally { setBusy(false); }
  }

  async function savePassword() {
    if (!editUser) return;
    setBusy(true); setError(""); setSuccess("");
    try {
      await usersApi.resetPassword(editUser.id, editPassword);
      setEditPassword(""); setEditUser(null);
      setSuccess(`Password reset for ${editUser.email}. Existing sessions were revoked.`);
    } catch (e) { setError(messageFor(e)); } finally { setBusy(false); }
  }

  async function toggle(user: AalUser) {
    if (
      !window.confirm(
        user.active ? `Deactivate ${user.email}?` : `Reactivate ${user.email}?`,
      )
    )
      return;
    setBusy(true);
    setError("");
    setSuccess("");
    try {
      const updated = user.active
        ? await usersApi.deactivate(user.id)
        : await usersApi.reactivate(user.id);
      setUsers((current) =>
        current.map((x) => (x.id === updated.id ? updated : x)),
      );
      setSuccess(
        `${updated.email} is now ${updated.active ? "active" : "inactive"}.`,
      );
    } catch (e) {
      setError(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  if (isLoading || !accessToken || role !== "ADMIN") return null;

  const active = users.filter((u) => u.active).length;
  const admins = users.filter((u) => u.active && u.role === "ADMIN").length;

  return (
    <main className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow">ADMINISTRATION</div>
          <h1 className="page-title">Users & Access</h1>
          <p className="page-subtitle">
            Provision AAL staff, assign operational roles and control account
            access from the canonical administration workspace.
          </p>
        </div>
        <button
          className="btn btn-primary"
          onClick={() => setCreateOpen((v) => !v)}
        >
          {createOpen ? "Close form" : "Create user"}
        </button>
      </div>

      {error && (
        <div className="alert alert-error" style={{ marginBottom: 14 }}>
          {error}
        </div>
      )}
      {success && (
        <div className="alert alert-success" style={{ marginBottom: 14 }}>
          {success}
        </div>
      )}

      <section className="grid grid-3">
        <div className="card kpi">
          <div className="kpi-label">Total users</div>
          <div className="kpi-value">{users.length}</div>
        </div>
        <div className="card kpi">
          <div className="kpi-label">Active users</div>
          <div className="kpi-value">{active}</div>
        </div>
        <div className="card kpi">
          <div className="kpi-label">Active administrators</div>
          <div className="kpi-value">{admins}</div>
        </div>
      </section>

      {createOpen && (
        <section className="card" style={{ marginTop: 16 }}>
          <h2 className="card-title">Create staff account</h2>
          <form className="form-grid" onSubmit={createUser}>
            <div className="field">
              <label>Email</label>
              <input
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                required
                maxLength={255}
              />
            </div>
            <div className="field"><label>Display name</label><input value={displayName} onChange={e=>setDisplayName(e.target.value)} placeholder="Full name" /></div>
            <div className="field"><label>Phone (optional)</label><input value={phone} onChange={e=>setPhone(e.target.value)} placeholder="+250…" /></div>
            <div className="alert" style={{fontSize:12}}>AAL generates a secure temporary password and emails the login credentials. The user must change it after the first sign-in.</div>
            <div className="field">
              <label>Role</label>
              <select
                value={newRole}
                onChange={(e) => setNewRole(e.target.value as AalRole)}
              >
                {roles.map((r) => (
                  <option key={r.roleName} value={r.roleName}>
                    {r.displayName}
                  </option>
                ))}
              </select>
            </div>
            <button className="btn btn-primary" disabled={busy}>
              Create account
            </button>
          </form>
        </section>
      )}

      <section className="card" style={{ marginTop: 16 }}>
        <div className="page-head">
          <div>
            <h2 className="card-title">Staff directory</h2>
            <div className="card-muted">
              Role and access changes invalidate the affected user's existing
              JWT session.
            </div>
          </div>
          <input
            className="search"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search users…"
          />
        </div>
        {loading ? (
          <div className="empty">Loading users…</div>
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>User</th>
                  <th>Role</th>
                  <th>Status</th>
                  <th>Created</th>
                  <th>Access</th>
                </tr>
              </thead>
              <tbody>
                {filtered.map((user) => {
                  const selected = draftRoles[user.id] ?? user.role;
                  return (
                    <tr key={user.id}>
                      <td>
                        <strong>{user.email}</strong>
                        <small>{user.id}</small>
                      </td>
                      <td>
                        <div className="actions">
                          <select
                            className="input"
                            value={selected}
                            onChange={(e) =>
                              setDraftRoles((c) => ({
                                ...c,
                                [user.id]: e.target.value as AalRole,
                              }))
                            }
                          >
                            {roles.map((r) => (
                              <option key={r.roleName} value={r.roleName}>
                                {r.displayName}
                              </option>
                            ))}
                          </select>
                          {selected !== user.role && (
                            <button className="btn btn-primary" disabled={busy} onClick={() => void saveRole(user)}>Save</button>
                          )}
                          <button className="btn" disabled={busy} onClick={() => { setEditUser(user); setEditEmail(user.email); setEditPassword(""); }}>Manage</button>
                        </div>
                      </td>
                      <td>
                        <span
                          className={
                            user.active
                              ? "status status-success"
                              : "status status-danger"
                          }
                        >
                          {user.active ? "ACTIVE" : "INACTIVE"}
                        </span>
                      </td>
                      <td>{new Date(user.createdAt).toLocaleDateString()}</td>
                      <td>
                        <button
                          className="btn"
                          disabled={busy}
                          onClick={() => void toggle(user)}
                        >
                          {user.active ? "Deactivate" : "Activate"}
                        </button>
                      </td>
                    </tr>
                  );
                })}
                {!filtered.length && (
                  <tr>
                    <td colSpan={5} className="empty">
                      No users found.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {editUser && (
        <div className="modal-backdrop" role="dialog" aria-modal="true">
          <section className="modal-card">
            <div className="page-head">
              <div><div className="eyebrow">ACCOUNT ADMINISTRATION</div><h2 className="card-title">Manage {editUser.email}</h2></div>
              <button className="icon-btn" onClick={() => setEditUser(null)} aria-label="Close">×</button>
            </div>
            <div className="form-grid">
              <div className="field"><label>Email address</label><input type="email" value={editEmail} onChange={e => setEditEmail(e.target.value)} /></div>
              <div className="field"><label>New password</label><input type="password" minLength={10} maxLength={128} value={editPassword} onChange={e => setEditPassword(e.target.value)} placeholder="Leave blank to keep current password" /></div>
            </div>
            <div className="actions" style={{marginTop:14}}>
              <button className="btn btn-primary" disabled={busy || !editEmail.trim()} onClick={() => void saveEmail()}>Update email</button>
              <button className="btn" disabled={busy || editPassword.length < 10} onClick={() => void savePassword()}>Change password</button>
              <button className="btn" onClick={() => setEditUser(null)}>Close</button>
            </div>
          </section>
        </div>
      )}

      <section className="card" style={{ marginTop: 16 }}>
        <h2 className="card-title">AAL role catalogue</h2>
        <div className="grid grid-4">
          {roles.map((r) => (
            <div className="card" key={r.roleName}>
              <strong>{r.displayName}</strong>
              <div className="card-muted" style={{ marginTop: 6 }}>
                {r.description}
              </div>
            </div>
          ))}
        </div>
      </section>
    </main>
  );
}
