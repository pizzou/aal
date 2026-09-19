import { NextRequest, NextResponse } from "next/server";

export function middleware(request: NextRequest) {
  const path = request.nextUrl.pathname;

  if (path === "/portal" || path.startsWith("/portal/") || path === "/customer-portal" || path.startsWith("/customer-portal/")) {
    return NextResponse.redirect(new URL("/", request.url));
  }

  return NextResponse.next();
}

export const config = {
  matcher: ["/portal/:path*", "/customer-portal/:path*"],
};
