import { apiFetch } from "@/lib/api-client";

export type AalRole =
  | "ADMIN"
  | "MANAGER"
  | "OPERATIONS"
  | "SALES"
  | "FINANCE"
  | "DISPATCH"
  | "WAREHOUSE"
  | "AIR_CARGO"
  | "CUSTOMER";

export interface AalUser {
  id: string;
  email: string;
  role: AalRole;
  active: boolean;
  createdAt: string;
  customerClientId?: string | null;
}

export interface AalRoleDefinition {
  roleName: AalRole;
  displayName: string;
  description: string;
}

export const usersApi = {
  list: () => apiFetch<AalUser[]>("/api/users"),
  roles: () => apiFetch<AalRoleDefinition[]>("/api/users/roles"),
  create: (data: { email: string; displayName?: string; phone?: string; role: AalRole }) =>
    apiFetch<AalUser>("/api/users", {
      method: "POST",
      body: JSON.stringify(data),
    }),
  changeRole: (id: string, role: AalRole) =>
    apiFetch<AalUser>(`/api/users/${encodeURIComponent(id)}/role`, {
      method: "PATCH",
      body: JSON.stringify({ role }),
    }),
  updateEmail: (id: string, email: string) =>
    apiFetch<AalUser>(`/api/users/${encodeURIComponent(id)}/email`, {
      method: "PATCH",
      body: JSON.stringify({ email }),
    }),
  resetPassword: (id: string, password: string) =>
    apiFetch<AalUser>(`/api/users/${encodeURIComponent(id)}/password`, {
      method: "PATCH",
      body: JSON.stringify({ password }),
    }),
  deactivate: (id: string) =>
    apiFetch<AalUser>(`/api/users/${encodeURIComponent(id)}/deactivate`, {
      method: "POST",
    }),
  reactivate: (id: string) =>
    apiFetch<AalUser>(`/api/users/${encodeURIComponent(id)}/reactivate`, {
      method: "POST",
    }),
};
