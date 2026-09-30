import BookClient from "./BookClient";

export default function BookPage({
  searchParams,
}: {
  searchParams?: Record<string, string | string[] | undefined>;
}) {
  const get = (key: string) => {
    const value = searchParams?.[key];
    return Array.isArray(value) ? (value[0] ?? "") : (value ?? "");
  };

  return (
    <BookClient
      quoteToken={get("quote")}
      requestToken={get("requestToken")}
      selectedMode={get("mode")}
    />
  );
}
