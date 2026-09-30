import ResetPasswordClient from "./ResetPasswordClient";

export default function ResetPasswordPage({
  searchParams,
}: {
  searchParams?: Record<string, string | string[] | undefined>;
}) {
  const rawToken = searchParams?.token;
  const token = Array.isArray(rawToken)
    ? (rawToken[0] ?? "")
    : (rawToken ?? "");

  return <ResetPasswordClient token={token} />;
}
