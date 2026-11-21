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

/** Parses a non-negative decimal with up to `scale` places into an integer of that scale (e.g. 2.5, 3 → 2500n). */
export function toScaled(raw: string | null | undefined, scale: number): bigint | null {
  const v = normaliseAmount(raw);
  if (v === "") return 0n;
  if (!new RegExp(`^\\d{1,15}(\\.\\d{1,${scale}})?$`).test(v)) return null;
  const [int, frac = ""] = v.split(".");
  return BigInt(int) * 10n ** BigInt(scale) + BigInt((frac + "0".repeat(scale)).slice(0, scale));
}

/** Half-up integer division for non-negative values. */
export function divHalfUp(a: bigint, b: bigint): bigint {
  return (a * 2n + b) / (2n * b);
}

/** Mirrors the server's GST line arithmetic in paise (preview only; the server is authoritative). */
export function gstLinePreview(qty: string, unitPrice: string, rate: string, interState: boolean) {
  const q = toScaled(qty, 3);
  const p = toPaise(unitPrice);
  const r = toScaled(rate, 2);
  if (q === null || p === null || r === null) return null;
  const taxable = divHalfUp(q * p, 1000n);
  const half = interState ? 0n : divHalfUp(taxable * r, 20000n);
  const igst = interState ? divHalfUp(taxable * r, 10000n) : 0n;
  return { taxable, cgst: half, sgst: half, igst, total: taxable + half * 2n + igst };
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
