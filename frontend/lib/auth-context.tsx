"use client";

import {
  createContext,
  useContext,
  useState,
  useEffect,
  ReactNode,
} from "react";

import {
  authApi,
  AuthSessionResponse,
  clearAccessToken,
  getAccessToken,
  setAccessToken,
} from "@/lib/api-client";

interface AuthState {
  accessToken: string | null;
  tenantId: string | null;
  role: string | null;
  isLoading: boolean;
  login: (token: string | null, tenant: string, role: string) => void;
  logout: () => void;
}

const AUTH_EXPIRED_EVENT = "aal:auth-expired";
const AUTH_CONTEXT_STORAGE_KEY = "aal.auth-context";

type StoredAuthContext = {
  tenantId: string;
  role: string;
};

const AuthContext = createContext<AuthState | null>(null);

let currentTenant: string | null = null;

export function getTenantId(): string | null {
  return currentTenant;
}

function readStoredAuthContext(): StoredAuthContext | null {
  if (typeof window === "undefined") return null;

  try {
    const raw = window.sessionStorage.getItem(AUTH_CONTEXT_STORAGE_KEY);
    if (!raw) return null;

    const parsed = JSON.parse(raw) as Partial<StoredAuthContext>;
    if (
      typeof parsed.tenantId !== "string" ||
      !parsed.tenantId.trim() ||
      typeof parsed.role !== "string" ||
      !parsed.role.trim()
    ) {
      window.sessionStorage.removeItem(AUTH_CONTEXT_STORAGE_KEY);
      return null;
    }

    return {
      tenantId: parsed.tenantId,
      role: parsed.role,
    };
  } catch {
    return null;
  }
}

function writeStoredAuthContext(tenantId: string, role: string): void {
  if (typeof window === "undefined") return;

  try {
    window.sessionStorage.setItem(
      AUTH_CONTEXT_STORAGE_KEY,
      JSON.stringify({ tenantId, role }),
    );
  } catch {
    // Session storage can be unavailable in privacy-restricted browsers.
  }
}

function clearStoredAuthContext(): void {
  if (typeof window === "undefined") return;

  try {
    window.sessionStorage.removeItem(AUTH_CONTEXT_STORAGE_KEY);
  } catch {
    // Ignore storage cleanup failures; the in-memory session is still cleared.
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [tenantId, setTenantId] = useState<string | null>(null);
  const [role, setRole] = useState<string | null>(null);
  const [accessToken, setAccessTokenState] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;

    async function restoreSession() {
      const storedToken = getAccessToken();
      const storedContext = readStoredAuthContext();

      try {
        if (!storedToken) {
          clearAccessToken();
          clearStoredAuthContext();

          currentTenant = null;
          setTenantId(null);
          setRole(null);
          setAccessTokenState(null);
          return;
        }

        /*
         * The server remains the source of truth for authentication and role.
         * The cached context below is only a continuity fallback when a full
         * page refresh happens while the API is temporarily unavailable.
         */
        const session: AuthSessionResponse = await authApi.session();

        if (cancelled) return;

        if (!session.authenticated || !session.tenantId || !session.role) {
          throw new Error("Authentication required");
        }

        if (session.role === "CUSTOMER") {
          try {
            await authApi.logout();
          } catch {
            // Local authentication state must still be cleared.
          }

          clearAccessToken();
          clearStoredAuthContext();

          currentTenant = null;
          setTenantId(null);
          setRole(null);
          setAccessTokenState(null);
          return;
        }

        currentTenant = session.tenantId;
        writeStoredAuthContext(session.tenantId, session.role);

        setTenantId(session.tenantId);
        setRole(session.role);
        setAccessTokenState(storedToken);

        if (
          session.mustChangePassword &&
          typeof window !== "undefined" &&
          window.location.pathname !== "/account/security"
        ) {
          window.location.replace("/account/security");
          return;
        }
      } catch (error) {
        if (cancelled) return;

        const isAuthenticationFailure =
          error instanceof Error &&
          /401|403|authentication required|invalid or expired token|token revoked/i.test(
            error.message,
          );

        /*
         * A refresh can race the backend cold start, network recovery, or a
         * temporary Render/Supabase delay. Do not throw away a valid local JWT
         * or render an empty role-filtered sidebar in that situation.
         *
         * The cached tenant/role is UI continuity only. Every protected API
         * request is still authenticated by the backend, so this does not grant
         * permissions by itself.
         */
        if (storedToken && storedContext && !isAuthenticationFailure) {
          currentTenant = storedContext.tenantId;
          setTenantId(storedContext.tenantId);
          setRole(storedContext.role);
          setAccessTokenState(storedToken);
          return;
        }

        clearAccessToken();
        clearStoredAuthContext();

        currentTenant = null;
        setTenantId(null);
        setRole(null);
        setAccessTokenState(null);
      } finally {
        if (!cancelled) {
          setIsLoading(false);
        }
      }
    }

    restoreSession();

    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    const handleAuthenticationExpired = () => {
      const token = getAccessToken();

      if (token) return;

      clearStoredAuthContext();
      currentTenant = null;

      setTenantId(null);
      setRole(null);
      setAccessTokenState(null);
    };

    window.addEventListener(AUTH_EXPIRED_EVENT, handleAuthenticationExpired);

    return () => {
      window.removeEventListener(
        AUTH_EXPIRED_EVENT,
        handleAuthenticationExpired,
      );
    };
  }, []);

  function login(token: string | null, tenant: string, userRole: string) {
    if (!token) {
      throw new Error("Authentication succeeded without an access token.");
    }

    setAccessToken(token);
    writeStoredAuthContext(tenant, userRole);

    currentTenant = tenant;

    setTenantId(tenant);
    setRole(userRole);
    setAccessTokenState(token);
  }

  async function logout() {
    try {
      await authApi.logout();
    } catch {
      // Local credentials must always be removed even when backend logout fails.
    }

    clearAccessToken();
    clearStoredAuthContext();

    currentTenant = null;

    setTenantId(null);
    setRole(null);
    setAccessTokenState(null);
  }

  return (
    <AuthContext.Provider
      value={{
        accessToken,
        tenantId,
        role,
        isLoading,
        login,
        logout,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);

  if (!ctx) {
    throw new Error("useAuth must be used within AuthProvider");
  }

  return ctx;
}
