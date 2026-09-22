import type { MetadataRoute } from "next";

export default function manifest(): MetadataRoute.Manifest {
  return {
    name: "Aviation Africa Logistics",
    short_name: "AAL",
    description: "AAL logistics control tower and field operations",
    start_url: "/aal-control-tower",
    display: "standalone",
    background_color: "#ffffff",
    theme_color: "#071A52",
    orientation: "portrait",
    icons: [
      { src: "/branding/aal-logo.jpg", sizes: "512x512", type: "image/jpeg" },
    ],
  };
}
