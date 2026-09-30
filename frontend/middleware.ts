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
  response.headers.set(
    "Content-Security-Policy",
    [
      "default-src 'self'",
      "base-uri 'self'",
      "object-src 'none'",
      "frame-ancestors 'none'",
      "form-action 'self'",
      "img-src 'self' data: blob: https:",
      "font-src 'self' data: https:",
      "style-src 'self' 'unsafe-inline'",
      `script-src 'self' 'nonce-${value}' 'strict-dynamic'`,
      "connect-src 'self' https: wss:",
    ].join("; "),
  );

  return response;
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"],
};
