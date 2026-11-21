import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ShieldCheck } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";
import { ConfirmDialog, ErrorState, LoadingRows } from "@/components/common";
import { Badge, Button, Card, CardHeader, Table, Td, Th } from "@/components/ui";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDate, formatDateTime } from "@/lib/utils";
import type { GstSlab } from "@/types/accounting";

/** Statutory GST rate slabs. Each must be checked against its official source by a tax approver before production use. */
export function TaxRulesTab() {
  const { can } = useAuth();
  const queryClient = useQueryClient();
  const slabs = useQuery({ queryKey: ["settings", "gst-rates"], queryFn: () => api.get<GstSlab[]>("/settings/gst-rates") });
  const [approving, setApproving] = useState<GstSlab | null>(null);
  const pending = slabs.data?.filter((s) => s.reviewStatus === "PENDING_REVIEW").length ?? 0;

  return (
    <Card>
      <CardHeader title="GST rate slabs"
        description="Rates offered on invoices, by effective date. Verify each against the notification shown before relying on it; approvals are recorded with your name and time."
        actions={slabs.data && (pending > 0 ? <Badge tone="warning">{pending} awaiting review</Badge> : <Badge tone="success">All reviewed</Badge>)} />
      {slabs.isError ? <ErrorState error={slabs.error} onRetry={() => slabs.refetch()} /> : (
        <Table>
          <thead><tr><Th className="text-right">Rate</Th><Th>Description</Th><Th>Effective</Th><Th>Official source</Th><Th>Review</Th><Th /></tr></thead>
          <tbody>
            {slabs.isPending ? <LoadingRows cols={6} /> : slabs.data.map((s) => (
              <tr key={s.id}>
                <Td className="text-right font-mono font-medium">{s.rate}%</Td>
                <Td>{s.description}</Td>
                <Td className="whitespace-nowrap text-muted">{formatDate(s.effectiveFrom)} → {s.effectiveTo ? formatDate(s.effectiveTo) : "now"}</Td>
                <Td className="max-w-xs text-xs text-muted">{s.officialSource}</Td>
                <Td>
                  {s.reviewStatus === "APPROVED" ? (
                    <span className="text-xs"><Badge tone="success">Approved</Badge><span className="mt-1 block text-muted">{s.reviewedBy} · {formatDateTime(s.reviewedAt)}</span></span>
                  ) : s.reviewStatus === "RETIRED" ? <Badge>Retired</Badge> : <Badge tone="warning">Pending review</Badge>}
                </Td>
                <Td className="text-right">
                  {s.reviewStatus === "PENDING_REVIEW" && can("TAX_RULES_APPROVE") && (
                    <Button size="sm" variant="secondary" onClick={() => setApproving(s)}><ShieldCheck className="h-3.5 w-3.5" /> Approve</Button>
                  )}
                </Td>
              </tr>
            ))}
          </tbody>
        </Table>
      )}
      {!can("TAX_RULES_APPROVE") && <p className="px-5 py-3 text-xs text-muted">Only partners and administrators can approve rate slabs.</p>}
      {approving && (
        <ConfirmDialog open onOpenChange={(o) => !o && setApproving(null)} title={`Approve ${approving.rate}% — ${approving.description}`}
          description={`Confirm you have checked this rate and its effective dates against: ${approving.officialSource}. Your approval is recorded in the audit log.`}
          confirmLabel="Approve rate" tone="success"
          onConfirm={async () => {
            await api.post(`/settings/gst-rates/${approving.id}/approve`);
            queryClient.invalidateQueries({ queryKey: ["settings", "gst-rates"] });
            queryClient.invalidateQueries({ queryKey: ["gst-rates"] });
            toast.success("Rate approved");
          }} />
      )}
    </Card>
  );
}
