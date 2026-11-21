import type { UseQueryResult } from "@tanstack/react-query";
import { EmptyState, LoadingRows } from "@/components/common";
import { Badge, Table, Td, Th } from "@/components/ui";
import { formatDateTime, humanize } from "@/lib/utils";
import type { AuditEntry, Page } from "@/types/api";

function DetailsCell({ details }: { details: Record<string, unknown> | null }) {
  if (!details) return <span className="text-muted">—</span>;
  return (
    <dl className="space-y-0.5 text-xs">
      {Object.entries(details).map(([k, v]) => (
        <div key={k} className="flex gap-1">
          <dt className="text-muted">{k}:</dt>
          <dd className="max-w-72 truncate" title={typeof v === "string" ? v : JSON.stringify(v)}>
            {typeof v === "string" ? v : JSON.stringify(v)}
          </dd>
        </div>
      ))}
    </dl>
  );
}

export function AuditTable({ query }: { query: UseQueryResult<Page<AuditEntry>> }) {
  return (
    <>
      <Table>
        <thead>
          <tr>
            <Th>When</Th>
            <Th>Actor</Th>
            <Th>Action</Th>
            <Th>Record</Th>
            <Th>Details</Th>
          </tr>
        </thead>
        <tbody>
          {query.isPending ? <LoadingRows cols={5} /> : query.data?.items.map((a) => (
            <tr key={a.id}>
              <Td className="whitespace-nowrap">{formatDateTime(a.occurredAt)}</Td>
              <Td>
                <p>{a.actorEmail ?? "system"}</p>
                {a.ipAddress && <p className="text-xs text-muted">{a.ipAddress}</p>}
              </Td>
              <Td><Badge tone="info">{humanize(a.action)}</Badge></Td>
              <Td className="text-xs">
                <p>{humanize(a.entityType)}</p>
                {a.entityId && <p className="font-mono text-muted">{a.entityId.slice(0, 8)}</p>}
              </Td>
              <Td><DetailsCell details={a.details} /></Td>
            </tr>
          ))}
        </tbody>
      </Table>
      {query.data && query.data.items.length === 0 && <EmptyState title="No audit entries" />}
    </>
  );
}
