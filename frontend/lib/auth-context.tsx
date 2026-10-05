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
  COOKIE_SESSION_SENTINEL,
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
    let loadingWatchdog: number | null = null;
    const storedToken = getAccessToken();
    const storedContext = readStoredAuthContext();
    const pathname =
      typeof window !== "undefined" ? window.location.pathname : "/";

    const clearLocalSession = () => {
      clearAccessToken();
      clearStoredAuthContext();
      currentTenant = null;
      setTenantId(null);
      setRole(null);
      setAccessTokenState(null);
    };

    /*
     * A successful OTP login persists both the bearer token and the validated
     * tenant/role context. Restore both synchronously on browser refresh so a
     * slow backend can never turn a refresh into an apparent logout.
     * Server-side APIs remain the source of truth for authorization.
     */
    // Restore a bearer credential immediately. This prevents a browser refresh
    // from depending on a cold/waking API before the application can render.
    if (storedToken) {
      setAccessTokenState(storedToken);
    }

    if (storedToken && storedContext) {
      currentTenant = storedContext.tenantId;
      setTenantId(storedContext.tenantId);
      setRole(storedContext.role);
      setAccessTokenState(storedToken);

      /*
       * Do not block the workspace on a remote session check. The stored
       * credential/context is enough to hydrate the shell immediately; the
       * backend remains the authorization source of truth. Protected requests
       * perform the real server-side validation. This is important on a hard
       * browser refresh when the Render service is cold.
       */
      setIsLoading(false);
    }

    const publicPage =
      pathname === "/" ||
      pathname === "/login" ||
      pathname === "/forgot-password" ||
      pathname === "/reset-password" ||
      pathname === "/quote" ||
      pathname === "/book" ||
      pathname === "/track" ||
      pathname.startsWith("/track/") ||
      pathname.startsWith("/quote/view/") ||
      pathname.startsWith("/quote/results/");

    /*
     * Public pages must render immediately. In particular /login cannot wait
     * for Render to wake the API service.
     */
    if (publicPage && !storedToken) {
      clearLocalSession();
      setIsLoading(false);
      return () => {
        cancelled = true;
      };
    }

    /*
     * A complete bearer session is authoritative enough to hydrate the
     * workspace immediately. Do NOT block a browser refresh on /api/auth/session:
     * that endpoint performs a database-backed JWT validation and therefore turns
     * a Render cold start or temporary DB saturation into an apparent logout.
     *
     * The first protected API request still validates the JWT server-side. A
     * definitive 401 clears the local session through api-client.ts.
     */
    if (storedToken && storedContext) {
      setIsLoading(false);
      return () => {
        cancelled = true;
      };
    }

    /*
     * If sessionStorage is unavailable but the HttpOnly cookie exists, recover
     * it once. This is the exceptional fallback path; it is never used when a
     * complete local bearer session is already available.
     */
    loadingWatchdog = window.setTimeout(() => {
      if (cancelled) return;
      setIsLoading(false);
      clearLocalSession();
    }, 5000);

    void (async () => {
      try {
        const session: AuthSessionResponse = await authApi.session({
          cache: "no-store",
        });

        if (cancelled) return;

        if (!session.authenticated || !session.tenantId || !session.role) {
          clearLocalSession();
          return;
        }

        currentTenant = session.tenantId;
        writeStoredAuthContext(session.tenantId, session.role);
        setTenantId(session.tenantId);
        setRole(session.role);

        setAccessToken(COOKIE_SESSION_SENTINEL);
        setAccessTokenState(COOKIE_SESSION_SENTINEL);

        if (
          session.mustChangePassword &&
          typeof window !== "undefined" &&
          window.location.pathname !== "/account/security"
        ) {
          window.location.replace("/account/security");
          return;
        }
      } catch {
        if (cancelled) return;
        clearLocalSession();
      } finally {
        if (loadingWatchdog !== null) {
          window.clearTimeout(loadingWatchdog);
          loadingWatchdog = null;
        }
        if (!cancelled) setIsLoading(false);
      }
    })();

    return () => {
      cancelled = true;
      if (loadingWatchdog !== null) {
        window.clearTimeout(loadingWatchdog);
        loadingWatchdog = null;
      }
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
