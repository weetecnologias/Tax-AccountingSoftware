import { describe, expect, it } from "vitest";
import { formatINR, isValidAmount, paiseToString, toPaise } from "./money";

describe("money", () => {
  it("parses to exact paise", () => {
    expect(toPaise("1500")).toBe(150000n);
    expect(toPaise("1,23,456.7")).toBe(12345670n);
    expect(toPaise("0.1")).toBe(10n);
    expect(toPaise("")).toBe(0n);
    expect(toPaise("10.005")).toBeNull();
    expect(toPaise("-5")).toBeNull();
    expect(toPaise("abc")).toBeNull();
  });

  it("adds without floating-point drift", () => {
    let total = 0n;
    for (let i = 0; i < 10; i++) total += toPaise("0.10")!;
    expect(paiseToString(total)).toBe("1.00");
  });

  it("formats with Indian digit grouping", () => {
    expect(formatINR("1234567.89")).toBe("12,34,567.89");
    expect(formatINR("100000")).toBe("1,00,000.00");
    expect(formatINR("999.5")).toBe("999.50");
    expect(formatINR("-4500.00")).toBe("-4,500.00");
    expect(formatINR("0.00", { blankZero: true })).toBe("");
  });

  it("validates amount input", () => {
    expect(isValidAmount("12,000.50")).toBe(true);
    expect(isValidAmount("")).toBe(true);
    expect(isValidAmount("1.234")).toBe(false);
  });
});
