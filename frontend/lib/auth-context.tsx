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

type JwtBootstrap = {
  tenantId?: string;
  role?: string;
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
    return { tenantId: parsed.tenantId, role: parsed.role };
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
    // Storage can be unavailable in privacy-restricted browsers.
  }
}

function clearStoredAuthContext(): void {
  if (typeof window === "undefined") return;
  try {
    window.sessionStorage.removeItem(AUTH_CONTEXT_STORAGE_KEY);
  } catch {
    // Ignore storage cleanup failures.
  }
}

/*
 * The JWT is never trusted for authorization on the client. It is only used
 * to restore the already-issued UI context synchronously during a hard refresh.
 * Every protected API call is still authorized by the verified server token.
 */
function decodeJwtBootstrap(token: string | null): JwtBootstrap | null {
  if (
    !token ||
    token === COOKIE_SESSION_SENTINEL ||
    typeof window === "undefined"
  ) {
    return null;
  }

  try {
    const parts = token.split(".");
    if (parts.length !== 3) return null;

    const normalized = parts[1].replace(/-/g, "+").replace(/_/g, "/");
    const padded = normalized.padEnd(
      normalized.length + ((4 - (normalized.length % 4)) % 4),
      "=",
    );
    const payload = JSON.parse(window.atob(padded)) as Record<string, unknown>;

    return {
      tenantId:
        typeof payload.tenantId === "string" ? payload.tenantId : undefined,
      role: typeof payload.role === "string" ? payload.role : undefined,
    };
  } catch {
    return null;
  }
}

function isPublicPath(pathname: string): boolean {
  return (
    pathname === "/" ||
    pathname === "/login" ||
    pathname === "/forgot-password" ||
    pathname === "/reset-password" ||
    pathname === "/quote" ||
    pathname === "/book" ||
    pathname === "/track" ||
    pathname.startsWith("/track/") ||
    pathname.startsWith("/quote/view/") ||
    pathname.startsWith("/quote/results/")
  );
}

export function AuthProvider({ children }: { children: ReactNode }) {
  /*
   * Browser credentials are intentionally read inside useEffect so the server
   * render and client hydration remain identical. The shell itself never
   * blocks an already-routed workspace behind this loading state.
   */
  const [tenantId, setTenantId] = useState<string | null>(null);
  const [role, setRole] = useState<string | null>(null);
  const [accessToken, setAccessTokenState] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    let loadingWatchdog: number | null = null;

    const storedToken = getAccessToken();
    const storedContext = readStoredAuthContext();
    const jwt = decodeJwtBootstrap(storedToken);
    const bootstrapped =
      storedContext ??
      (jwt?.tenantId && jwt.role
        ? { tenantId: jwt.tenantId, role: jwt.role }
        : null);

    if (storedToken) setAccessTokenState(storedToken);

    if (bootstrapped) {
      currentTenant = bootstrapped.tenantId;
      setTenantId(bootstrapped.tenantId);
      setRole(bootstrapped.role);
      writeStoredAuthContext(bootstrapped.tenantId, bootstrapped.role);
      setIsLoading(false);
    }

    const pathname =
      typeof window !== "undefined" ? window.location.pathname : "/";

    if (isPublicPath(pathname) && !storedToken) {
      setIsLoading(false);
      return () => {
        cancelled = true;
      };
    }

    /*
     * Bearer sessions do not need a blocking /session request. A protected API
     * request will verify the JWT. A Render/network timeout must never destroy
     * a valid local session and send the operator back to the login screen.
     */
    if (storedToken && bootstrapped) {
      setIsLoading(false);
      return () => {
        cancelled = true;
      };
    }

    /*
     * Cookie-only fallback. This path is intentionally isolated and bounded.
     * Network errors leave the browser session alone; only a definitive
     * unauthenticated response clears it.
     */
    loadingWatchdog = window.setTimeout(() => {
      if (cancelled) return;
      setIsLoading(false);
    }, 5000);

    void (async () => {
      try {
        const session: AuthSessionResponse = await authApi.session({
          cache: "no-store",
        });

        if (cancelled) return;

        if (!session.authenticated || !session.tenantId || !session.role) {
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

        setAccessToken(COOKIE_SESSION_SENTINEL);
        setAccessTokenState(COOKIE_SESSION_SENTINEL);

        if (
          session.mustChangePassword &&
          window.location.pathname !== "/account/security"
        ) {
          window.location.replace("/account/security");
          return;
        }
      } catch {
        /*
         * A timeout/cold-start is not proof that authentication is invalid.
         * Keep any existing browser credential and let protected calls decide.
         */
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
      /*
       * api-client clears the bearer before emitting this event. Therefore an
       * event with no token is a real server-side 401 and can clear the UI.
       */
      if (getAccessToken()) return;

      clearStoredAuthContext();
      currentTenant = null;
      setTenantId(null);
      setRole(null);
      setAccessTokenState(null);
      setIsLoading(false);
    };

    window.addEventListener(AUTH_EXPIRED_EVENT, handleAuthenticationExpired);
    return () =>
      window.removeEventListener(
        AUTH_EXPIRED_EVENT,
        handleAuthenticationExpired,
      );
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
    setIsLoading(false);
  }

  async function logout() {
    try {
      await authApi.logout();
    } catch {
      // Local credentials must always be removed.
    }

    clearAccessToken();
    clearStoredAuthContext();
    currentTenant = null;
    setTenantId(null);
    setRole(null);
    setAccessTokenState(null);
    setIsLoading(false);
  }

  return (
    <AuthContext.Provider
      value={{ accessToken, tenantId, role, isLoading, login, logout }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within AuthProvider");
  return ctx;
}
