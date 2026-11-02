import { redirect } from "next/navigation";

export default function LegacyNewShipmentRedirect() {
  // New business starts from Customer and an accepted quotation.
  redirect("/commercial");
}
