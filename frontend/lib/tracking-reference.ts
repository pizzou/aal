/**
 * AAL public tracking numbers encode the full 128-bit database tracking token.
 * The shipment's sequential AAL-SHP reference is not a public access token.
 */
export function trackingNumberFromToken(token: string): string {
  const compact = token.replace(/-/g, "");
  return /^[0-9a-f]{32}$/i.test(compact)
    ? `AAL-TRK-${compact.toUpperCase()}`
    : token;
}

/** Allow customers to paste either the tracking number or the full share URL. */
export function normalizeTrackingInput(raw: string): string {
  const text = raw.trim();
  if (!/^https?:\/\//i.test(text)) return text;
  try {
    const url = new URL(text);
    const match = url.pathname.match(/\/track\/([^/]+)\/?$/i);
    return match ? decodeURIComponent(match[1]) : text;
  } catch {
    return text;
  }
}
