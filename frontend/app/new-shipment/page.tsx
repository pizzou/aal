import { redirect } from "next/navigation";

/** Compatibility link: one shipment workspace with list and guided create mode. */
export default function FormerNewShipmentPage() {
  redirect("/shipments?create=1");
}
