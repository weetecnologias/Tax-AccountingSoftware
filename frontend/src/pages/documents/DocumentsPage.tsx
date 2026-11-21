import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Search, Upload } from "lucide-react";
import { useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { ErrorState, PageHeader, Pagination } from "@/components/common";
import { DocumentsTable, UploadDialog } from "@/components/documents";
import { Button, Card, Input, Select } from "@/components/ui";
import { useDocumentTypes } from "@/hooks/useReference";
import { api, qs } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { humanize } from "@/lib/utils";
import type { DocumentItem, Page } from "@/types/api";

const STATUSES = ["UPLOADED", "DUPLICATE_SUSPECTED", "REVIEW_REQUIRED", "APPROVED", "REJECTED", "ARCHIVED"];

export function DocumentsPage() {
  const { can } = useAuth();
  const [params, setParams] = useSearchParams();
  const [search, setSearch] = useState(params.get("q") ?? "");
  const [uploadOpen, setUploadOpen] = useState(false);
  const docTypes = useDocumentTypes();
  const status = params.get("status") ?? "";
  const documentType = params.get("documentType") ?? "";
  const page = Number(params.get("page") ?? 0);

  function update(next: Record<string, string>) {
    const p = new URLSearchParams(params);
    for (const [k, v] of Object.entries(next)) v ? p.set(k, v) : p.delete(k);
    setParams(p, { replace: true });
  }

  useEffect(() => {
    const t = setTimeout(() => {
      if ((params.get("q") ?? "") !== search) update({ q: search, page: "" });
    }, 300);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [search]);

  const filters = { q: params.get("q") ?? "", status, documentType };
  const query = useQuery({
    queryKey: ["documents", "all", filters, page],
    queryFn: () => api.get<Page<DocumentItem>>(`/documents${qs({ ...filters, page, size: 20 })}`),
    placeholderData: keepPreviousData,
  });

  return (
    <div>
      <PageHeader title="Documents" description="All client documents. Reviews follow maker-checker: the uploader cannot approve their own upload."
        actions={can("DOCUMENT_UPLOAD") && <Button onClick={() => setUploadOpen(true)}><Upload className="h-4 w-4" /> Upload</Button>} />
      <Card>
        <div className="flex flex-wrap gap-3 border-b border-line p-4">
          <div className="relative min-w-60 flex-1">
            <Search className="pointer-events-none absolute top-2.5 left-3 h-4 w-4 text-muted" aria-hidden />
            <Input aria-label="Search by file name" placeholder="Search by file name" value={search}
              onChange={(e) => setSearch(e.target.value)} className="pl-9" />
          </div>
          <Select aria-label="Filter by status" value={status} onChange={(e) => update({ status: e.target.value, page: "" })} className="w-52">
            <option value="">All statuses</option>
            {STATUSES.map((s) => <option key={s} value={s}>{humanize(s)}</option>)}
          </Select>
          <Select aria-label="Filter by type" value={documentType} onChange={(e) => update({ documentType: e.target.value, page: "" })} className="w-52">
            <option value="">All types</option>
            {docTypes.data?.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
          </Select>
        </div>
        {query.isError ? <ErrorState error={query.error} onRetry={() => query.refetch()} /> : (
          <>
            <DocumentsTable query={query} />
            {query.data && <Pagination page={query.data.page} totalPages={query.data.totalPages} totalItems={query.data.totalItems}
              onPage={(p) => update({ page: String(p) })} />}
          </>
        )}
      </Card>
      <p className="mt-3 text-xs text-muted">
        “Not scanned” means malware scanning is switched off in this environment. It must be enabled before production use.
      </p>
      <UploadDialog open={uploadOpen} onOpenChange={setUploadOpen} />
    </div>
  );
}
