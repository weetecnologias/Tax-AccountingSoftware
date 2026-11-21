import type { FieldValues, Path, UseFormSetError } from "react-hook-form";
import { toast } from "sonner";
import { ApiError } from "@/lib/api";

/** Maps server field errors onto form fields; anything else becomes a toast. Returns the top-level message. */
export function applyServerErrors<T extends FieldValues>(error: unknown, setError: UseFormSetError<T>, fields: string[]) {
  if (error instanceof ApiError) {
    let mapped = false;
    for (const fe of error.fieldErrors) {
      if (fields.includes(fe.field)) {
        setError(fe.field as Path<T>, { type: "server", message: fe.message });
        mapped = true;
      }
    }
    if (!mapped) toast.error(error.message);
    return error.message;
  }
  toast.error("Something went wrong. Please try again.");
  return null;
}

/** Turns "" into null for optional API fields. */
export function nullIfBlank(v: string | undefined | null): string | null {
  return v === undefined || v === null || v.trim() === "" ? null : v.trim();
}
