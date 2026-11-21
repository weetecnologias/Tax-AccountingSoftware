import { z } from "zod";

/** Client-side mirrors of the backend rules. The server re-validates everything. */

const GSTIN_CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
export const PAN_RE = /^[A-Z]{5}[0-9]{4}[A-Z]$/;
export const TAN_RE = /^[A-Z]{4}[0-9]{5}[A-Z]$/;
export const GSTIN_RE = /^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$/;
export const IFSC_RE = /^[A-Z]{4}0[A-Z0-9]{6}$/;
export const PINCODE_RE = /^[1-9][0-9]{5}$/;

export function gstinCheckChar(first14: string): string {
  let sum = 0;
  for (let i = 0; i < 14; i++) {
    const product = GSTIN_CHARSET.indexOf(first14[i]) * (i % 2 === 0 ? 1 : 2);
    sum += Math.floor(product / 36) + (product % 36);
  }
  return GSTIN_CHARSET[(36 - (sum % 36)) % 36];
}

export function isValidGstin(value: string): boolean {
  return GSTIN_RE.test(value) && gstinCheckChar(value.slice(0, 14)) === value[14];
}

export const upper = (v: unknown) => (typeof v === "string" ? v.trim().toUpperCase() : v);

export const panSchema = z.preprocess(upper, z.string().regex(PAN_RE, "PAN must look like AAAAA9999A"));

export const optionalTanSchema = z.preprocess(
  upper,
  z.string().refine((v) => v === "" || TAN_RE.test(v), "TAN must look like AAAA99999A"),
);

export const gstinSchema = z.preprocess(
  upper,
  z.string().refine(isValidGstin, "GSTIN is invalid (format or check digit)"),
);

export const passwordSchema = z
  .string()
  .min(12, "At least 12 characters")
  .max(128, "At most 128 characters")
  .refine((v) => /[A-Z]/.test(v), "Include an upper-case letter")
  .refine((v) => /[a-z]/.test(v), "Include a lower-case letter")
  .refine((v) => /[0-9]/.test(v), "Include a digit");

/** Checks the 4th PAN character against the holder types the entity type allows. */
export function panMatchesEntity(pan: string, allowed: string[] | undefined): boolean {
  if (!allowed || allowed.length === 0 || pan.length < 4) return true;
  return allowed.includes(pan[3]);
}
