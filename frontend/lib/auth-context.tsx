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
const AuthContext = createContext<AuthState | null>(null);

let currentTenant: string | null = null;

export function getTenantId(): string | null {
  return currentTenant;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [tenantId, setTenantId] = useState<string | null>(null);
  const [role, setRole] = useState<string | null>(null);
  const [accessToken, setAccessTokenState] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;

    async function restoreSession() {
      try {
        const session: AuthSessionResponse = await authApi.session();

        if (cancelled) return;

        if (!session.authenticated) {
          clearAccessToken();
          currentTenant = null;
          setTenantId(null);
          setRole(null);
          setAccessTokenState(null);
          return;
        }

        if (session.role === "CUSTOMER") {
          try {
            await authApi.logout();
          } catch {
            // Ignore cleanup errors; customer accounts are not internal sessions.
          }
          clearAccessToken();
          currentTenant = null;
          setTenantId(null);
          setRole(null);
          setAccessTokenState(null);
          return;
        }

        currentTenant = session.tenantId;
        setTenantId(session.tenantId);
        setRole(session.role);

        // A bearer token is used when the frontend and API are deployed on
        // different sites. The HttpOnly cookie remains a server-side fallback.
        setAccessTokenState(getAccessToken() ?? "cookie");

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

        clearAccessToken();
        currentTenant = null;
        setTenantId(null);
        setRole(null);
        setAccessTokenState(null);
      } finally {
        if (!cancelled) setIsLoading(false);
      }
    }

    restoreSession();

    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    const handleAuthenticationExpired = () => {
      currentTenant = null;
      setTenantId(null);
      setRole(null);
      setAccessTokenState(null);
    };

    window.addEventListener(AUTH_EXPIRED_EVENT, handleAuthenticationExpired);
    return () =>
      window.removeEventListener(
        AUTH_EXPIRED_EVENT,
        handleAuthenticationExpired,
      );
  }, []);

  function login(token: string | null, tenant: string, userRole: string) {
    if (token) setAccessToken(token);

    currentTenant = tenant;
    setTenantId(tenant);
    setRole(userRole);
    setAccessTokenState(token ?? getAccessToken() ?? "cookie");
  }

  async function logout() {
    try {
      await authApi.logout();
    } catch {
      // Always clear the browser session even if the server is unavailable.
    }

    clearAccessToken();
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
