import { NextRequest, NextResponse } from "next/server";

function nonce() {
  return btoa(crypto.randomUUID()).replace(/=+$/g, "");
}

export function middleware(request: NextRequest) {
  const path = request.nextUrl.pathname;

  if (
    path === "/portal" ||
    path.startsWith("/portal/") ||
    path === "/customer-portal" ||
    path.startsWith("/customer-portal/")
  ) {
    return NextResponse.redirect(new URL("/", request.url));
  }

  const value = nonce();
  const requestHeaders = new Headers(request.headers);
  requestHeaders.set("x-nonce", value);

  const apiOrigin = (() => {
    const configured = process.env.NEXT_PUBLIC_API_BASE_URL?.trim();
    if (!configured) {
      return process.env.NODE_ENV === "production"
        ? "https://aal-ocst.onrender.com"
        : "http://localhost:8080";
    }
    try {
      return new URL(configured).origin;
    } catch {
      return process.env.NODE_ENV === "production"
        ? "https://aal-ocst.onrender.com"
        : "http://localhost:8080";
    }
  })();

  const cspHeader = [
    "default-src 'self'",
    "base-uri 'self'",
    "object-src 'none'",
    "frame-ancestors 'none'",
    "form-action 'self'",
    "img-src 'self' data: blob:",
    "font-src 'self' data:",
    "style-src 'self' 'unsafe-inline'",
    `script-src 'self' 'nonce-${value}' 'strict-dynamic'`,
    `connect-src 'self' ${apiOrigin} wss:`,
    "worker-src 'self' blob:",
    "manifest-src 'self'",
    ...(process.env.NODE_ENV === "production"
      ? ["upgrade-insecure-requests"]
      : []),
  ].join("; ");

  /*
   * Next.js reads the CSP from the incoming request when applying the nonce
   * to its own generated inline/bootstrap scripts. Setting the policy only
   * on the response leaves those scripts without the nonce and can block
   * hydration, which makes app/loading.tsx appear to hang forever.
   */
  requestHeaders.set("Content-Security-Policy", cspHeader);

  const response = NextResponse.next({
    request: { headers: requestHeaders },
  });

  response.headers.set("X-Content-Type-Options", "nosniff");
  response.headers.set("X-Frame-Options", "DENY");
  response.headers.set("Referrer-Policy", "strict-origin-when-cross-origin");
  response.headers.set(
    "Permissions-Policy",
    "camera=(self), microphone=(), geolocation=(self)",
  );
  response.headers.set("Content-Security-Policy", cspHeader);

  return response;
}

export const config = {
  matcher: [
    {
      source: "/((?!api|_next/static|_next/image|favicon.ico).*)",
      missing: [
        { type: "header", key: "next-router-prefetch" },
        { type: "header", key: "purpose", value: "prefetch" },
      ],
    },
  ],
};
