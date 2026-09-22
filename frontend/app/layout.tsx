import type { Metadata, Viewport } from "next";
import "./globals.css";
import { AuthProvider } from "@/lib/auth-context";
import AppShell from "@/components/AppShell";
import ServiceWorkerRegistration from "@/components/ServiceWorkerRegistration";

export const metadata: Metadata = {
  title: "Aviation Africa Logistics Ltd | Global Logistics Control Tower",
  description:
    "Aviation Africa Logistics Ltd — secure multimodal logistics operations across air, ocean, road, rail, warehousing, customs and shipment visibility.",
  applicationName: "Aviation Africa Logistics Ltd",
  keywords: [
    "Aviation Africa Logistics",
    "AAL",
    "logistics",
    "freight",
    "air cargo",
    "multimodal logistics",
    "Africa logistics",
  ],
  icons: {
    icon: "/branding/aal-logo.jpg",
    apple: "/branding/aal-logo.jpg",
  },
};

export const viewport: Viewport = {
  themeColor: "#071A52",
  colorScheme: "light",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <body>
        <AuthProvider>
          <ServiceWorkerRegistration />
          <AppShell>{children}</AppShell>
        </AuthProvider>
      </body>
    </html>
  );
}
