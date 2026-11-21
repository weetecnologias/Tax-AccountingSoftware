import { useQuery } from "@tanstack/react-query";
import { CheckCircle2, Download, XCircle } from "lucide-react";
import { useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { EmptyState, ErrorState, Skeleton } from "@/components/common";
import { Button, Card, Field, Input, Select, Table, Td, Th } from "@/components/ui";
import { currentYearRange, postableAccounts, useAccounts, usePeriods } from "@/hooks/useAccounting";
import { api, qs } from "@/lib/api";
import { downloadCsv } from "@/lib/csv";
import { formatINR } from "@/lib/money";
import { formatDate, formatDateTime, humanize } from "@/lib/utils";
import type { BalanceSheet, GeneralLedger, ProfitAndLoss, ReportHeader, StatementSection, TrialBalance } from "@/types/accounting";

const amt = "text-right font-mono tabular-nums whitespace-nowrap";

function ReportFrame({ header, title, controls, onExport, children }: {
  header?: ReportHeader; title: string; controls: ReactNode; onExport?: () => void; children: ReactNode;
}) {
  return (
    <Card>
      <div className="flex flex-wrap items-end justify-between gap-3 border-b border-line p-4">
        <div className="flex flex-wrap items-end gap-3">{controls}</div>
        {onExport && <Button variant="secondary" size="sm" onClick={onExport}><Download className="h-4 w-4" /> Export CSV</Button>}
      </div>
      {header && (
        <div className="border-b border-line px-5 py-3 text-center">
          <p className="text-[11px] tracking-wide text-muted uppercase">
            Prepared by {header.preparedBy.name}{header.preparedBy.address ? ` · ${header.preparedBy.address}` : ""}
            {header.preparedBy.gstin ? ` · GSTIN ${header.preparedBy.gstin}` : ""}
          </p>
          <p className="font-semibold text-primary">{header.clientName} <span className="font-mono text-xs text-muted">{header.clientCode}</span></p>
          <p className="text-sm">{title} {header.from ? `for ${formatDate(header.from)} to ${formatDate(header.to)}` : `as at ${formatDate(header.to)}`}</p>
          <p className="text-xs text-muted">{header.basis} · generated {formatDateTime(header.generatedAt)} · amounts in ₹</p>
        </div>
      )}
      {children}
      {header?.preparedBy.footer && <p className="border-t border-line px-5 py-2 text-center text-[11px] text-muted">{header.preparedBy.footer}</p>}
    </Card>
  );
}

function BalanceCheck({ ok, label, difference }: { ok: boolean; label: string; difference?: string }) {
  return ok ? (
    <p className="flex items-center gap-1.5 px-5 py-3 text-sm text-emerald"><CheckCircle2 className="h-4 w-4" /> {label}</p>
  ) : (
    <p role="alert" className="flex items-center gap-1.5 px-5 py-3 text-sm text-danger"><XCircle className="h-4 w-4" /> Out of balance{difference ? ` by ₹${formatINR(difference)}` : ""}. Investigate before relying on this report.</p>
  );
}

function useRange(clientId: string) {
  const periods = usePeriods(clientId);
  const def = currentYearRange(periods.data);
  const [from, setFrom] = useState<string | null>(null);
  const [to, setTo] = useState<string | null>(null);
  return { from: from ?? def.from, to: to ?? def.to, setFrom, setTo, ready: !periods.isPending };
}

// ------------------------------------------------------------------ trial balance

export function TrialBalanceTab({ clientId }: { clientId: string }) {
  const r = useRange(clientId);
  const q = useQuery({
    queryKey: ["accounting", clientId, "tb", r.to],
    queryFn: () => api.get<TrialBalance>(`/accounting/trial-balance${qs({ clientId, asOf: r.to })}`),
    enabled: r.ready,
  });
  const d = q.data;
  return (
    <ReportFrame header={d?.header} title="Trial balance"
      controls={<Field label="As at" htmlFor="tb-asof"><Input id="tb-asof" type="date" value={r.to} onChange={(e) => r.setTo(e.target.value)} /></Field>}
      onExport={d ? () => downloadCsv(`trial-balance-${r.to}.csv`, ["Code", "Account", "Group", "Debit", "Credit"],
        [...d.rows.map((x) => [x.code, x.name, x.groupName, x.debit, x.credit]), ["", "Total", "", d.totalDebit, d.totalCredit]]) : undefined}>
      {q.isError ? <ErrorState error={q.error} onRetry={() => q.refetch()} /> : !d ? <Skeleton className="m-4 h-40" /> : d.rows.length === 0 ? (
        <EmptyState title="No posted entries yet" description="Only posted journals appear in reports." />
      ) : (
        <>
          <Table>
            <thead><tr><Th>Code</Th><Th>Account</Th><Th>Group</Th><Th className="text-right">Debit</Th><Th className="text-right">Credit</Th></tr></thead>
            <tbody>
              {d.rows.map((x) => (
                <tr key={x.accountId}>
                  <Td className="font-mono text-xs">{x.code}</Td><Td>{x.name}</Td><Td className="text-muted">{x.groupName}</Td>
                  <Td className={amt}>{formatINR(x.debit, { blankZero: true })}</Td><Td className={amt}>{formatINR(x.credit, { blankZero: true })}</Td>
                </tr>
              ))}
              <tr className="bg-page font-semibold">
                <Td colSpan={3}>Total</Td><Td className={amt}>{formatINR(d.totalDebit)}</Td><Td className={amt}>{formatINR(d.totalCredit)}</Td>
              </tr>
            </tbody>
          </Table>
          <BalanceCheck ok={d.balanced} label="Total debits equal total credits" />
        </>
      )}
    </ReportFrame>
  );
}

// ------------------------------------------------------------------ statements

function Sections({ sections, emptyLabel }: { sections: StatementSection[]; emptyLabel: string }) {
  if (sections.length === 0) return <tr><Td colSpan={2} className="text-muted">{emptyLabel}</Td></tr>;
  return (
    <>
      {sections.map((s) => (
        <SectionRows key={s.title} section={s} />
      ))}
    </>
  );
}

function SectionRows({ section }: { section: StatementSection }) {
  return (
    <>
      <tr><Td colSpan={2} className="pt-4 font-medium text-primary">{section.title}</Td></tr>
      {section.lines.map((l) => (
        <tr key={l.accountId}><Td className="pl-8">{l.code} · {l.name}</Td><Td className={amt}>{formatINR(l.amount)}</Td></tr>
      ))}
      <tr><Td className="pl-8 text-muted">Total {section.title.toLowerCase()}</Td><Td className={`${amt} text-muted`}>{formatINR(section.total)}</Td></tr>
    </>
  );
}

const sectionCsv = (heading: string, sections: StatementSection[]) =>
  sections.flatMap((s) => [[heading, s.title, "", ""], ...s.lines.map((l) => [heading, s.title, `${l.code} ${l.name}`, l.amount])]);

export function ProfitLossTab({ clientId }: { clientId: string }) {
  const r = useRange(clientId);
  const q = useQuery({
    queryKey: ["accounting", clientId, "pl", r.from, r.to],
    queryFn: () => api.get<ProfitAndLoss>(`/accounting/profit-loss${qs({ clientId, from: r.from, to: r.to })}`),
    enabled: r.ready && r.from <= r.to,
  });
  const d = q.data;
  const profit = d && !d.netProfit.startsWith("-");
  return (
    <ReportFrame header={d?.header} title="Statement of profit and loss"
      controls={<>
        <Field label="From" htmlFor="pl-from"><Input id="pl-from" type="date" value={r.from} onChange={(e) => r.setFrom(e.target.value)} /></Field>
        <Field label="To" htmlFor="pl-to"><Input id="pl-to" type="date" value={r.to} onChange={(e) => r.setTo(e.target.value)} /></Field>
      </>}
      onExport={d ? () => downloadCsv(`profit-and-loss-${r.from}-${r.to}.csv`, ["Section", "Group", "Account", "Amount"],
        [...sectionCsv("Income", d.income), ["Income", "Total", "", d.totalIncome], ...sectionCsv("Expenses", d.expenses),
          ["Expenses", "Total", "", d.totalExpenses], ["Net profit / (loss)", "", "", d.netProfit]]) : undefined}>
      {q.isError ? <ErrorState error={q.error} onRetry={() => q.refetch()} /> : !d ? <Skeleton className="m-4 h-40" /> : (
        <Table>
          <tbody>
            <tr className="bg-page"><Td className="font-semibold">Income</Td><Td /></tr>
            <Sections sections={d.income} emptyLabel="No income in this period" />
            <tr className="font-semibold"><Td>Total income</Td><Td className={amt}>{formatINR(d.totalIncome)}</Td></tr>
            <tr className="bg-page"><Td className="font-semibold">Expenses</Td><Td /></tr>
            <Sections sections={d.expenses} emptyLabel="No expenses in this period" />
            <tr className="font-semibold"><Td>Total expenses</Td><Td className={amt}>{formatINR(d.totalExpenses)}</Td></tr>
            <tr className={profit ? "bg-mint font-semibold text-primary" : "bg-danger-soft font-semibold text-danger"}>
              <Td>{profit ? "Net profit" : "Net loss"}</Td><Td className={amt}>{formatINR(d.netProfit.replace("-", ""))}</Td>
            </tr>
          </tbody>
        </Table>
      )}
    </ReportFrame>
  );
}

export function BalanceSheetTab({ clientId }: { clientId: string }) {
  const r = useRange(clientId);
  const q = useQuery({
    queryKey: ["accounting", clientId, "bs", r.to],
    queryFn: () => api.get<BalanceSheet>(`/accounting/balance-sheet${qs({ clientId, asOf: r.to })}`),
    enabled: r.ready,
  });
  const d = q.data;
  return (
    <ReportFrame header={d?.header} title="Balance sheet"
      controls={<Field label="As at" htmlFor="bs-asof"><Input id="bs-asof" type="date" value={r.to} onChange={(e) => r.setTo(e.target.value)} /></Field>}
      onExport={d ? () => downloadCsv(`balance-sheet-${r.to}.csv`, ["Section", "Group", "Account", "Amount"],
        [...sectionCsv("Equity", d.equity), ["Equity", "Profit / (loss) for the period", "", d.profitForPeriod], ["Equity", "Total", "", d.totalEquity],
          ...sectionCsv("Liabilities", d.liabilities), ["Liabilities", "Total", "", d.totalLiabilities],
          ["Total equity and liabilities", "", "", d.totalLiabilitiesAndEquity], ...sectionCsv("Assets", d.assets), ["Assets", "Total", "", d.totalAssets]]) : undefined}>
      {q.isError ? <ErrorState error={q.error} onRetry={() => q.refetch()} /> : !d ? <Skeleton className="m-4 h-40" /> : (
        <>
          <div className="grid gap-0 lg:grid-cols-2 lg:divide-x lg:divide-line">
            <Table>
              <tbody>
                <tr className="bg-page"><Td className="font-semibold">Equity and liabilities</Td><Td /></tr>
                <Sections sections={d.equity} emptyLabel="No equity balances" />
                <tr><Td className="pl-8">Profit / (loss) for the period</Td><Td className={amt}>{formatINR(d.profitForPeriod)}</Td></tr>
                <tr className="font-medium"><Td>Total equity</Td><Td className={amt}>{formatINR(d.totalEquity)}</Td></tr>
                <Sections sections={d.liabilities} emptyLabel="No liabilities" />
                <tr className="font-medium"><Td>Total liabilities</Td><Td className={amt}>{formatINR(d.totalLiabilities)}</Td></tr>
                <tr className="bg-mint font-semibold text-primary"><Td>Total equity and liabilities</Td><Td className={amt}>{formatINR(d.totalLiabilitiesAndEquity)}</Td></tr>
              </tbody>
            </Table>
            <Table>
              <tbody>
                <tr className="bg-page"><Td className="font-semibold">Assets</Td><Td /></tr>
                <Sections sections={d.assets} emptyLabel="No assets" />
                <tr className="bg-mint font-semibold text-primary"><Td>Total assets</Td><Td className={amt}>{formatINR(d.totalAssets)}</Td></tr>
              </tbody>
            </Table>
          </div>
          <BalanceCheck ok={d.balanced} label="Assets equal equity plus liabilities" difference={d.difference} />
        </>
      )}
    </ReportFrame>
  );
}

// ------------------------------------------------------------------ general ledger

export function LedgerTab({ clientId }: { clientId: string }) {
  const r = useRange(clientId);
  const accounts = useAccounts(clientId);
  const ledgers = postableAccounts(accounts.data);
  const [accountId, setAccountId] = useState("");
  const q = useQuery({
    queryKey: ["accounting", clientId, "gl", accountId, r.from, r.to],
    queryFn: () => api.get<GeneralLedger>(`/accounting/ledger${qs({ clientId, accountId, from: r.from, to: r.to })}`),
    enabled: !!accountId && r.ready && r.from <= r.to,
  });
  const d = q.data;
  return (
    <ReportFrame header={d?.header} title={d ? `General ledger · ${d.code} ${d.name}` : "General ledger"}
      controls={<>
        <Field label="Account" htmlFor="gl-account">
          <Select id="gl-account" value={accountId} onChange={(e) => setAccountId(e.target.value)} className="min-w-72">
            <option value="">Choose an account…</option>
            {ledgers.map((a) => <option key={a.id} value={a.id}>{a.code} · {a.name}</option>)}
          </Select>
        </Field>
        <Field label="From" htmlFor="gl-from"><Input id="gl-from" type="date" value={r.from} onChange={(e) => r.setFrom(e.target.value)} /></Field>
        <Field label="To" htmlFor="gl-to"><Input id="gl-to" type="date" value={r.to} onChange={(e) => r.setTo(e.target.value)} /></Field>
      </>}
      onExport={d ? () => downloadCsv(`ledger-${d.code}-${r.from}-${r.to}.csv`, ["Date", "Voucher", "Narration", "Debit", "Credit", "Balance", "Dr/Cr"],
        [["", "", "Opening balance", "", "", d.openingBalance, d.openingSide],
          ...d.lines.map((l) => [l.entryDate, l.entryNumber, l.description ? `${l.narration} — ${l.description}` : l.narration, l.debit, l.credit, l.balance, l.balanceSide]),
          ["", "", "Closing balance", d.totalDebit, d.totalCredit, d.closingBalance, d.closingSide]]) : undefined}>
      {!accountId ? <EmptyState title="Choose an account" /> : q.isError ? <ErrorState error={q.error} onRetry={() => q.refetch()} /> : !d ? <Skeleton className="m-4 h-40" /> : (
        <Table>
          <thead><tr><Th>Date</Th><Th>Voucher</Th><Th>Narration</Th><Th className="text-right">Debit</Th><Th className="text-right">Credit</Th><Th className="text-right">Balance</Th></tr></thead>
          <tbody>
            <tr className="bg-page"><Td colSpan={5} className="font-medium">Opening balance</Td><Td className={amt}>{formatINR(d.openingBalance)} {d.openingSide}</Td></tr>
            {d.lines.map((l, i) => (
              <tr key={`${l.journalId}-${i}`}>
                <Td className="whitespace-nowrap">{formatDate(l.entryDate)}</Td>
                <Td className="font-mono text-xs"><Link to={`/accounting/journals/${l.journalId}`} className="text-primary hover:underline">{l.entryNumber}</Link></Td>
                <Td><p className="max-w-md truncate">{l.narration}</p>{l.description && <p className="text-xs text-muted">{l.description}</p>}</Td>
                <Td className={amt}>{formatINR(l.debit, { blankZero: true })}</Td>
                <Td className={amt}>{formatINR(l.credit, { blankZero: true })}</Td>
                <Td className={amt}>{formatINR(l.balance)} {l.balanceSide}</Td>
              </tr>
            ))}
            <tr className="bg-page font-semibold">
              <Td colSpan={3}>Closing balance · {humanize(d.accountType)}</Td>
              <Td className={amt}>{formatINR(d.totalDebit)}</Td><Td className={amt}>{formatINR(d.totalCredit)}</Td>
              <Td className={amt}>{formatINR(d.closingBalance)} {d.closingSide}</Td>
            </tr>
          </tbody>
        </Table>
      )}
    </ReportFrame>
  );
}
