import { redirect } from "next/navigation";

/** Existing bookmarks still lead to the unified Customers & Quotes workspace. */
export default function CustomersRedirect() {
  redirect("/commercial?tab=customers");
}
