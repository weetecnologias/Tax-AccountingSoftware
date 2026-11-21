import { useQuery } from "@tanstack/react-query";
import { CheckCircle2, Download, FileSpreadsheet, FileText, XCircle } from "lucide-react";
import { useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { EmptyState, ErrorState, Skeleton } from "@/components/common";
import { Button, Card, Field, Input, Select, Table, Td, Th } from "@/components/ui";
import { currentYearRange, postableAccounts, useAccounts, usePeriods } from "@/hooks/useAccounting";
import { toast } from "sonner";
import { api, downloadFile, qs } from "@/lib/api";
import { downloadCsv } from "@/lib/csv";
import { formatINR } from "@/lib/money";
import { formatDate, formatDateTime, humanize } from "@/lib/utils";
import type { BalanceSheet, ExportReport, GeneralLedger, ProfitAndLoss, ReportHeader, StatementSection, TrialBalance } from "@/types/accounting";

export const amt = "text-right font-mono tabular-nums whitespace-nowrap";

export type ExportSpec = { report: ExportReport; params: Record<string, string | null | undefined> };

/** PDF / Excel downloads rendered by the server on the firm letterhead (each export is audited). */
export function ExportButtons({ clientId, spec }: { clientId: string; spec: ExportSpec }) {
  const [busy, setBusy] = useState<"pdf" | "xlsx" | null>(null);
  const run = async (format: "pdf" | "xlsx") => {
    setBusy(format);
    try {
      await downloadFile(`/accounting/export${qs({ report: spec.report, format, clientId, ...spec.params })}`, `${spec.report.toLowerCase()}.${format}`);
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Export failed");
    } finally {
      setBusy(null);
    }
  };
  return (
    <>
      <Button variant="secondary" size="sm" loading={busy === "pdf"} onClick={() => run("pdf")}><FileText className="h-4 w-4" /> PDF</Button>
      <Button variant="secondary" size="sm" loading={busy === "xlsx"} onClick={() => run("xlsx")}><FileSpreadsheet className="h-4 w-4" /> Excel</Button>
    </>
  );
}

export function ReportFrame({ header, title, controls, onExport, exportSpec, children }: {
  header?: ReportHeader; title: string; controls: ReactNode; onExport?: () => void; exportSpec?: ExportSpec; children: ReactNode;
}) {
  return (
    <Card>
      <div className="flex flex-wrap items-end justify-between gap-3 border-b border-line p-4">
        <div className="flex flex-wrap items-end gap-3">{controls}</div>
        <div className="flex flex-wrap gap-2">
          {header && exportSpec && <ExportButtons clientId={header.clientId} spec={exportSpec} />}
          {onExport && <Button variant="secondary" size="sm" onClick={onExport}><Download className="h-4 w-4" /> CSV</Button>}
        </div>
      </div>
      {header && (
        <div className="border-b border-line px-5 py-3 text-center">
          <p className="text-[11px] tracking-wide text-muted uppercase">
            Prepared by {header.preparedBy.name}{header.preparedBy.address ? ` · ${header.preparedBy.address}` : ""}
            {header.preparedBy.gstin ? ` · GSTIN ${header.preparedBy.gstin}` : ""}
          </p>
          <p className="font-semibold text-primary">{header.clientName} <span className="font-mono text-xs text-muted">{header.clientCode}</span></p>
          <p className="text-sm">{title} {header.from ? `for ${formatDate(header.from)} to ${formatDate(header.to)}` : `as at ${formatDate(header.to)}`}
            {header.compareFrom ? ` · compared with ${formatDate(header.compareFrom)} to ${formatDate(header.compareTo)}`
              : header.compareTo ? ` · compared with ${formatDate(header.compareTo)}` : ""}</p>
          <p className="text-xs text-muted">{header.basis} · generated {formatDateTime(header.generatedAt)} · amounts in ₹</p>
        </div>
      )}
      {children}
      {header?.preparedBy.footer && <p className="border-t border-line px-5 py-2 text-center text-[11px] text-muted">{header.preparedBy.footer}</p>}
    </Card>
  );
}

export function BalanceCheck({ ok, label, difference }: { ok: boolean; label: string; difference?: string }) {
  return ok ? (
    <p className="flex items-center gap-1.5 px-5 py-3 text-sm text-emerald"><CheckCircle2 className="h-4 w-4" /> {label}</p>
  ) : (
    <p role="alert" className="flex items-center gap-1.5 px-5 py-3 text-sm text-danger"><XCircle className="h-4 w-4" /> Out of balance{difference ? ` by ₹${formatINR(difference)}` : ""}. Investigate before relying on this report.</p>
  );
}

export function useRange(clientId: string) {
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
    <ReportFrame header={d?.header} title="Trial balance" exportSpec={{ report: "TRIAL_BALANCE", params: { asOf: r.to } }}
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

function Sections({ sections, emptyLabel, compare }: { sections: StatementSection[]; emptyLabel: string; compare: boolean }) {
  if (sections.length === 0) return <tr><Td colSpan={compare ? 3 : 2} className="text-muted">{emptyLabel}</Td></tr>;
  return (
    <>
      {sections.map((s) => (
        <SectionRows key={s.title} section={s} compare={compare} />
      ))}
    </>
  );
}

function SectionRows({ section, compare }: { section: StatementSection; compare: boolean }) {
  return (
    <>
      <tr><Td colSpan={compare ? 3 : 2} className="pt-4 font-medium text-primary">{section.title}</Td></tr>
      {section.lines.map((l) => (
        <tr key={l.accountId}><Td className="pl-8">{l.code} · {l.name}</Td><Td className={amt}>{formatINR(l.amount)}</Td>
          {compare && <Td className={`${amt} text-muted`}>{formatINR(l.previous)}</Td>}</tr>
      ))}
      <tr><Td className="pl-8 text-muted">Total {section.title}</Td><Td className={`${amt} text-muted`}>{formatINR(section.total)}</Td>
        {compare && <Td className={`${amt} text-muted`}>{formatINR(section.previousTotal)}</Td>}</tr>
    </>
  );
}

/** Same dates one year earlier (29 Feb falls back to 28 Feb). */
export function yearEarlier(iso: string): string {
  const [y, m, d] = iso.split("-").map(Number);
  const last = new Date(Date.UTC(y - 1, m, 0)).getUTCDate();
  return `${y - 1}-${String(m).padStart(2, "0")}-${String(Math.min(d, last)).padStart(2, "0")}`;
}

function CompareToggle({ id, checked, onChange }: { id: string; checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <label htmlFor={id} className="flex items-center gap-2 pb-2 text-sm">
      <input id={id} type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)} className="h-4 w-4 accent-emerald" />
      Compare with previous year
    </label>
  );
}

const Row = ({ label, value, previous, compare, className, labelClass }: {
  label: ReactNode; value: string | null; previous?: string | null; compare: boolean; className?: string; labelClass?: string;
}) => (
  <tr className={className}><Td className={labelClass}>{label}</Td><Td className={amt}>{formatINR(value)}</Td>
    {compare && <Td className={`${amt} opacity-75`}>{formatINR(previous ?? null)}</Td>}</tr>
);

const CompareHead = ({ compare, current, previous }: { compare: boolean; current: string; previous: string }) =>
  compare ? <thead><tr><Th>Particulars</Th><Th className="text-right">{current}</Th><Th className="text-right">{previous}</Th></tr></thead> : null;

const sectionCsv = (heading: string, sections: StatementSection[]) =>
  sections.flatMap((s) => [[heading, s.title, "", ""], ...s.lines.map((l) => [heading, s.title, `${l.code} ${l.name}`, l.amount])]);

export function ProfitLossTab({ clientId }: { clientId: string }) {
  const r = useRange(clientId);
  const [compare, setCompare] = useState(false);
  const cmp = compare ? { compareFrom: yearEarlier(r.from), compareTo: yearEarlier(r.to) } : {};
  const q = useQuery({
    queryKey: ["accounting", clientId, "pl", r.from, r.to, compare],
    queryFn: () => api.get<ProfitAndLoss>(`/accounting/profit-loss${qs({ clientId, from: r.from, to: r.to, ...cmp })}`),
    enabled: r.ready && r.from <= r.to,
  });
  const d = q.data;
  const profit = d && !d.netProfit.startsWith("-");
  const c = compare && !!d?.previousNetProfit;
  return (
    <ReportFrame header={d?.header} title="Statement of profit and loss"
      exportSpec={{ report: "PROFIT_LOSS", params: { from: r.from, to: r.to, ...cmp } }}
      controls={<>
        <Field label="From" htmlFor="pl-from"><Input id="pl-from" type="date" value={r.from} onChange={(e) => r.setFrom(e.target.value)} /></Field>
        <Field label="To" htmlFor="pl-to"><Input id="pl-to" type="date" value={r.to} onChange={(e) => r.setTo(e.target.value)} /></Field>
        <CompareToggle id="pl-compare" checked={compare} onChange={setCompare} />
      </>}
      onExport={d ? () => downloadCsv(`profit-and-loss-${r.from}-${r.to}.csv`, ["Section", "Group", "Account", "Amount"],
        [...sectionCsv("Income", d.income), ["Income", "Total", "", d.totalIncome], ...sectionCsv("Expenses", d.expenses),
          ["Expenses", "Total", "", d.totalExpenses], ["Net profit / (loss)", "", "", d.netProfit]]) : undefined}>
      {q.isError ? <ErrorState error={q.error} onRetry={() => q.refetch()} /> : !d ? <Skeleton className="m-4 h-40" /> : (
        <Table>
          <CompareHead compare={c} current={`${formatDate(r.from)} – ${formatDate(r.to)}`}
            previous={d.header.compareFrom ? `${formatDate(d.header.compareFrom)} – ${formatDate(d.header.compareTo)}` : ""} />
          <tbody>
            <tr className="bg-page"><Td className="font-semibold" colSpan={c ? 3 : 2}>Income</Td></tr>
            <Sections sections={d.income} emptyLabel="No income in this period" compare={c} />
            <Row label="Total income" value={d.totalIncome} previous={d.previousTotalIncome} compare={c} className="font-semibold" />
            <tr className="bg-page"><Td className="font-semibold" colSpan={c ? 3 : 2}>Expenses</Td></tr>
            <Sections sections={d.expenses} emptyLabel="No expenses in this period" compare={c} />
            <Row label="Total expenses" value={d.totalExpenses} previous={d.previousTotalExpenses} compare={c} className="font-semibold" />
            <Row label={profit ? "Net profit" : "Net loss"} value={d.netProfit} previous={d.previousNetProfit} compare={c}
              className={profit ? "bg-mint font-semibold text-primary" : "bg-danger-soft font-semibold text-danger"} />
          </tbody>
        </Table>
      )}
    </ReportFrame>
  );
}

export function BalanceSheetTab({ clientId }: { clientId: string }) {
  const r = useRange(clientId);
  const [compare, setCompare] = useState(false);
  const cmp = compare ? { compareAsOf: yearEarlier(r.to) } : {};
  const q = useQuery({
    queryKey: ["accounting", clientId, "bs", r.to, compare],
    queryFn: () => api.get<BalanceSheet>(`/accounting/balance-sheet${qs({ clientId, asOf: r.to, ...cmp })}`),
    enabled: r.ready,
  });
  const d = q.data;
  const c = compare && !!d?.previousTotalAssets;
  const heads = <CompareHead compare={c} current={formatDate(r.to)} previous={d?.header.compareTo ? formatDate(d.header.compareTo) : ""} />;
  return (
    <ReportFrame header={d?.header} title="Balance sheet" exportSpec={{ report: "BALANCE_SHEET", params: { asOf: r.to, ...cmp } }}
      controls={<>
        <Field label="As at" htmlFor="bs-asof"><Input id="bs-asof" type="date" value={r.to} onChange={(e) => r.setTo(e.target.value)} /></Field>
        <CompareToggle id="bs-compare" checked={compare} onChange={setCompare} />
      </>}
      onExport={d ? () => downloadCsv(`balance-sheet-${r.to}.csv`, ["Section", "Group", "Account", "Amount"],
        [...sectionCsv("Equity", d.equity), ["Equity", "Profit / (loss) for the period", "", d.profitForPeriod], ["Equity", "Total", "", d.totalEquity],
          ...sectionCsv("Liabilities", d.liabilities), ["Liabilities", "Total", "", d.totalLiabilities],
          ["Total equity and liabilities", "", "", d.totalLiabilitiesAndEquity], ...sectionCsv("Assets", d.assets), ["Assets", "Total", "", d.totalAssets]]) : undefined}>
      {q.isError ? <ErrorState error={q.error} onRetry={() => q.refetch()} /> : !d ? <Skeleton className="m-4 h-40" /> : (
        <>
          <div className="grid gap-0 lg:grid-cols-2 lg:divide-x lg:divide-line">
            <Table>
              {heads}
              <tbody>
                <tr className="bg-page"><Td className="font-semibold" colSpan={c ? 3 : 2}>Equity and liabilities</Td></tr>
                <Sections sections={d.equity} emptyLabel="No equity balances" compare={c} />
                <Row label="Profit / (loss) for the period" value={d.profitForPeriod} previous={d.previousProfitForPeriod} compare={c} labelClass="pl-8" />
                <Row label="Total equity (including profit for the period)" value={d.totalEquity} previous={d.previousTotalEquity} compare={c} className="font-medium" />
                <Sections sections={d.liabilities} emptyLabel="No liabilities" compare={c} />
                <Row label="Total liabilities" value={d.totalLiabilities} previous={d.previousTotalLiabilities} compare={c} className="font-medium" />
                <Row label="Total equity and liabilities" value={d.totalLiabilitiesAndEquity} previous={d.previousTotalLiabilitiesAndEquity} compare={c}
                  className="bg-mint font-semibold text-primary" />
              </tbody>
            </Table>
            <Table>
              {heads}
              <tbody>
                <tr className="bg-page"><Td className="font-semibold" colSpan={c ? 3 : 2}>Assets</Td></tr>
                <Sections sections={d.assets} emptyLabel="No assets" compare={c} />
                <Row label="Total assets" value={d.totalAssets} previous={d.previousTotalAssets} compare={c} className="bg-mint font-semibold text-primary" />
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
      exportSpec={{ report: "LEDGER", params: { accountId, from: r.from, to: r.to } }}
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
