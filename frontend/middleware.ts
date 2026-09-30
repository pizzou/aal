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

  response.headers.set(
    "Content-Security-Policy",
    [
      "default-src 'self'",
      "base-uri 'self'",
      "object-src 'none'",
      "frame-ancestors 'none'",
      "form-action 'self'",
      "img-src 'self' data: blob: https://aal-a.vercel.app",
      "font-src 'self' data:",
      "style-src 'self' 'unsafe-inline'",
      `script-src 'self' 'nonce-${value}' 'strict-dynamic'`,
      `connect-src 'self' ${apiOrigin} wss:`,
      "worker-src 'self' blob:",
      "manifest-src 'self'",
      ...(process.env.NODE_ENV === "production"
        ? ["upgrade-insecure-requests"]
        : []),
    ].join("; "),
  );

  return response;
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"],
};
