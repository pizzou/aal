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
        const storedToken = getAccessToken();

        /*
         * Do not call the authenticated session endpoint without
         * preserving the already stored bearer token.
         *
         * apiFetch attaches it automatically.
         */
        const session: AuthSessionResponse = await authApi.session();

        if (cancelled) return;

        if (session.role === "CUSTOMER") {
          try {
            await authApi.logout();
          } catch {
            // Customer accounts are not internal sessions.
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

        /*
         * Keep the actual bearer token in React state.
         *
         * Do not use the literal string "cookie" as an authentication
         * token. It causes the UI to believe it is authenticated while
         * the API may actually have no usable bearer credential.
         */
        setAccessTokenState(storedToken);

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
       * Do not immediately destroy the UI state for a transient
       * unauthorized request. The API layer owns token invalidation.
       *
       * Only clear the React authentication state when the token has
       * actually been removed.
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
     * The OTP verification response contains the real JWT.
     *
     * Store it immediately before navigating to the dashboard.
     */
    if (!token) {
      throw new Error("Authentication succeeded without an access token.");
    }

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
      // Always clear local state even if backend logout fails.
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
