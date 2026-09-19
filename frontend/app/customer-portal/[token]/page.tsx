import { redirect } from "next/navigation";

export default function LegacyCustomerPortalRoute({
  params,
}: {
  params: { token: string };
}) {
  redirect(`/track/${encodeURIComponent(params.token)}`);
}
