import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router-dom";
import { EmptyState, ErrorState, Skeleton } from "@/components/common";
import { Badge, Field, Input, Select, Table, Td, Th } from "@/components/ui";
import { api, qs } from "@/lib/api";
import { downloadCsv } from "@/lib/csv";
import { formatINR } from "@/lib/money";
import { formatDate } from "@/lib/utils";
import type { Ageing, CashFlow, CashFlowSection, Register, ReportHeader } from "@/types/accounting";
import { amt, BalanceCheck, ReportFrame, useRange } from "./ReportTabs";

// ------------------------------------------------------------------ cash flow

function FlowLines({ section }: { section: CashFlowSection }) {
  if (section.lines.length === 0) return <tr><Td className="pl-8 text-muted" colSpan={2}>None</Td></tr>;
  return <>{section.lines.map((l, i) => <tr key={`${l.label}-${i}`}><Td className="pl-8">{l.label}</Td><Td className={amt}>{formatINR(l.amount)}</Td></tr>)}</>;
}

export function CashFlowTab({ clientId }: { clientId: string }) {
  const r = useRange(clientId);
  const q = useQuery({
    queryKey: ["accounting", clientId, "cf", r.from, r.to],
    queryFn: () => api.get<CashFlow>(`/accounting/cash-flow${qs({ clientId, from: r.from, to: r.to })}`),
    enabled: r.ready && r.from <= r.to,
  });
  const d = q.data;
  return (
    <ReportFrame header={d?.header} title="Cash flow statement (indirect method)"
      exportSpec={{ report: "CASH_FLOW", params: { from: r.from, to: r.to } }}
      controls={<>
        <Field label="From" htmlFor="cf-from"><Input id="cf-from" type="date" value={r.from} onChange={(e) => r.setFrom(e.target.value)} /></Field>
        <Field label="To" htmlFor="cf-to"><Input id="cf-to" type="date" value={r.to} onChange={(e) => r.setTo(e.target.value)} /></Field>
      </>}>
      {q.isError ? <ErrorState error={q.error} onRetry={() => q.refetch()} /> : !d ? <Skeleton className="m-4 h-40" /> : (
        <>
          <Table>
            <tbody>
              <tr className="bg-page"><Td className="font-semibold" colSpan={2}>Cash flows from operating activities</Td></tr>
              <tr><Td className="pl-4">Net profit / (loss)</Td><Td className={amt}>{formatINR(d.netProfit)}</Td></tr>
              <tr><Td className="pl-4 font-medium text-primary" colSpan={2}>{d.nonCashAdjustments.title}</Td></tr>
              <FlowLines section={d.nonCashAdjustments} />
              <tr><Td className="pl-4 font-medium text-primary" colSpan={2}>{d.workingCapital.title}</Td></tr>
              <FlowLines section={d.workingCapital} />
              <tr className="font-semibold"><Td>Net cash from operating activities</Td><Td className={amt}>{formatINR(d.operatingTotal)}</Td></tr>
              {[d.investing, d.financing].map((s) => (
                <FlowBlock key={s.title} section={s} />
              ))}
              <tr className="bg-mint font-semibold text-primary"><Td>Net increase / (decrease) in cash</Td><Td className={amt}>{formatINR(d.netChange)}</Td></tr>
              <tr><Td>Cash and cash equivalents at the beginning</Td><Td className={amt}>{formatINR(d.openingCash)}</Td></tr>
              <tr className="bg-mint font-semibold text-primary"><Td>Cash and cash equivalents at the end</Td><Td className={amt}>{formatINR(d.closingCash)}</Td></tr>
            </tbody>
          </Table>
          <BalanceCheck ok={d.reconciles} label="Agrees with the movement in cash and bank ledgers" />
          {d.unclassifiedAccounts.length > 0 && (
            <p className="px-5 pb-4 text-xs text-muted">
              Treated as operating because no cash-flow category is set: {d.unclassifiedAccounts.join(", ")}. Set the category in the chart of accounts.
            </p>
          )}
        </>
      )}
    </ReportFrame>
  );
}

function FlowBlock({ section }: { section: CashFlowSection }) {
  return (
    <>
      <tr className="bg-page"><Td className="font-semibold" colSpan={2}>{section.title}</Td></tr>
      <FlowLines section={section} />
      <tr className="font-semibold"><Td>Net cash from {section.title.replace(/^Cash flows from /i, "")}</Td><Td className={amt}>{formatINR(section.total)}</Td></tr>
    </>
  );
}

// ------------------------------------------------------------------ ageing

export function AgeingTab({ clientId }: { clientId: string }) {
  const r = useRange(clientId);
  const today = new Date().toISOString().slice(0, 10);
  const [kind, setKind] = useState<"RECEIVABLE" | "PAYABLE">("RECEIVABLE");
  const [asOf, setAsOf] = useState(today);
  const q = useQuery({
    queryKey: ["accounting", clientId, "ageing", kind, asOf],
    queryFn: () => api.get<Ageing>(`/accounting/ageing${qs({ clientId, kind, asOf })}`),
    enabled: r.ready && !!asOf,
  });
  const header = useQuery({
    queryKey: ["accounting", clientId, "tb", asOf],
    queryFn: () => api.get<{ header: ReportHeader }>(`/accounting/trial-balance${qs({ clientId, asOf })}`),
    enabled: !!asOf,
  });
  const d = q.data;
  const label = kind === "RECEIVABLE" ? "Customer" : "Vendor";
  return (
    <ReportFrame header={header.data?.header} title={kind === "RECEIVABLE" ? "Receivables ageing" : "Payables ageing"}
      exportSpec={{ report: kind === "RECEIVABLE" ? "AGEING_RECEIVABLE" : "AGEING_PAYABLE", params: { asOf } }}
      controls={<>
        <Field label="Show" htmlFor="ag-kind">
          <Select id="ag-kind" value={kind} onChange={(e) => setKind(e.target.value as "RECEIVABLE" | "PAYABLE")}>
            <option value="RECEIVABLE">Receivables (customers)</option>
            <option value="PAYABLE">Payables (vendors)</option>
          </Select>
        </Field>
        <Field label="As at" htmlFor="ag-asof"><Input id="ag-asof" type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} /></Field>
      </>}
      onExport={d ? () => downloadCsv(`${kind.toLowerCase()}-ageing-${asOf}.csv`, [label, "GSTIN", "Outstanding", ...d.bucketLabels, "Not linked", "Advance"],
        [...d.rows.map((x) => [x.partyName, x.gstin ?? "", x.outstanding, ...x.buckets, x.unlinked, x.advance]),
          ["Total", "", d.total, ...d.bucketTotals, d.unlinkedTotal, d.advanceTotal]]) : undefined}>
      {q.isError ? <ErrorState error={q.error} onRetry={() => q.refetch()} /> : !d ? <Skeleton className="m-4 h-40" /> : d.rows.length === 0 ? (
        <EmptyState title="Nothing outstanding" description={`No ${kind === "RECEIVABLE" ? "customer" : "vendor"} balances as at ${formatDate(asOf)}.`} />
      ) : (
        <>
          <div className="overflow-x-auto">
            <Table>
              <thead>
                <tr>
                  <Th>{label}</Th><Th className="text-right">Outstanding</Th>
                  {d.bucketLabels.map((b) => <Th key={b} className="text-right">{b}</Th>)}
                  <Th className="text-right">Not linked</Th><Th className="text-right">Advance</Th>
                </tr>
              </thead>
              <tbody>
                {d.rows.map((x) => (
                  <tr key={x.partyId ?? "unassigned"}>
                    <Td>{x.partyName}{x.gstin && <span className="block font-mono text-xs text-muted">{x.gstin}</span>}</Td>
                    <Td className={`${amt} font-medium`}>{formatINR(x.outstanding)}</Td>
                    {x.buckets.map((b, i) => <Td key={i} className={`${amt} ${i === 4 && b !== "0.00" ? "text-danger" : ""}`}>{formatINR(b, { blankZero: true })}</Td>)}
                    <Td className={amt}>{formatINR(x.unlinked, { blankZero: true })}</Td>
                    <Td className={amt}>{formatINR(x.advance, { blankZero: true })}</Td>
                  </tr>
                ))}
                <tr className="bg-page font-semibold">
                  <Td>Total</Td><Td className={amt}>{formatINR(d.total)}</Td>
                  {d.bucketTotals.map((b, i) => <Td key={i} className={amt}>{formatINR(b)}</Td>)}
                  <Td className={amt}>{formatINR(d.unlinkedTotal)}</Td><Td className={amt}>{formatINR(d.advanceTotal)}</Td>
                </tr>
              </tbody>
            </Table>
          </div>
          <BalanceCheck ok={d.reconciles} label={`Agrees with the ${kind === "RECEIVABLE" ? "trade receivables" : "trade payables"} control ledger (₹${formatINR(d.controlBalance)})`} />
          <p className="px-5 pb-4 text-xs text-muted">
            Balances are matched to the newest invoices first (payments are assumed to settle the oldest). Days are counted from each invoice's due date.
            "Not linked" is a balance that cannot be traced to a posted invoice, such as an opening balance.
          </p>
        </>
      )}
    </ReportFrame>
  );
}

// ------------------------------------------------------------------ registers

export function RegistersTab({ clientId }: { clientId: string }) {
  const r = useRange(clientId);
  const [kind, setKind] = useState<"SALES" | "PURCHASE">("SALES");
  const q = useQuery({
    queryKey: ["accounting", clientId, "register", kind, r.from, r.to],
    queryFn: () => api.get<Register>(`/accounting/registers${qs({ clientId, kind, from: r.from, to: r.to })}`),
    enabled: r.ready && r.from <= r.to,
  });
  const header = useQuery({
    queryKey: ["accounting", clientId, "tb", r.to],
    queryFn: () => api.get<{ header: ReportHeader }>(`/accounting/trial-balance${qs({ clientId, asOf: r.to })}`),
    enabled: r.ready,
  });
  const d = q.data;
  const sales = kind === "SALES";
  return (
    <ReportFrame header={header.data?.header ? { ...header.data.header, from: r.from, to: r.to } : undefined}
      title={sales ? "Sales register" : "Purchase register"}
      exportSpec={{ report: sales ? "SALES_REGISTER" : "PURCHASE_REGISTER", params: { from: r.from, to: r.to } }}
      controls={<>
        <Field label="Register" htmlFor="rg-kind">
          <Select id="rg-kind" value={kind} onChange={(e) => setKind(e.target.value as "SALES" | "PURCHASE")}>
            <option value="SALES">Sales (with credit notes)</option>
            <option value="PURCHASE">Purchases (with debit notes)</option>
          </Select>
        </Field>
        <Field label="From" htmlFor="rg-from"><Input id="rg-from" type="date" value={r.from} onChange={(e) => r.setFrom(e.target.value)} /></Field>
        <Field label="To" htmlFor="rg-to"><Input id="rg-to" type="date" value={r.to} onChange={(e) => r.setTo(e.target.value)} /></Field>
      </>}
      onExport={d ? () => downloadCsv(`${kind.toLowerCase()}-register-${r.from}-${r.to}.csv`,
        ["Date", "Type", "Number", "Party", "GSTIN", "Place of supply", "Reverse charge", "Taxable", "CGST", "SGST", "IGST", "Total"],
        [...d.rows.map((x) => [x.date, x.documentType, x.number, x.partyName, x.partyGstin ?? "", x.placeOfSupply, x.reverseCharge ? "Yes" : "No",
          x.taxable, x.cgst, x.sgst, x.igst, x.total]), ["", "", "", "Total", "", "", "", d.taxable, d.cgst, d.sgst, d.igst, d.total]]) : undefined}>
      {q.isError ? <ErrorState error={q.error} onRetry={() => q.refetch()} /> : !d ? <Skeleton className="m-4 h-40" /> : d.rows.length === 0 ? (
        <EmptyState title="No posted documents in this period" />
      ) : (
        <div className="overflow-x-auto">
          <Table>
            <thead>
              <tr><Th>Date</Th><Th>Document</Th><Th>{sales ? "Customer" : "Vendor"}</Th><Th>POS</Th><Th className="text-right">Taxable</Th>
                <Th className="text-right">CGST</Th><Th className="text-right">SGST</Th><Th className="text-right">IGST</Th><Th className="text-right">Total</Th></tr>
            </thead>
            <tbody>
              {d.rows.map((x) => (
                <tr key={x.invoiceId}>
                  <Td className="whitespace-nowrap">{formatDate(x.date)}</Td>
                  <Td>
                    <Link to={`/accounting/invoices/${x.invoiceId}`} className="font-mono text-xs text-primary hover:underline">{x.number}</Link>
                    {x.documentType.endsWith("_NOTE") && <Badge tone="warning" className="ml-1.5">{x.documentType === "CREDIT_NOTE" ? "CN" : "DN"}</Badge>}
                  </Td>
                  <Td>{x.partyName}{x.reverseCharge && <Badge tone="warning" className="ml-1.5">RCM</Badge>}
                    <span className="block font-mono text-xs text-muted">{x.partyGstin ?? "Unregistered"}</span></Td>
                  <Td className="font-mono text-xs">{x.placeOfSupply}</Td>
                  <Td className={amt}>{formatINR(x.taxable)}</Td>
                  <Td className={amt}>{formatINR(x.cgst, { blankZero: true })}</Td>
                  <Td className={amt}>{formatINR(x.sgst, { blankZero: true })}</Td>
                  <Td className={amt}>{formatINR(x.igst, { blankZero: true })}</Td>
                  <Td className={`${amt} font-medium`}>{formatINR(x.total)}</Td>
                </tr>
              ))}
              <tr className="bg-page font-semibold">
                <Td colSpan={4}>Total ({d.rows.length} documents)</Td>
                <Td className={amt}>{formatINR(d.taxable)}</Td><Td className={amt}>{formatINR(d.cgst)}</Td>
                <Td className={amt}>{formatINR(d.sgst)}</Td><Td className={amt}>{formatINR(d.igst)}</Td><Td className={amt}>{formatINR(d.total)}</Td>
              </tr>
            </tbody>
          </Table>
          <p className="px-5 py-3 text-xs text-muted">{sales ? "Credit notes" : "Debit notes"} are shown as negative amounts.</p>
        </div>
      )}
    </ReportFrame>
  );
}
