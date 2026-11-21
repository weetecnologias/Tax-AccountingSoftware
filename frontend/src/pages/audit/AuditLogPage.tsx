import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useSearchParams } from "react-router-dom";
import { AuditTable } from "@/components/audit";
import { ErrorState, PageHeader, Pagination } from "@/components/common";
import { Button, Card, Field, Input, Select } from "@/components/ui";
import { api, qs } from "@/lib/api";
import { humanize } from "@/lib/utils";
import type { AuditEntry, Page } from "@/types/api";

const ACTIONS = [
  "LOGIN", "LOGOUT", "ACCOUNT_LOCKED", "PASSWORD_CHANGED", "REFRESH_TOKEN_REUSE",
  "USER_CREATED", "USER_UPDATED", "USER_DEACTIVATED", "USER_PASSWORD_RESET", "ROLE_CREATED", "ROLE_UPDATED",
  "CLIENT_CREATED", "CLIENT_UPDATED", "CLIENT_STATUS_CHANGED", "CLIENT_ACTIVATED", "CLIENT_CLOSED", "CLIENTS_EXPORTED",
  "GST_REGISTRATION_ADDED", "GST_REGISTRATION_UPDATED", "BANK_ACCOUNT_ADDED", "BANK_ACCOUNT_UPDATED",
  "CONTACT_ADDED", "CONTACT_UPDATED", "CONTACT_REMOVED", "ADDRESS_ADDED", "ADDRESS_UPDATED", "ADDRESS_REMOVED",
  "DOCUMENT_UPLOADED", "DOCUMENT_DOWNLOADED", "DOCUMENT_APPROVED", "DOCUMENT_REJECTED", "DOCUMENT_ARCHIVED",
  "DOCUMENT_INFECTED_REJECTED",
];
const ENTITIES = ["USER", "ROLE", "CLIENT", "CLIENT_CONTACT", "CLIENT_ADDRESS", "GST_REGISTRATION", "BANK_ACCOUNT", "DOCUMENT"];

/** Converts a yyyy-mm-dd date input (local) into an ISO instant at local midnight. */
function toInstant(date: string, endOfDay = false): string | undefined {
  if (!date) return undefined;
  const d = new Date(`${date}T00:00:00`);
  if (endOfDay) d.setDate(d.getDate() + 1);
  return d.toISOString();
}

export function AuditLogPage() {
  const [params, setParams] = useSearchParams();
  const get = (k: string) => params.get(k) ?? "";
  const page = Number(get("page") || 0);

  function update(next: Record<string, string>) {
    const p = new URLSearchParams(params);
    for (const [k, v] of Object.entries(next)) v ? p.set(k, v) : p.delete(k);
    setParams(p, { replace: true });
  }

  const filters = {
    action: get("action"), entityType: get("entityType"), clientId: get("clientId"),
    from: toInstant(get("from")), to: toInstant(get("to"), true),
  };
  const query = useQuery({
    queryKey: ["audit", filters, page],
    queryFn: () => api.get<Page<AuditEntry>>(`/audit-logs${qs({ ...filters, page, size: 25 })}`),
    placeholderData: keepPreviousData,
  });

  return (
    <div>
      <PageHeader title="Audit log" description="Append-only record of every consequential action. Entries cannot be edited or deleted." />
      <Card>
        <div className="grid grid-cols-1 gap-3 border-b border-line p-4 sm:grid-cols-2 lg:grid-cols-5">
          <Field label="Action" htmlFor="f-action">
            <Select id="f-action" value={get("action")} onChange={(e) => update({ action: e.target.value, page: "" })}>
              <option value="">All actions</option>
              {ACTIONS.map((a) => <option key={a} value={a}>{humanize(a)}</option>)}
            </Select>
          </Field>
          <Field label="Record type" htmlFor="f-entity">
            <Select id="f-entity" value={get("entityType")} onChange={(e) => update({ entityType: e.target.value, page: "" })}>
              <option value="">All records</option>
              {ENTITIES.map((a) => <option key={a} value={a}>{humanize(a)}</option>)}
            </Select>
          </Field>
          <Field label="From" htmlFor="f-from">
            <Input id="f-from" type="date" value={get("from")} onChange={(e) => update({ from: e.target.value, page: "" })} />
          </Field>
          <Field label="To" htmlFor="f-to">
            <Input id="f-to" type="date" value={get("to")} onChange={(e) => update({ to: e.target.value, page: "" })} />
          </Field>
          <div className="flex items-end">
            <Button variant="secondary" onClick={() => setParams({}, { replace: true })} className="w-full">Clear filters</Button>
          </div>
        </div>
        {get("clientId") && (
          <p className="border-b border-line bg-mint/50 px-4 py-2 text-xs">Filtered to one client.</p>
        )}
        {query.isError ? <ErrorState error={query.error} onRetry={() => query.refetch()} /> : (
          <>
            <AuditTable query={query} />
            {query.data && <Pagination page={query.data.page} totalPages={query.data.totalPages} totalItems={query.data.totalItems}
              onPage={(p) => update({ page: String(p) })} />}
          </>
        )}
      </Card>
    </div>
  );
}
