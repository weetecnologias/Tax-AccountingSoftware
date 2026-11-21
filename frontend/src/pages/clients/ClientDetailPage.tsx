import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { Pencil, Upload } from "lucide-react";
import { useState } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { toast } from "sonner";
import { AuditTable } from "@/components/audit";
import { ConfirmDialog, DetailList, ErrorState, PageHeader, Pagination, Skeleton, StatusBadge } from "@/components/common";
import { DocumentsTable, UploadDialog } from "@/components/documents";
import { Button, Card, CardHeader, Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui";
import { labelOf, useEntityTypes } from "@/hooks/useReference";
import { api, qs } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDate, formatDateTime, humanize, MONTHS } from "@/lib/utils";
import type { AuditEntry, ClientDetail, ClientStatus, DocumentItem, Page, StatusHistoryEntry } from "@/types/api";
import { BankAccountsTab } from "./tabs/BankAccountsTab";
import { ContactsTab } from "./tabs/ContactsTab";
import { RegistrationsTab } from "./tabs/RegistrationsTab";

type TransitionMeta = { label: string; tone: "primary" | "success" | "danger"; description: string; comment?: "optional" | "closure" };

function transitionMeta(from: ClientStatus, to: ClientStatus): TransitionMeta {
  switch (to) {
    case "DOCUMENTS_PENDING":
      return { label: "Mark documents pending", tone: "primary", description: "Records that KYC or business documents are still awaited.", comment: "optional" };
    case "UNDER_VERIFICATION":
      return from === "READY_FOR_APPROVAL"
        ? { label: "Send back for verification", tone: "primary", description: "Returns the client to verification for corrections.", comment: "optional" }
        : { label: "Start verification", tone: "primary", description: "Moves the client into verification of its details and documents.", comment: "optional" };
    case "READY_FOR_APPROVAL":
      return { label: "Submit for approval", tone: "primary", description: "A different user with approval rights must activate the client. PAN and entity type become locked.", comment: "optional" };
    case "ACTIVE":
      return from === "ON_HOLD"
        ? { label: "Reactivate", tone: "success", description: "Takes the client off hold.", comment: "optional" }
        : { label: "Approve & activate", tone: "success", description: "Activates the client. Your approval is recorded with your name and time.", comment: "optional" };
    case "ON_HOLD":
      return { label: "Put on hold", tone: "primary", description: "Pauses work on this client.", comment: "optional" };
    case "CLOSED":
      return { label: "Close client", tone: "danger", description: "The client becomes read-only. No records are deleted; everything is retained for audit.", comment: "closure" };
    default:
      return { label: humanize(to), tone: "primary", description: "" };
  }
}

export function ClientDetailPage() {
  const { id = "" } = useParams();
  const { can } = useAuth();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const entityTypes = useEntityTypes();
  const [params, setParams] = useSearchParams();
  const tab = params.get("tab") ?? "overview";
  const [pending, setPending] = useState<ClientStatus | null>(null);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [docPage, setDocPage] = useState(0);
  const [auditPage, setAuditPage] = useState(0);

  const client = useQuery({ queryKey: ["client", id], queryFn: () => api.get<ClientDetail>(`/clients/${id}`) });
  const history = useQuery({
    queryKey: ["client", id, "history"],
    queryFn: () => api.get<StatusHistoryEntry[]>(`/clients/${id}/status-history`),
  });
  const documents = useQuery({
    queryKey: ["documents", "client", id, docPage],
    queryFn: () => api.get<Page<DocumentItem>>(`/clients/${id}/documents${qs({ page: docPage, size: 20 })}`),
    enabled: tab === "documents" && can("DOCUMENT_READ"),
    placeholderData: keepPreviousData,
  });
  const audit = useQuery({
    queryKey: ["audit", "client", id, auditPage],
    queryFn: () => api.get<Page<AuditEntry>>(`/clients/${id}/audit${qs({ page: auditPage, size: 25 })}`),
    enabled: tab === "audit" && can("AUDIT_READ"),
    placeholderData: keepPreviousData,
  });

  if (client.isError) return <ErrorState error={client.error} onRetry={() => client.refetch()} />;
  if (client.isPending) return <div className="space-y-4"><Skeleton className="h-16" /><Skeleton className="h-80" /></div>;
  const c = client.data;
  const closed = c.status === "CLOSED";
  const editable = can("CLIENT_WRITE") && !closed;

  const changeStatus = async (comment: string) => {
    if (!pending) return;
    const body = pending === "CLOSED" ? { targetStatus: pending, closureReason: comment } : { targetStatus: pending, comment: comment || null };
    const updated = await api.post<ClientDetail>(`/clients/${id}/status`, body);
    queryClient.setQueryData(["client", id], updated);
    queryClient.invalidateQueries({ queryKey: ["client", id, "history"] });
    queryClient.invalidateQueries({ queryKey: ["clients"] });
    queryClient.invalidateQueries({ queryKey: ["dashboard"] });
    toast.success(`Client is now ${humanize(updated.status).toLowerCase()}`);
  };

  const meta = pending ? transitionMeta(c.status, pending) : null;

  return (
    <div>
      <PageHeader
        title={c.displayName}
        description={`${c.clientCode} · ${labelOf(entityTypes.data, c.entityType)}`}
        actions={
          <>
            {can("DOCUMENT_UPLOAD") && !closed && (
              <Button variant="secondary" onClick={() => setUploadOpen(true)}><Upload className="h-4 w-4" /> Upload document</Button>
            )}
            {editable && c.panRevealed && (
              <Button variant="secondary" onClick={() => navigate(`/clients/${id}/edit`)}><Pencil className="h-4 w-4" /> Edit</Button>
            )}
          </>
        }
      />

      <Card className="mb-5 flex flex-wrap items-center justify-between gap-3 px-5 py-4">
        <div className="flex items-center gap-3">
          <span className="text-sm text-muted">Status</span>
          <StatusBadge status={c.status} />
          {c.counts.documentsAwaitingReview > 0 && (
            <span className="text-xs text-muted">{c.counts.documentsAwaitingReview} document(s) awaiting review</span>
          )}
        </div>
        <div className="flex flex-wrap gap-2">
          {c.allowedTransitions.map((t) => {
            const m = transitionMeta(c.status, t);
            return (
              <Button key={t} size="sm" variant={m.tone === "danger" ? "secondary" : m.tone} onClick={() => setPending(t)}
                className={m.tone === "danger" ? "text-danger" : undefined}>
                {m.label}
              </Button>
            );
          })}
        </div>
      </Card>

      <Tabs value={tab} onValueChange={(v) => setParams({ tab: v }, { replace: true })}>
        <TabsList aria-label="Client sections">
          <TabsTrigger value="overview">Overview</TabsTrigger>
          <TabsTrigger value="registrations">Registrations ({c.counts.gstRegistrations})</TabsTrigger>
          <TabsTrigger value="contacts">Contacts &amp; addresses</TabsTrigger>
          <TabsTrigger value="banks">Bank accounts</TabsTrigger>
          {can("DOCUMENT_READ") && <TabsTrigger value="documents">Documents ({c.counts.documents})</TabsTrigger>}
          {can("AUDIT_READ") && <TabsTrigger value="audit">Audit trail</TabsTrigger>}
        </TabsList>

        <TabsContent value="overview" className="mt-5 space-y-5">
          <Card>
            <CardHeader title="Organisation details" />
            <div className="p-5">
              <DetailList items={[
                { label: "Legal name", value: c.legalName },
                { label: "Entity type", value: labelOf(entityTypes.data, c.entityType) },
                { label: "PAN", value: <span className="font-mono">{c.pan}</span> },
                { label: "TAN", value: c.tan ? <span className="font-mono">{c.tan}</span> : "—" },
                { label: "Incorporation / birth date", value: formatDate(c.dateOfIncorporation) },
                { label: "Financial year starts", value: MONTHS[c.fyStartMonth - 1] },
                { label: "Accounting method", value: humanize(c.accountingMethod) },
                { label: "Email", value: c.email ?? "—" },
                { label: "Phone", value: c.phone ?? "—" },
                { label: "Accountant", value: c.accountant?.fullName ?? <span className="text-warning">Unassigned</span> },
                { label: "Reviewer", value: c.reviewer?.fullName ?? <span className="text-warning">Unassigned</span> },
                { label: "Created", value: formatDateTime(c.createdAt) },
              ]} />
              {c.notes && <p className="mt-5 rounded-md bg-page p-3 text-sm whitespace-pre-wrap">{c.notes}</p>}
              {closed && (
                <p className="mt-5 rounded-md bg-danger-soft p-3 text-sm text-danger">
                  Closed {formatDateTime(c.closedAt)} — {c.closureReason}
                </p>
              )}
            </div>
          </Card>
          <Card>
            <CardHeader title="Status history" description="Every onboarding step with who made it" />
            <ol className="divide-y divide-line">
              {history.data?.map((h) => (
                <li key={h.id} className="flex flex-wrap items-center justify-between gap-2 px-5 py-3 text-sm">
                  <div className="flex flex-wrap items-center gap-2">
                    {h.fromStatus && <><StatusBadge status={h.fromStatus} /><span className="text-muted">→</span></>}
                    <StatusBadge status={h.toStatus} />
                    {h.comment && <span className="text-muted">“{h.comment}”</span>}
                  </div>
                  <span className="text-xs text-muted">{h.changedBy?.fullName} · {formatDateTime(h.changedAt)}</span>
                </li>
              ))}
            </ol>
          </Card>
        </TabsContent>

        <TabsContent value="registrations" className="mt-5">
          <RegistrationsTab clientId={id} editable={editable} />
        </TabsContent>
        <TabsContent value="contacts" className="mt-5">
          <ContactsTab clientId={id} editable={editable} />
        </TabsContent>
        <TabsContent value="banks" className="mt-5">
          <BankAccountsTab clientId={id} editable={editable} />
        </TabsContent>
        {can("DOCUMENT_READ") && (
          <TabsContent value="documents" className="mt-5">
            <Card>
              {documents.isError ? <ErrorState error={documents.error} onRetry={() => documents.refetch()} /> : (
                <>
                  <DocumentsTable query={documents} showClient={false} />
                  {documents.data && <Pagination page={documents.data.page} totalPages={documents.data.totalPages}
                    totalItems={documents.data.totalItems} onPage={setDocPage} />}
                </>
              )}
            </Card>
          </TabsContent>
        )}
        {can("AUDIT_READ") && (
          <TabsContent value="audit" className="mt-5">
            <Card>
              {audit.isError ? <ErrorState error={audit.error} onRetry={() => audit.refetch()} /> : (
                <>
                  <AuditTable query={audit} />
                  {audit.data && <Pagination page={audit.data.page} totalPages={audit.data.totalPages}
                    totalItems={audit.data.totalItems} onPage={setAuditPage} />}
                </>
              )}
            </Card>
            <p className="mt-2 text-xs text-muted">
              The full log is in the <Link to={`/audit?clientId=${id}`} className="text-emerald hover:underline">Audit Log</Link>.
            </p>
          </TabsContent>
        )}
      </Tabs>

      {meta && (
        <ConfirmDialog
          open={!!pending}
          onOpenChange={(o) => !o && setPending(null)}
          title={meta.label}
          description={meta.description}
          confirmLabel={meta.label}
          tone={meta.tone}
          commentLabel={meta.comment === "closure" ? "Closure reason" : "Comment (optional)"}
          commentRequired={meta.comment === "closure"}
          onConfirm={changeStatus}
        />
      )}
      <UploadDialog open={uploadOpen} onOpenChange={setUploadOpen} fixedClient={{ id, name: c.displayName }} />
    </div>
  );
}
