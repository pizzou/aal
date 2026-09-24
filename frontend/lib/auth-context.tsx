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
      /*
       * IMPORTANT:
       * Keep storedToken outside the try block because both the
       * success and error paths need access to the same token.
       */
      const storedToken = getAccessToken();

      try {
        /*
         * There is no reason to call the authenticated session
         * endpoint when no bearer token exists.
         */
        if (!storedToken) {
          clearAccessToken();

          currentTenant = null;
          setTenantId(null);
          setRole(null);
          setAccessTokenState(null);

          return;
        }

        /*
         * apiFetch is responsible for attaching the stored bearer token.
         */
        const session: AuthSessionResponse = await authApi.session();

        if (cancelled) return;

        /*
         * Customer accounts are not internal operations sessions.
         */
        if (session.role === "CUSTOMER") {
          try {
            await authApi.logout();
          } catch {
            // Local authentication state must still be cleared.
          }

          clearAccessToken();

          currentTenant = null;
          setTenantId(null);
          setRole(null);
          setAccessTokenState(null);

          return;
        }

        /*
         * Restore the complete authenticated session.
         */
        currentTenant = session.tenantId;

        setTenantId(session.tenantId);
        setRole(session.role);
        setAccessTokenState(storedToken);

        /*
         * Force password change when required by the backend.
         */
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
          /401|authentication required|invalid or expired token/i.test(
            error.message,
          );

        /*
         * If a token still exists and this was not an authentication
         * failure, preserve the session. This prevents a temporary
         * backend/network failure from logging the user out.
         */
        if (storedToken && !isAuthenticationFailure) {
          setAccessTokenState(storedToken);
          return;
        }

        /*
         * A genuine authentication failure invalidates the local
         * session.
         */
        clearAccessToken();

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
      /*
       * Do not destroy the authenticated UI for a transient 401
       * while the API layer still has a valid token.
       */
      const token = getAccessToken();

      if (token) {
        return;
      }

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
    /*
     * OTP verification must return the actual JWT.
     */
    if (!token) {
      throw new Error("Authentication succeeded without an access token.");
    }

    /*
     * Persist the real JWT before navigating to the dashboard.
     */
    setAccessToken(token);

    currentTenant = tenant;

    setTenantId(tenant);
    setRole(userRole);
    setAccessTokenState(token);
  }

  async function logout() {
    try {
      await authApi.logout();
    } catch {
      /*
       * Local credentials must always be removed even when
       * the backend logout request fails.
       */
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
