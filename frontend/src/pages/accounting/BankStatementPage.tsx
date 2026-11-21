import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, CheckCircle2, Link2, RefreshCw, Unlink, XCircle } from "lucide-react";
import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { toast } from "sonner";
import { ConfirmDialog, ErrorState, PageHeader, Skeleton, StatusBadge } from "@/components/common";
import { Badge, Button, Card, CardHeader, Dialog, Field, Input, Select, Table, Td, Th } from "@/components/ui";
import { postableAccounts, useAccounts } from "@/hooks/useAccounting";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatINR } from "@/lib/money";
import { formatDate, formatDateTime, humanize } from "@/lib/utils";
import type { BankImport, BankTxn, MatchSuggestion, ReconSummary } from "@/types/accounting";
import { BANK_LABELS } from "./BankTab";

const HINT_ACCOUNT: Record<string, string> = { BANK_CHARGES: "5320", INTEREST: "4210", CASH: "1110" };

export function BankStatementPage() {
  const { id = "" } = useParams();
  const { can } = useAuth();
  const queryClient = useQueryClient();
  const stmt = useQuery({ queryKey: ["bank", id], queryFn: () => api.get<BankImport>(`/bank-reconciliation/statements/${id}`) });
  const txns = useQuery({ queryKey: ["bank", id, "txns"], queryFn: () => api.get<BankTxn[]>(`/bank-reconciliation/statements/${id}/transactions`) });
  const summary = useQuery({ queryKey: ["bank", id, "summary"], queryFn: () => api.get<ReconSummary>(`/bank-reconciliation/statements/${id}/summary`) });
  const [filter, setFilter] = useState<"ALL" | "UNMATCHED" | "MATCHED" | "EXCLUDED">("ALL");
  const [matching, setMatching] = useState<BankTxn | null>(null);
  const [excluding, setExcluding] = useState<BankTxn | null>(null);
  const [journaling, setJournaling] = useState<BankTxn | null>(null);
  const [signing, setSigning] = useState(false);
  const [discarding, setDiscarding] = useState(false);
  const navigate = useNavigate();
  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ["bank", id] });
    queryClient.invalidateQueries({ queryKey: ["accounting"] });
  };
  const auto = useMutation({
    mutationFn: () => api.post<{ matched: number }>(`/bank-reconciliation/statements/${id}/auto-match`),
    onSuccess: (r) => { refresh(); toast.success(`${r.matched} more transaction(s) matched`); },
    onError: (e) => toast.error(e instanceof Error ? e.message : "Failed"),
  });
  const unmatch = useMutation({
    mutationFn: (t: BankTxn) => api.post(`/bank-reconciliation/transactions/${t.id}/unmatch`),
    onSuccess: () => { refresh(); toast.success("Unmatched"); },
    onError: (e) => toast.error(e instanceof Error ? e.message : "Failed"),
  });

  if (stmt.isError) return <ErrorState error={stmt.error} onRetry={() => stmt.refetch()} />;
  if (!stmt.data || !txns.data) return <Skeleton className="h-96" />;
  const s = stmt.data;
  const open = s.status === "IN_PROGRESS";
  const write = can("ACCOUNTING_WRITE") && open;
  const rows = txns.data.filter((t) => filter === "ALL" || t.status === filter);

  return (
    <div>
      <PageHeader title={s.originalFilename} description={`${s.ledgerAccountName} · ${BANK_LABELS[s.bankFormat] ?? s.bankFormat} layout · ${formatDate(s.periodFrom)} – ${formatDate(s.periodTo)}`}
        actions={<Link to={`/accounting?clientId=${s.clientId}&tab=bank`}><Button variant="secondary"><ArrowLeft className="h-4 w-4" /> Statements</Button></Link>} />

      <Card className="mb-5 flex flex-wrap items-center justify-between gap-3 px-5 py-4">
        <div className="flex flex-wrap items-center gap-2 text-sm">
          <StatusBadge status={open ? "REVIEW_REQUIRED" : "APPROVED"} />
          <Badge tone="success">{s.matched} matched</Badge>
          <Badge tone={s.unmatched ? "warning" : "neutral"}>{s.unmatched} open</Badge>
          <Badge>{s.excluded} excluded</Badge>
          {s.duplicateCount > 0 && <span className="text-xs text-muted">{s.duplicateCount} duplicate row(s) skipped on import</span>}
          {!open && <span className="text-xs text-muted">Signed off by {s.approvedBy?.fullName} · {formatDateTime(s.approvedAt)}{s.selfApproved ? " (self-approved)" : ""}</span>}
        </div>
        <div className="flex gap-2">
          {write && <Button size="sm" variant="ghost" className="text-danger" onClick={() => setDiscarding(true)}>Discard import</Button>}
          {write && <Button size="sm" variant="secondary" loading={auto.isPending} onClick={() => auto.mutate()}><RefreshCw className="h-4 w-4" /> Run auto-match</Button>}
          {s.canApprove && <Button size="sm" variant="success" disabled={s.unmatched > 0} title={s.unmatched > 0 ? "Resolve open lines first" : undefined} onClick={() => setSigning(true)}>Sign off reconciliation</Button>}
        </div>
      </Card>

      <div className="grid gap-5 xl:grid-cols-3">
        <Card className="xl:col-span-2">
          <div className="flex gap-1 border-b border-line p-3" role="tablist" aria-label="Filter transactions">
            {(["ALL", "UNMATCHED", "MATCHED", "EXCLUDED"] as const).map((f) => (
              <button key={f} type="button" role="tab" aria-selected={filter === f} onClick={() => setFilter(f)}
                className={`cursor-pointer rounded px-3 py-1 text-sm ${filter === f ? "bg-mint font-medium text-primary" : "text-muted hover:text-ink"}`}>
                {f === "ALL" ? "All" : f === "UNMATCHED" ? "Open" : humanize(f)}
              </button>
            ))}
          </div>
          <Table>
            <thead><tr><Th>Date</Th><Th>Description</Th><Th className="text-right">Withdrawal</Th><Th className="text-right">Deposit</Th><Th>Books</Th><Th className="w-36" /></tr></thead>
            <tbody>
              {rows.map((t) => (
                <tr key={t.id} className="align-top">
                  <Td className="whitespace-nowrap">{formatDate(t.txnDate)}</Td>
                  <Td>
                    <p className="max-w-xs text-xs break-words">{t.description}</p>
                    {t.reference && <p className="font-mono text-[11px] text-muted">{t.reference}</p>}
                    {t.categoryHint && t.status === "UNMATCHED" && <Badge className="mt-1">{humanize(t.categoryHint)}?</Badge>}
                  </Td>
                  <Td className="text-right font-mono tabular-nums">{formatINR(t.withdrawal, { blankZero: true })}</Td>
                  <Td className="text-right font-mono tabular-nums">{formatINR(t.deposit, { blankZero: true })}</Td>
                  <Td className="text-xs">
                    {t.status === "MATCHED" && t.matchedLine ? (
                      <><Link to={`/accounting/journals/${t.matchedLine.journalId}`} className="font-mono text-primary hover:underline">{t.matchedLine.entryNumber}</Link>
                        <p className="text-muted">{humanize(t.matchType)} · {t.matchConfidence}%</p></>
                    ) : t.status === "EXCLUDED" ? <span className="text-muted">Excluded: {t.exclusionReason}</span>
                      : <span className="text-warning">Not in books</span>}
                  </Td>
                  <Td>
                    {write && t.status === "UNMATCHED" && (
                      <div className="flex flex-col gap-1">
                        <Button size="sm" variant="secondary" onClick={() => setMatching(t)}><Link2 className="h-3.5 w-3.5" /> Match</Button>
                        <Button size="sm" variant="ghost" onClick={() => setJournaling(t)}>Record journal</Button>
                        <Button size="sm" variant="ghost" className="text-muted" onClick={() => setExcluding(t)}>Exclude</Button>
                      </div>
                    )}
                    {write && t.status !== "UNMATCHED" && (
                      <Button size="sm" variant="ghost" onClick={() => unmatch.mutate(t)}><Unlink className="h-3.5 w-3.5" /> Undo</Button>
                    )}
                  </Td>
                </tr>
              ))}
            </tbody>
          </Table>
        </Card>

        <Card className="h-fit">
          <CardHeader title="Reconciliation statement" description={summary.data ? `As at ${formatDate(summary.data.asOf)}` : undefined} />
          {summary.data && <BrsTable s={summary.data} />}
        </Card>
      </div>

      {matching && <MatchDialog txn={matching} onClose={() => setMatching(null)} onDone={refresh} />}
      {journaling && <JournalDialog txn={journaling} clientId={s.clientId} bankLedgerId={s.ledgerAccountId} onClose={() => setJournaling(null)} />}
      {excluding && (
        <ConfirmDialog open onOpenChange={(o) => !o && setExcluding(null)} title="Exclude bank line"
          description="Excluded lines stay on the reconciliation statement as acknowledged differences between bank and books."
          confirmLabel="Exclude" commentLabel="Reason" commentRequired
          onConfirm={async (reason) => { await api.post(`/bank-reconciliation/transactions/${excluding.id}/exclude`, { reason }); refresh(); }} />
      )}
      {discarding && (
        <ConfirmDialog open onOpenChange={(o) => !o && setDiscarding(false)} title="Discard this import"
          description="Removes this statement and all its matches so it can be imported again (for example against the correct bank ledger). Posted journals are not affected. The discard is audited."
          confirmLabel="Discard import" tone="danger"
          onConfirm={async () => {
            await api.del(`/bank-reconciliation/statements/${id}`);
            queryClient.invalidateQueries({ queryKey: ["accounting"] });
            toast.success("Import discarded");
            navigate(`/accounting?clientId=${s.clientId}&tab=bank`);
          }} />
      )}
      {signing && (
        <ConfirmDialog open onOpenChange={(o) => !o && setSigning(false)} title="Sign off reconciliation"
          description={summary.data?.reconciled ? "Bank and books reconcile. Signing off freezes this reconciliation."
            : `There is an unexplained difference of ₹${formatINR(summary.data?.difference ?? "0")}. Sign off only if you have investigated it.`}
          confirmLabel="Sign off" tone="success" commentLabel="Comment (optional)"
          onConfirm={async (reason) => { await api.post(`/bank-reconciliation/statements/${id}/approve`, { reason: reason || null }); refresh(); toast.success("Reconciliation signed off"); }} />
      )}
    </div>
  );
}

function BrsTable({ s }: { s: ReconSummary }) {
  const row = (label: string, value: string | null, sign = "") => (
    <div className="flex justify-between gap-3 py-1"><dt className="text-muted">{label}</dt><dd className="font-mono">{sign}{value ? formatINR(value) : "—"}</dd></div>
  );
  return (
    <div className="p-5 text-sm">
      <dl>
        {row("Balance as per books", s.balancePerBooks)}
        {row("Add: deposits in bank, not in books", s.bankOnlyDeposits, "+ ")}
        {row("Less: withdrawals in bank, not in books", s.bankOnlyWithdrawals, "− ")}
        {row("Less: book debits not yet in bank", s.bookOnlyDebits, "− ")}
        {row("Add: book credits not yet in bank", s.bookOnlyCredits, "+ ")}
        <div className="mt-1 flex justify-between border-t border-line pt-2 font-medium"><dt>Adjusted book balance</dt><dd className="font-mono">{formatINR(s.adjustedBookBalance)}</dd></div>
        {row("Balance as per bank statement", s.balancePerBank)}
      </dl>
      {s.reconciled ? (
        <p className="mt-3 flex items-center gap-1.5 text-emerald"><CheckCircle2 className="h-4 w-4" /> Reconciled — no unexplained difference</p>
      ) : (
        <p role="alert" className="mt-3 flex items-center gap-1.5 text-danger"><XCircle className="h-4 w-4" /> Unexplained difference ₹{formatINR(s.difference ?? "0")}</p>
      )}
      {s.bookOnlyEntries.length > 0 && (
        <div className="mt-4">
          <p className="text-xs font-medium text-muted">In books, not in this statement</p>
          <ul className="mt-1 space-y-1 text-xs">
            {s.bookOnlyEntries.map((e) => (
              <li key={e.lineId} className="flex justify-between gap-2">
                <Link to={`/accounting/journals/${e.journalId}`} className="truncate text-primary hover:underline">{e.entryNumber} · {e.narration}</Link>
                <span className="font-mono">{formatINR(e.amount)}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

function MatchDialog({ txn, onClose, onDone }: { txn: BankTxn; onClose: () => void; onDone: () => void }) {
  const s = useQuery({ queryKey: ["bank", "suggest", txn.id], queryFn: () => api.get<MatchSuggestion[]>(`/bank-reconciliation/transactions/${txn.id}/suggestions`) });
  const [busy, setBusy] = useState<string | null>(null);
  const amount = txn.deposit !== "0.00" ? txn.deposit : txn.withdrawal;
  const choose = async (lineId: string) => {
    setBusy(lineId);
    try {
      await api.post(`/bank-reconciliation/transactions/${txn.id}/match`, { lineId });
      toast.success("Matched");
      onDone();
      onClose();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Failed");
    } finally {
      setBusy(null);
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} wide title="Match with a posted entry"
      description={`${formatDate(txn.txnDate)} · ${txn.description} · ₹${formatINR(amount)} ${txn.deposit !== "0.00" ? "deposit" : "withdrawal"}`}>
      {s.isPending ? <Skeleton className="h-24" /> : s.data?.length === 0 ? (
        <p className="text-sm text-muted">No posted entry on this bank ledger has the same amount and direction within 10 days. Use “Record journal” to book it.</p>
      ) : (
        <ul className="space-y-2">
          {s.data?.map((m) => (
            <li key={m.lineId} className="flex items-center justify-between gap-3 rounded-md border border-line p-3">
              <div className="text-sm">
                <p><span className="font-mono">{m.entryNumber}</span> · {formatDate(m.entryDate)} · ₹{formatINR(m.amount)}</p>
                <p className="text-xs text-muted">{m.narration}</p>
                <p className="mt-1 text-xs"><Badge tone={m.matchType === "PROBABLE" ? "success" : "warning"}>{humanize(m.matchType)} · {m.confidence}%</Badge> <span className="text-muted">{m.evidence.join(" · ")}</span></p>
              </div>
              <Button size="sm" loading={busy === m.lineId} onClick={() => choose(m.lineId)}>Match</Button>
            </li>
          ))}
        </ul>
      )}
    </Dialog>
  );
}

function JournalDialog({ txn, clientId, bankLedgerId, onClose }: { txn: BankTxn; clientId: string; bankLedgerId: string; onClose: () => void }) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const accounts = useAccounts(clientId);
  const options = postableAccounts(accounts.data).filter((a) => a.id !== bankLedgerId);
  const suggested = txn.categoryHint ? options.find((a) => a.code === HINT_ACCOUNT[txn.categoryHint!])?.id : undefined;
  const [account, setAccount] = useState<string>("");
  const [narration, setNarration] = useState(txn.description);
  const [busy, setBusy] = useState(false);
  const submit = async () => {
    setBusy(true);
    try {
      const r = await api.post<{ journalId: string }>(`/bank-reconciliation/transactions/${txn.id}/create-journal`,
        { counterAccountId: account || suggested, narration });
      queryClient.invalidateQueries({ queryKey: ["accounting"] });
      toast.success("Draft journal created. Submit it for approval; once posted, run auto-match.", {
        action: { label: "Open", onClick: () => navigate(`/accounting/journals/${r.journalId}`) },
      });
      onClose();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Failed");
    } finally {
      setBusy(false);
    }
  };
  const deposit = txn.deposit !== "0.00";
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title="Record a journal for this bank line"
      description={`Creates a draft: ${deposit ? "Dr Bank / Cr the account below" : "Dr the account below / Cr Bank"} for ₹${formatINR(deposit ? txn.deposit : txn.withdrawal)}. It still needs approval before posting.`}
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button loading={busy} disabled={!(account || suggested)} onClick={submit}>Create draft journal</Button></>}>
      <div className="space-y-4">
        <Field label={deposit ? "Credit account" : "Debit account"} htmlFor="j-acc" hint={txn.categoryHint ? `Suggested from the description: ${humanize(txn.categoryHint)}` : undefined}>
          <Select id="j-acc" value={account || suggested || ""} onChange={(e) => setAccount(e.target.value)}>
            <option value="">Choose…</option>
            {options.map((a) => <option key={a.id} value={a.id}>{a.code} · {a.name}</option>)}
          </Select>
        </Field>
        <Field label="Narration" htmlFor="j-narr"><Input id="j-narr" value={narration} onChange={(e) => setNarration(e.target.value)} maxLength={1000} /></Field>
      </div>
    </Dialog>
  );
}
