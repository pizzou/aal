import LoginClient from "./LoginClient";

export default function LoginPage({
  searchParams,
}: {
  searchParams?: Record<string, string | string[] | undefined>;
}) {
  const rawNext = searchParams?.next;
  const nextPath = Array.isArray(rawNext) ? rawNext[0] : rawNext;

  return <LoginClient nextPath={nextPath} />;
}
