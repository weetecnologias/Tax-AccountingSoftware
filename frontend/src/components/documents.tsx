import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Archive, Check, Download, Upload, X } from "lucide-react";
import { useRef, useState } from "react";
import { Link } from "react-router-dom";
import { toast } from "sonner";
import { ConfirmDialog, EmptyState, LoadingRows, StatusBadge } from "@/components/common";
import { Button, Dialog, Field, Input, Select, Table, Td, Th } from "@/components/ui";
import { labelOf, useDocumentTypes } from "@/hooks/useReference";
import { ApiError, api, downloadFile, qs } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatBytes, formatDateTime } from "@/lib/utils";
import type { ClientSummary, DocumentItem, Page } from "@/types/api";

const MAX_BYTES = 25 * 1024 * 1024;
const ACCEPT = ".pdf,.png,.jpg,.jpeg,.xlsx,.xls,.csv";

function currentFy(): string {
  const now = new Date();
  const start = now.getMonth() >= 3 ? now.getFullYear() : now.getFullYear() - 1;
  return `${start}-${String((start + 1) % 100).padStart(2, "0")}`;
}

export function UploadDialog({ open, onOpenChange, fixedClient }: {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  fixedClient?: { id: string; name: string };
}) {
  const queryClient = useQueryClient();
  const docTypes = useDocumentTypes();
  const fileRef = useRef<HTMLInputElement>(null);
  const [clientSearch, setClientSearch] = useState("");
  const [clientId, setClientId] = useState(fixedClient?.id ?? "");
  const [documentType, setDocumentType] = useState("");
  const [financialYear, setFinancialYear] = useState(currentFy());
  const [file, setFile] = useState<File | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});

  const clients = useQuery({
    queryKey: ["clients", "picker", clientSearch],
    queryFn: () => api.get<Page<ClientSummary>>(`/clients${qs({ q: clientSearch, size: 20, sort: "displayName,asc" })}`),
    enabled: open && !fixedClient,
  });
  const openClients = clients.data?.items.filter((c) => c.status !== "CLOSED") ?? [];

  const reset = () => {
    setClientId(fixedClient?.id ?? "");
    setDocumentType("");
    setFile(null);
    setErrors({});
    setClientSearch("");
  };

  const upload = useMutation({
    mutationFn: () => {
      const fd = new FormData();
      fd.append("file", file!);
      fd.append("clientId", clientId);
      fd.append("documentType", documentType);
      if (financialYear) fd.append("financialYear", financialYear);
      return api.post<DocumentItem>("/documents/upload", fd);
    },
    onSuccess: (d) => {
      queryClient.invalidateQueries({ queryKey: ["documents"] });
      queryClient.invalidateQueries({ queryKey: ["client", d.clientId] });
      queryClient.invalidateQueries({ queryKey: ["dashboard"] });
      if (d.status === "DUPLICATE_SUSPECTED") toast.warning("Uploaded, but an identical file already exists for this client");
      else toast.success("Document uploaded");
      reset();
      onOpenChange(false);
    },
    onError: (e) => {
      if (e instanceof ApiError && e.fieldErrors.length) {
        setErrors(Object.fromEntries(e.fieldErrors.map((f) => [f.field, f.message])));
      } else {
        toast.error(e instanceof Error ? e.message : "Upload failed");
      }
    },
  });

  const submit = () => {
    const next: Record<string, string> = {};
    if (!clientId) next.clientId = "Choose a client";
    if (!documentType) next.documentType = "Choose a document type";
    if (!file) next.file = "Choose a file";
    else if (file.size > MAX_BYTES) next.file = "The file is larger than 25 MB";
    if (financialYear && !/^\d{4}-\d{2}$/.test(financialYear)) next.financialYear = "Use the format 2025-26";
    setErrors(next);
    if (Object.keys(next).length === 0) upload.mutate();
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(o) => { if (!o) reset(); onOpenChange(o); }}
      title="Upload document"
      description="PDF, PNG, JPG, XLSX, XLS or CSV up to 25 MB. Files are checked by content, encrypted and stored securely."
      footer={
        <>
          <Button variant="secondary" onClick={() => onOpenChange(false)} disabled={upload.isPending}>Cancel</Button>
          <Button onClick={submit} loading={upload.isPending}><Upload className="h-4 w-4" /> Upload</Button>
        </>
      }
    >
      <div className="space-y-4">
        {fixedClient ? (
          <p className="text-sm">Client: <span className="font-medium">{fixedClient.name}</span></p>
        ) : (
          <Field label="Client" htmlFor="upload-client" required error={errors.clientId}>
            <Input placeholder="Type to search clients" value={clientSearch} aria-label="Search clients"
              onChange={(e) => setClientSearch(e.target.value)} />
            <Select id="upload-client" value={clientId} onChange={(e) => setClientId(e.target.value)} size={5}
              className="h-auto py-1" aria-invalid={!!errors.clientId}>
              {openClients.map((c) => <option key={c.id} value={c.id}>{c.displayName} · {c.clientCode}</option>)}
            </Select>
          </Field>
        )}
        <Field label="Document type" htmlFor="upload-type" required error={errors.documentType}>
          <Select id="upload-type" value={documentType} onChange={(e) => setDocumentType(e.target.value)}
            aria-invalid={!!errors.documentType}>
            <option value="">Select…</option>
            {docTypes.data?.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
          </Select>
        </Field>
        <Field label="Financial year" htmlFor="upload-fy" error={errors.financialYear} hint="Optional, e.g. 2025-26">
          <Input id="upload-fy" value={financialYear} onChange={(e) => setFinancialYear(e.target.value)} maxLength={7} />
        </Field>
        <Field label="File" htmlFor="upload-file" required error={errors.file}>
          <input ref={fileRef} id="upload-file" type="file" accept={ACCEPT}
            onChange={(e) => setFile(e.target.files?.[0] ?? null)}
            className="block w-full text-sm file:mr-3 file:cursor-pointer file:rounded-md file:border-0 file:bg-mint file:px-3 file:py-2 file:text-sm file:font-medium file:text-primary" />
          {file && <p className="text-xs text-muted">{file.name} · {formatBytes(file.size)}</p>}
        </Field>
      </div>
    </Dialog>
  );
}

type ReviewAction = { doc: DocumentItem; kind: "approve" | "reject" | "archive" } | null;

export function DocumentsTable({ query, showClient = true }: {
  query: ReturnType<typeof useQuery<Page<DocumentItem>>>;
  showClient?: boolean;
}) {
  const { can } = useAuth();
  const queryClient = useQueryClient();
  const docTypes = useDocumentTypes();
  const [action, setAction] = useState<ReviewAction>(null);

  const act = async (comment: string) => {
    if (!action) return;
    const { doc, kind } = action;
    await api.post(`/documents/${doc.id}/${kind}`, kind === "archive" ? undefined : { comment: comment || null });
    queryClient.invalidateQueries({ queryKey: ["documents"] });
    queryClient.invalidateQueries({ queryKey: ["client", doc.clientId] });
    queryClient.invalidateQueries({ queryKey: ["dashboard"] });
    toast.success(kind === "approve" ? "Document approved" : kind === "reject" ? "Document rejected" : "Document archived");
  };

  const download = async (d: DocumentItem) => {
    try {
      await downloadFile(`/documents/${d.id}/download`, d.originalFilename);
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Download failed");
    }
  };

  const cols = showClient ? 7 : 6;
  return (
    <>
      <Table>
        <thead>
          <tr>
            <Th>File</Th>
            {showClient && <Th>Client</Th>}
            <Th>Type</Th>
            <Th>Status</Th>
            <Th>Scan</Th>
            <Th>Uploaded</Th>
            <Th className="text-right">Actions</Th>
          </tr>
        </thead>
        <tbody>
          {query.isPending ? <LoadingRows cols={cols} /> : query.data?.items.map((d) => (
            <tr key={d.id}>
              <Td>
                <p className="max-w-64 truncate font-medium" title={d.originalFilename}>{d.originalFilename}</p>
                <p className="text-xs text-muted">{formatBytes(d.sizeBytes)}{d.financialYear ? ` · FY ${d.financialYear}` : ""}</p>
                {d.reviewComment && <p className="mt-0.5 text-xs text-muted italic">“{d.reviewComment}”</p>}
              </Td>
              {showClient && (
                <Td>
                  <Link to={`/clients/${d.clientId}`} className="text-primary hover:underline">{d.clientName}</Link>
                  <p className="font-mono text-xs text-muted">{d.clientCode}</p>
                </Td>
              )}
              <Td>{labelOf(docTypes.data, d.documentType)}</Td>
              <Td><StatusBadge status={d.status} /></Td>
              <Td><StatusBadge status={d.scanStatus} /></Td>
              <Td>
                <p>{formatDateTime(d.uploadedAt)}</p>
                <p className="text-xs text-muted">{d.uploadedBy?.fullName}</p>
              </Td>
              <Td>
                <div className="flex justify-end gap-1">
                  <Button variant="ghost" size="icon" aria-label={`Download ${d.originalFilename}`} title="Download"
                    onClick={() => download(d)}>
                    <Download className="h-4 w-4" />
                  </Button>
                  {d.canReview && (
                    <>
                      <Button variant="ghost" size="icon" aria-label="Approve" title="Approve"
                        onClick={() => setAction({ doc: d, kind: "approve" })}>
                        <Check className="h-4 w-4 text-emerald" />
                      </Button>
                      <Button variant="ghost" size="icon" aria-label="Reject" title="Reject"
                        onClick={() => setAction({ doc: d, kind: "reject" })}>
                        <X className="h-4 w-4 text-danger" />
                      </Button>
                    </>
                  )}
                  {can("DOCUMENT_REVIEW") && ["APPROVED", "REJECTED", "DUPLICATE_SUSPECTED"].includes(d.status) && (
                    <Button variant="ghost" size="icon" aria-label="Archive" title="Archive"
                      onClick={() => setAction({ doc: d, kind: "archive" })}>
                      <Archive className="h-4 w-4" />
                    </Button>
                  )}
                </div>
              </Td>
            </tr>
          ))}
        </tbody>
      </Table>
      {query.data && query.data.items.length === 0 && (
        <EmptyState title="No documents" description="Uploaded documents will appear here." />
      )}
      <ConfirmDialog
        open={!!action}
        onOpenChange={(o) => !o && setAction(null)}
        title={action?.kind === "approve" ? "Approve document" : action?.kind === "reject" ? "Reject document" : "Archive document"}
        description={
          action?.kind === "archive"
            ? `“${action?.doc.originalFilename}” will be archived. It stays stored and auditable.`
            : `“${action?.doc.originalFilename}” — your decision is recorded in the audit trail.`
        }
        confirmLabel={action?.kind === "approve" ? "Approve" : action?.kind === "reject" ? "Reject" : "Archive"}
        tone={action?.kind === "reject" ? "danger" : action?.kind === "approve" ? "success" : "primary"}
        commentLabel={action?.kind === "archive" ? undefined : action?.kind === "reject" ? "Reason" : "Comment (optional)"}
        commentRequired={action?.kind === "reject"}
        onConfirm={act}
      />
    </>
  );
}
