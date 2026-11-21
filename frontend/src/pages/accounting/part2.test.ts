import { describe, expect, it } from "vitest";
import type { Account, Party } from "@/types/accounting";
import { partiesFor, partyKind } from "./JournalPage";
import { yearEarlier } from "./ReportTabs";

describe("yearEarlier", () => {
  it("moves a date back one year", () => {
    expect(yearEarlier("2026-04-01")).toBe("2025-04-01");
    expect(yearEarlier("2027-03-31")).toBe("2026-03-31");
  });
  it("clamps 29 February to 28 February", () => {
    expect(yearEarlier("2028-02-29")).toBe("2027-02-28");
  });
});

describe("party lines", () => {
  const acc = (subtype: string | null) => ({ subtype } as Account);
  const party = (id: string, partyType: Party["partyType"], active = true) => ({ id, partyType, active } as Party);
  it("asks for a customer on receivables and a vendor on payables only", () => {
    expect(partyKind(acc("RECEIVABLE"))).toBe("CUSTOMER");
    expect(partyKind(acc("PAYABLE"))).toBe("VENDOR");
    expect(partyKind(acc("BANK"))).toBeNull();
    expect(partyKind(undefined)).toBeNull();
  });
  it("lists active parties of the right kind, including BOTH", () => {
    const ps = [party("c", "CUSTOMER"), party("v", "VENDOR"), party("b", "BOTH"), party("x", "CUSTOMER", false)];
    expect(partiesFor(ps, "CUSTOMER").map((p) => p.id)).toEqual(["c", "b"]);
    expect(partiesFor(ps, "VENDOR").map((p) => p.id)).toEqual(["v", "b"]);
    expect(partiesFor(ps, null)).toEqual([]);
  });
});
