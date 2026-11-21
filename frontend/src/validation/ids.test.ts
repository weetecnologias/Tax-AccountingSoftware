import { describe, expect, it } from "vitest";
import { gstinCheckChar, gstinSchema, isValidGstin, optionalTanSchema, panMatchesEntity, panSchema, passwordSchema } from "./ids";

describe("Indian identifier validation", () => {
  it("normalises and validates PAN", () => {
    expect(panSchema.parse(" aaapl1234c ")).toBe("AAAPL1234C");
    expect(panSchema.safeParse("AAAPL1234").success).toBe(false);
  });

  it("matches the backend GSTIN check digit", () => {
    expect(gstinCheckChar("27AAPFU0939F1Z")).toBe("V");
    expect(isValidGstin("27AAPFU0939F1ZV")).toBe(true);
    expect(isValidGstin("27AAPFU0939F1ZA")).toBe(false);
    expect(gstinSchema.parse("27aapfu0939f1zv")).toBe("27AAPFU0939F1ZV");
  });

  it("treats TAN as optional", () => {
    expect(optionalTanSchema.parse("")).toBe("");
    expect(optionalTanSchema.parse("muma12345b")).toBe("MUMA12345B");
    expect(optionalTanSchema.safeParse("MUM12345B").success).toBe(false);
  });

  it("checks the PAN holder type against the entity type", () => {
    expect(panMatchesEntity("AAACX1234C", ["C"])).toBe(true);
    expect(panMatchesEntity("AAAPX1234C", ["C"])).toBe(false);
    expect(panMatchesEntity("AAA", ["C"])).toBe(true);
  });

  it("enforces the password policy", () => {
    expect(passwordSchema.safeParse("Short1").success).toBe(false);
    expect(passwordSchema.safeParse("alllowercase123").success).toBe(false);
    expect(passwordSchema.safeParse("GoodPassword123").success).toBe(true);
  });
});
