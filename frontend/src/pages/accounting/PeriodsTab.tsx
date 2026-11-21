import { useMutation, useQueryClient } from "@tanstack/react-query";
import { BookCheck, CalendarPlus, Lock, Unlock } from "lucide-react";
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { toast } from "sonner";
import { ConfirmDialog, StatusBadge } from "@/components/common";
import { Badge, Button, Card, CardHeader, Input, Table, Td, Th } from "@/components/ui";
import { usePeriods } from "@/hooks/useAccounting";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDateTime } from "@/lib/utils";
import type { JournalDetail, Period } from "@/types/accounting";

const monthLabel = (iso: string) => new Date(`${iso}T00:00:00`).toLocaleDateString("en-IN", { month: "long", year: "numeric" });

export function PeriodsTab({ clientId }: { clientId: string; fyStartMonth: number }) {
  const { can } = useAuth();
  const queryClient = useQueryClient();
  const periods = usePeriods(clientId);
  const navigate = useNavigate();
  const [action, setAction] = useState<{ period: Period; kind: "close" | "reopen" } | null>(null);
  const [closingYear, setClosingYear] = useState<string | null>(null);
  const last = periods.data?.[periods.data.length - 1];
  const nextFy = last ? `${Number(last.financialYear.slice(0, 4)) + 1}-${String((Number(last.financialYear.slice(0, 4)) + 2) % 100).padStart(2, "0")}` : "";
  const [fy, setFy] = useState(nextFy);
  const refresh = () => queryClient.invalidateQueries({ queryKey: ["accounting", clientId] });
  const openYear = useMutation({
    mutationFn: () => api.post("/accounting/financial-years", { clientId, financialYear: fy || nextFy }),
    onSuccess: () => { toast.success("Financial year opened"); refresh(); },
    onError: (e) => toast.error(e instanceof Error ? e.message : "Failed"),
  });

  const years = [...new Set(periods.data?.map((p) => p.financialYear))];
  return (
    <div className="space-y-5">
      {years.map((y) => {
        const inYear = periods.data?.filter((p) => p.financialYear === y) ?? [];
        const yearClosed = inYear.some((p) => p.yearClosed);
        return (
        <Card key={y}>
          <CardHeader title={`Financial year ${y}`}
            description={yearClosed ? "The year has been closed: profit or loss was transferred to retained earnings and no period can be reopened."
              : "Closed periods accept no postings. Reopening needs a reason and is audited."}
            actions={yearClosed ? <Badge tone="success">Year closed</Badge> : can("ACCOUNTING_WRITE") && (
              <Button size="sm" variant="secondary" onClick={() => setClosingYear(y)}><BookCheck className="h-3.5 w-3.5" /> Prepare year-end close</Button>
            )} />
          <Table>
            <thead><tr><Th>Period</Th><Th>Dates</Th><Th>Status</Th><Th>Closed by</Th><Th /></tr></thead>
            <tbody>
              {inYear.map((p) => (
                <tr key={p.id}>
                  <Td className="font-medium">{monthLabel(p.startDate)}</Td>
                  <Td className="text-muted">{p.startDate} → {p.endDate}</Td>
                  <Td><StatusBadge status={p.status === "OPEN" ? "ACTIVE" : "CLOSED"} /></Td>
                  <Td className="text-muted">{p.closedBy ? `${p.closedBy.fullName} · ${formatDateTime(p.closedAt)}` : "—"}</Td>
                  <Td className="text-right">
                    {can("ACCOUNTING_APPROVE") && !p.yearClosed && (p.status === "OPEN"
                      ? <Button size="sm" variant="secondary" onClick={() => setAction({ period: p, kind: "close" })}><Lock className="h-3.5 w-3.5" /> Close</Button>
                      : <Button size="sm" variant="secondary" onClick={() => setAction({ period: p, kind: "reopen" })}><Unlock className="h-3.5 w-3.5" /> Reopen</Button>)}
                  </Td>
                </tr>
              ))}
            </tbody>
          </Table>
        </Card>
        );
      })}
      {can("ACCOUNTING_WRITE") && (
        <Card className="flex flex-wrap items-center gap-3 p-4">
          <CalendarPlus className="h-4 w-4 text-emerald" />
          <span className="text-sm">Open another financial year</span>
          <Input aria-label="Financial year" value={fy || nextFy} onChange={(e) => setFy(e.target.value)} maxLength={7} className="w-28" />
          <Button size="sm" loading={openYear.isPending} onClick={() => openYear.mutate()}>Open year</Button>
        </Card>
      )}
      {closingYear && (
        <ConfirmDialog open onOpenChange={(o) => !o && setClosingYear(null)} title={`Prepare year-end close for ${closingYear}`}
          description={`Creates a draft closing journal dated on the last day of ${closingYear} that moves every income and expense balance to retained earnings. It then goes through approval like any journal; posting it closes all twelve periods permanently. All journals of the year must be posted or cancelled first.`}
          confirmLabel="Prepare draft" tone="primary"
          onConfirm={async () => {
            const j = await api.post<JournalDetail>(`/accounting/financial-years/${closingYear}/prepare-close`, { clientId });
            refresh();
            toast.success("Closing journal prepared as a draft. Submit it for approval.");
            navigate(`/accounting/journals/${j.id}`);
          }} />
      )}
      {action && (
        <ConfirmDialog open onOpenChange={(o) => !o && setAction(null)}
          title={action.kind === "close" ? `Close ${monthLabel(action.period.startDate)}` : `Reopen ${monthLabel(action.period.startDate)}`}
          description={action.kind === "close"
            ? "No journal can be posted into this period while it is closed. Journals awaiting approval or posting must be resolved first."
            : "Reopening allows postings again. Your reason is recorded in the audit log."}
          confirmLabel={action.kind === "close" ? "Close period" : "Reopen period"}
          tone={action.kind === "close" ? "primary" : "danger"}
          commentLabel={action.kind === "reopen" ? "Reason" : undefined}
          commentRequired={action.kind === "reopen"}
          onConfirm={async (reason) => {
            await api.post(`/accounting/periods/${action.period.id}/${action.kind}`, action.kind === "reopen" ? { reason } : undefined);
            refresh();
            toast.success(action.kind === "close" ? "Period closed" : "Period reopened");
          }} />
      )}
    </div>
  );
}
