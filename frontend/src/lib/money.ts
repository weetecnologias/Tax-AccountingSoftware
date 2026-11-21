/**
 * Exact money helpers. Amounts travel as decimal strings ("1500.00"); arithmetic is done in integer paise
 * with BigInt so the UI never shows floating-point drift. The server remains the authority.
 */

const AMOUNT_RE = /^\d{1,15}(\.\d{1,2})?$/;

export function normaliseAmount(raw: string | null | undefined): string {
  return (raw ?? "").replace(/,/g, "").trim();
}

export function isValidAmount(raw: string | null | undefined): boolean {
  const v = normaliseAmount(raw);
  return v === "" || AMOUNT_RE.test(v);
}

/** "1,500.5" → 150050n; blank → 0n; invalid → null. */
export function toPaise(raw: string | null | undefined): bigint | null {
  const v = normaliseAmount(raw);
  if (v === "") return 0n;
  if (!AMOUNT_RE.test(v)) return null;
  const [int, frac = ""] = v.split(".");
  return BigInt(int) * 100n + BigInt((frac + "00").slice(0, 2));
}

export function paiseToString(p: bigint): string {
  const neg = p < 0n;
  const abs = neg ? -p : p;
  const s = `${abs / 100n}.${String(abs % 100n).padStart(2, "0")}`;
  return neg ? `-${s}` : s;
}

/** Indian digit grouping (12,34,567.89) computed from the string, no floating point. */
export function formatINR(value: string | null | undefined, opts: { blankZero?: boolean } = {}): string {
  if (value === null || value === undefined || value === "") return "";
  const neg = value.startsWith("-");
  const [intRaw, fracRaw = ""] = (neg ? value.slice(1) : value).split(".");
  const frac = (fracRaw + "00").slice(0, 2);
  if (opts.blankZero && /^0*$/.test(intRaw) && frac === "00") return "";
  let int = intRaw.replace(/^0+(?=\d)/, "");
  if (int.length > 3) {
    const last3 = int.slice(-3);
    const rest = int.slice(0, -3).replace(/\B(?=(\d{2})+(?!\d))/g, ",");
    int = `${rest},${last3}`;
  }
  return `${neg ? "-" : ""}${int}.${frac}`;
}
