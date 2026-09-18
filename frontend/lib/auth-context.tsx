"use client";

import {
  createContext,
  useContext,
  useState,
  useEffect,
  ReactNode,
} from "react";

import { authApi, AuthSessionResponse } from "@/lib/api-client";

interface AuthState {
  accessToken: string | null;
  tenantId: string | null;
  role: string | null;
  isLoading: boolean;
  login: (token: string, tenant: string, role: string) => void;
  logout: () => void;
}

const AuthContext = createContext<AuthState | null>(null);

let currentTenant: string | null = null;

export function getAccessToken(): string | null {
  /*
   * Authentication is intentionally cookie-based.
   * The HttpOnly NLS_SESSION cookie cannot and should not be read
   * by browser JavaScript.
   */
  return null;
}

export function getTenantId(): string | null {
  return currentTenant;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [tenantId, setTenantId] = useState<string | null>(null);

  const [role, setRole] = useState<string | null>(null);

  const [authenticated, setAuthenticated] = useState(false);

  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;

    async function restoreSession() {
      try {
        /*
         * authApi.session() is a GET and therefore does not require
         * the CSRF bootstrap request.
         *
         * The browser automatically sends NLS_SESSION when present.
         */
        const session: AuthSessionResponse = await authApi.session();

        if (cancelled) {
          return;
        }

        currentTenant = session.tenantId;

        setTenantId(session.tenantId);
        setRole(session.role);
        setAuthenticated(true);
        if (session.mustChangePassword && typeof window !== "undefined" && window.location.pathname !== "/account/security") {
          window.location.replace("/account/security");
          return;
        }
      } catch {
        if (cancelled) {
          return;
        }

        currentTenant = null;
        setTenantId(null);
        setRole(null);
        setAuthenticated(false);
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

  function login(_token: string, tenant: string, userRole: string) {
    /*
     * The JWT is stored by the backend in the HttpOnly NLS_SESSION
     * cookie. The frontend only stores the non-sensitive session state.
     */
    currentTenant = tenant;

    setTenantId(tenant);
    setRole(userRole);
    setAuthenticated(true);
  }

  async function logout() {
    try {
      await authApi.logout();
    } catch {
      /*
       * Clear local state even if the server-side logout request fails.
       * The next login/session check will establish the correct state.
       */
    }

    currentTenant = null;

    setTenantId(null);
    setRole(null);
    setAuthenticated(false);
  }

  return (
    <AuthContext.Provider
      value={{
        accessToken: authenticated ? "cookie" : null,
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
