import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Download, Pencil, Plus } from "lucide-react";
import { useMemo, useState } from "react";
import { useForm } from "react-hook-form";
import { toast } from "sonner";
import { z } from "zod";
import { Badge, Button, Card, CardHeader, Dialog, Field, Input, Select, Table, Td, Th } from "@/components/ui";
import { useAccounts } from "@/hooks/useAccounting";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { downloadCsv } from "@/lib/csv";
import { applyServerErrors, nullIfBlank } from "@/lib/forms";
import { cn, humanize } from "@/lib/utils";
import { ACCOUNT_TYPES, CASH_FLOW_CATEGORIES, type Account } from "@/types/accounting";

/** Orders accounts as a tree (parents before children) and records each one's depth. */
function asTree(accounts: Account[]): { account: Account; depth: number }[] {
  const children = new Map<string | null, Account[]>();
  for (const a of accounts) {
    const key = a.parentId && accounts.some((p) => p.id === a.parentId) ? a.parentId : null;
    children.set(key, [...(children.get(key) ?? []), a]);
  }
  const out: { account: Account; depth: number }[] = [];
  const walk = (parent: string | null, depth: number) => {
    for (const a of (children.get(parent) ?? []).sort((x, y) => x.code.localeCompare(y.code))) {
      out.push({ account: a, depth });
      walk(a.id, depth + 1);
    }
  };
  walk(null, 0);
  return out;
}

export function ChartTab({ clientId, closed }: { clientId: string; closed: boolean }) {
  const { can } = useAuth();
  const accounts = useAccounts(clientId);
  const [editing, setEditing] = useState<Account | "new" | null>(null);
  const tree = useMemo(() => asTree(accounts.data ?? []), [accounts.data]);
  const editable = can("ACCOUNTING_WRITE") && !closed;

  return (
    <Card>
      <CardHeader title="Chart of accounts" description="Groups organise ledgers; only ledger accounts can be used on journal lines."
        actions={<>
          <Button size="sm" variant="secondary" onClick={() => downloadCsv("chart-of-accounts.csv", ["Code", "Name", "Type", "Group", "Active", "Subtype"],
            tree.map(({ account: a }) => [a.code, a.name, a.accountType, a.group ? "Group" : "Ledger", a.active ? "Yes" : "No", a.subtype]))}>
            <Download className="h-4 w-4" /> Export
          </Button>
          {editable && <Button size="sm" onClick={() => setEditing("new")}><Plus className="h-4 w-4" /> Add account</Button>}
        </>} />
      <Table>
        <thead><tr><Th>Code</Th><Th>Name</Th><Th>Type</Th><Th>Kind</Th><Th>Status</Th><Th /></tr></thead>
        <tbody>
          {tree.map(({ account: a, depth }) => (
            <tr key={a.id} className={cn(a.group && "bg-page/60")}>
              <Td className="font-mono text-xs">{a.code}</Td>
              <Td><span style={{ paddingLeft: depth * 18 }} className={cn(a.group && "font-semibold")}>{a.name}</span></Td>
              <Td>{humanize(a.accountType)}</Td>
              <Td>{a.group ? <Badge>Group</Badge> : <Badge tone="info">Ledger</Badge>}{a.subtype && <span className="ml-1 text-xs text-muted">{humanize(a.subtype)}</span>}</Td>
              <Td>{a.active ? <Badge tone="success">Active</Badge> : <Badge tone="danger">Inactive</Badge>}</Td>
              <Td className="text-right">{editable && <Button variant="ghost" size="icon" aria-label={`Edit ${a.code}`} onClick={() => setEditing(a)}><Pencil className="h-4 w-4" /></Button>}</Td>
            </tr>
          ))}
        </tbody>
      </Table>
      {editing && <AccountDialog clientId={clientId} accounts={accounts.data ?? []} existing={editing === "new" ? null : editing} onClose={() => setEditing(null)} />}
    </Card>
  );
}

/** Fixed fields stay in the form values (a disabled field would be dropped on submit). */
const LOCK = { tabIndex: -1, "aria-readonly": true, className: "pointer-events-none bg-page text-muted" } as const;

const schema = z.object({
  code: z.string().trim().regex(/^[A-Za-z0-9.-]{1,20}$/, "Up to 20 letters, digits, . or -"),
  name: z.string().trim().min(1, "Required").max(150),
  accountType: z.enum(["ASSET", "LIABILITY", "EQUITY", "INCOME", "EXPENSE"]),
  parentId: z.string(),
  group: z.boolean(),
  active: z.boolean(),
  subtype: z.string().max(30),
  description: z.string().max(500),
  cashFlowCategory: z.string(),
});
type Values = z.infer<typeof schema>;

function AccountDialog({ clientId, accounts, existing, onClose }: { clientId: string; accounts: Account[]; existing: Account | null; onClose: () => void }) {
  const queryClient = useQueryClient();
  const { register, handleSubmit, formState, setError, watch } = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      code: existing?.code ?? "", name: existing?.name ?? "", accountType: existing?.accountType ?? "EXPENSE",
      parentId: existing?.parentId ?? "", group: existing?.group ?? false, active: existing?.active ?? true,
      subtype: existing?.subtype ?? "", description: existing?.description ?? "", cashFlowCategory: existing?.cashFlowCategory ?? "",
    },
  });
  const e = formState.errors;
  const type = watch("accountType");
  const isGroup = watch("group");
  const balanceSheet = type === "ASSET" || type === "LIABILITY" || type === "EQUITY";
  const parents = accounts.filter((a) => a.group && a.accountType === type && a.id !== existing?.id);
  const save = useMutation({
    mutationFn: (v: Values) => {
      const body = { ...v, clientId, parentId: nullIfBlank(v.parentId), subtype: nullIfBlank(v.subtype),
        description: nullIfBlank(v.description), cashFlowCategory: balanceSheet && !v.group ? nullIfBlank(v.cashFlowCategory) : null,
        version: existing?.version };
      return existing ? api.put(`/accounting/accounts/${existing.id}`, body) : api.post("/accounting/accounts", body);
    },
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ["accounting", clientId, "accounts"] }); toast.success("Account saved"); onClose(); },
    onError: (err) => applyServerErrors(err, setError, Object.keys(schema.shape)),
  });
  const locked = !!existing?.used;
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} wide title={existing ? `Edit ${existing.code} ${existing.name}` : "Add account"}
      description={locked ? "This account has postings, so its type and group setting are fixed." : undefined}
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button loading={save.isPending} onClick={handleSubmit((v) => save.mutate(v))}>Save</Button></>}>
      <form className="grid grid-cols-1 gap-4 sm:grid-cols-2" noValidate onSubmit={(ev) => ev.preventDefault()}>
        <Field label="Code" htmlFor="ac-code" required error={e.code?.message}><Input id="ac-code" className="font-mono uppercase" {...register("code")} aria-invalid={!!e.code} /></Field>
        <Field label="Name" htmlFor="ac-name" required error={e.name?.message}><Input id="ac-name" {...register("name")} aria-invalid={!!e.name} /></Field>
        <Field label="Type" htmlFor="ac-type" required>
          <Select id="ac-type" {...(locked ? LOCK : {})} {...register("accountType")}>{ACCOUNT_TYPES.map((t) => <option key={t} value={t}>{humanize(t)}</option>)}</Select>
        </Field>
        <Field label="Parent group" htmlFor="ac-parent" error={e.parentId?.message}>
          <Select id="ac-parent" {...register("parentId")} aria-invalid={!!e.parentId}>
            <option value="">(top level)</option>
            {parents.map((p) => <option key={p.id} value={p.id}>{p.code} {p.name}</option>)}
          </Select>
        </Field>
        <Field label="Subtype" htmlFor="ac-subtype" hint="Optional tag, e.g. BANK, CASH, GST_INPUT"><Input id="ac-subtype" className="uppercase" {...register("subtype")} /></Field>
        <Field label="Description" htmlFor="ac-desc"><Input id="ac-desc" {...register("description")} /></Field>
        {balanceSheet && !isGroup && (
          <Field label="Cash flow category" htmlFor="ac-cf" hint="Where movements on this ledger appear in the cash flow statement" className="sm:col-span-2">
            <Select id="ac-cf" {...register("cashFlowCategory")}>
              <option value="">Automatic (from type and subtype)</option>
              {CASH_FLOW_CATEGORIES.map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}
            </Select>
          </Field>
        )}
        <label className="flex items-center gap-2 text-sm"><input type="checkbox" {...register("group")} {...(locked ? { tabIndex: -1, "aria-readonly": true, onClick: (ev: React.MouseEvent) => ev.preventDefault() } : {})} className={locked ? "pointer-events-none h-4 w-4 opacity-60 accent-emerald" : "h-4 w-4 accent-emerald"} /> Group (holds other accounts, cannot be posted to)</label>
        <label className="flex items-center gap-2 text-sm"><input type="checkbox" {...register("active")} className="h-4 w-4 accent-emerald" /> Active</label>
      </form>
    </Dialog>
  );
}
