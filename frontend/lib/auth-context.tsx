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
    const storedToken = getAccessToken();
    const storedContext = readStoredAuthContext();

    const clearLocalSession = () => {
      clearAccessToken();
      clearStoredAuthContext();
      currentTenant = null;
      setTenantId(null);
      setRole(null);
      setAccessTokenState(null);
    };

    /*
     * Do not hold the entire application behind a remote session round-trip.
     * On Render, a cold backend can take several seconds to wake. A valid JWT
     * plus the previously validated tenant/role is enough to render the UI;
     * protected API calls remain the server-side authorization boundary. The
     * session endpoint is validated in the background and immediately clears
     * state when the token is actually rejected.
     */
    if (!storedToken || !storedContext) {
      clearLocalSession();
      setIsLoading(false);
      return () => {
        cancelled = true;
      };
    }

    currentTenant = storedContext.tenantId;
    setTenantId(storedContext.tenantId);
    setRole(storedContext.role);
    setAccessTokenState(storedToken);
    setIsLoading(false);

    void (async () => {
      try {
        const session: AuthSessionResponse = await authApi.session();
        if (cancelled || getAccessToken() !== storedToken) return;

        if (!session.authenticated || !session.tenantId || !session.role) {
          clearLocalSession();
          return;
        }

        if (session.role === "CUSTOMER") {
          try {
            await authApi.logout();
          } catch {
            // Local authentication state must still be cleared.
          }
          clearLocalSession();
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
        }
      } catch (error) {
        if (cancelled || getAccessToken() !== storedToken) return;

        const isAuthenticationFailure =
          error instanceof Error &&
          /401|403|authentication required|invalid or expired token|token revoked/i.test(
            error.message,
          );

        /*
         * Network/cold-start failures intentionally do not clear the locally
         * restored session. The next protected request will retry and the
         * normal 401 handling will evict a genuinely expired token.
         */
        if (isAuthenticationFailure) {
          clearLocalSession();
        }
      }
    })();

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
