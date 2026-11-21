import { useQuery } from "@tanstack/react-query";
import { api, qs } from "@/lib/api";
import type { ClientSummary, Page } from "@/types/api";
import type { Account, Period } from "@/types/accounting";

export const useAccounts = (clientId: string | null) =>
  useQuery({
    queryKey: ["accounting", clientId, "accounts"],
    queryFn: () => api.get<Account[]>(`/accounting/chart-of-accounts?clientId=${clientId}`),
    enabled: !!clientId,
  });

export const usePeriods = (clientId: string | null) =>
  useQuery({
    queryKey: ["accounting", clientId, "periods"],
    queryFn: () => api.get<Period[]>(`/accounting/periods?clientId=${clientId}`),
    enabled: !!clientId,
  });

export const useClientOptions = (search: string) =>
  useQuery({
    queryKey: ["clients", "picker", search],
    queryFn: () => api.get<Page<ClientSummary>>(`/clients${qs({ q: search, size: 50, sort: "displayName,asc" })}`),
  });

/** Ledger (non-group) accounts usable on journal lines, sorted by code. */
export function postableAccounts(accounts: Account[] | undefined): Account[] {
  return (accounts ?? []).filter((a) => !a.group && a.active).sort((a, b) => a.code.localeCompare(b.code));
}

/** Default report range: the financial year containing today, from the client's periods. */
export function currentYearRange(periods: Period[] | undefined): { from: string; to: string } {
  const today = new Date().toISOString().slice(0, 10);
  if (!periods || periods.length === 0) return { from: today, to: today };
  const containing = periods.find((p) => p.startDate <= today && p.endDate >= today);
  const fy = containing?.financialYear ?? periods[periods.length - 1].financialYear;
  const inYear = periods.filter((p) => p.financialYear === fy);
  return { from: inYear[0].startDate, to: inYear[inYear.length - 1].endDate };
}
