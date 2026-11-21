import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Pencil, Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { toast } from "sonner";
import { z } from "zod";
import { ConfirmDialog, EmptyState, ErrorState, LoadingRows } from "@/components/common";
import { Badge, Button, Card, CardHeader, Dialog, Field, Input, Select, Table, Td, Th } from "@/components/ui";
import { useStates } from "@/hooks/useReference";
import { api } from "@/lib/api";
import { applyServerErrors, nullIfBlank } from "@/lib/forms";
import { humanize } from "@/lib/utils";
import type { Address, Contact } from "@/types/api";
import { PINCODE_RE } from "@/validation/ids";

const contactSchema = z.object({
  name: z.string().trim().min(1, "Required").max(200),
  designation: z.string().max(120),
  email: z.string().trim().refine((v) => v === "" || z.email().safeParse(v).success, "Enter a valid email"),
  phone: z.string().trim().refine((v) => v === "" || /^[+0-9 -]{7,20}$/.test(v), "Enter a valid phone number"),
  primary: z.boolean(),
});
type ContactValues = z.infer<typeof contactSchema>;

const ADDRESS_TYPES = ["REGISTERED", "BUSINESS", "BRANCH", "COMMUNICATION", "RESIDENTIAL"];
const addressSchema = z.object({
  addressType: z.string().min(1),
  line1: z.string().trim().min(1, "Required").max(200),
  line2: z.string().max(200),
  city: z.string().trim().min(1, "Required").max(100),
  stateCode: z.string().min(1, "Choose a state"),
  pincode: z.string().trim().regex(PINCODE_RE, "6 digits, not starting with 0"),
});
type AddressValues = z.infer<typeof addressSchema>;

export function ContactsTab({ clientId, editable }: { clientId: string; editable: boolean }) {
  return (
    <div className="space-y-5">
      <ContactsCard clientId={clientId} editable={editable} />
      <AddressesCard clientId={clientId} editable={editable} />
    </div>
  );
}

function ContactsCard({ clientId, editable }: { clientId: string; editable: boolean }) {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<Contact | "new" | null>(null);
  const [removing, setRemoving] = useState<Contact | null>(null);
  const q = useQuery({ queryKey: ["client", clientId, "contacts"], queryFn: () => api.get<Contact[]>(`/clients/${clientId}/contacts`) });
  const refresh = () => queryClient.invalidateQueries({ queryKey: ["client", clientId] });

  return (
    <Card>
      <CardHeader title="Contact persons"
        actions={editable && <Button size="sm" onClick={() => setEditing("new")}><Plus className="h-4 w-4" /> Add contact</Button>} />
      {q.isError ? <ErrorState error={q.error} onRetry={() => q.refetch()} /> : (
        <>
          <Table>
            <thead><tr><Th>Name</Th><Th>Designation</Th><Th>Email</Th><Th>Phone</Th><Th /></tr></thead>
            <tbody>
              {q.isPending ? <LoadingRows cols={5} rows={2} /> : q.data?.map((c) => (
                <tr key={c.id}>
                  <Td className="font-medium">{c.name} {c.primary && <Badge tone="success" className="ml-1">Primary</Badge>}</Td>
                  <Td>{c.designation ?? "—"}</Td>
                  <Td>{c.email ?? "—"}</Td>
                  <Td>{c.phone ?? "—"}</Td>
                  <Td className="text-right whitespace-nowrap">
                    {editable && <>
                      <Button variant="ghost" size="icon" aria-label={`Edit ${c.name}`} onClick={() => setEditing(c)}><Pencil className="h-4 w-4" /></Button>
                      <Button variant="ghost" size="icon" aria-label={`Remove ${c.name}`} onClick={() => setRemoving(c)}><Trash2 className="h-4 w-4 text-danger" /></Button>
                    </>}
                  </Td>
                </tr>
              ))}
            </tbody>
          </Table>
          {q.data?.length === 0 && <EmptyState title="No contacts" />}
        </>
      )}
      {editing && <ContactDialog clientId={clientId} existing={editing === "new" ? null : editing}
        onClose={() => setEditing(null)} onSaved={() => { refresh(); setEditing(null); }} />}
      <ConfirmDialog open={!!removing} onOpenChange={(o) => !o && setRemoving(null)} title="Remove contact"
        description={`Remove ${removing?.name} from this client? The removal is audited.`} confirmLabel="Remove" tone="danger"
        onConfirm={async () => { await api.del(`/clients/${clientId}/contacts/${removing!.id}`); refresh(); toast.success("Contact removed"); }} />
    </Card>
  );
}

function ContactDialog({ clientId, existing, onClose, onSaved }: { clientId: string; existing: Contact | null; onClose: () => void; onSaved: () => void }) {
  const { register, handleSubmit, formState, setError } = useForm<ContactValues>({
    resolver: zodResolver(contactSchema),
    defaultValues: { name: existing?.name ?? "", designation: existing?.designation ?? "", email: existing?.email ?? "",
      phone: existing?.phone ?? "", primary: existing?.primary ?? false },
  });
  const e = formState.errors;
  const save = useMutation({
    mutationFn: (v: ContactValues) => {
      const body = { ...v, designation: nullIfBlank(v.designation), email: nullIfBlank(v.email), phone: nullIfBlank(v.phone) };
      return existing ? api.put(`/clients/${clientId}/contacts/${existing.id}`, body) : api.post(`/clients/${clientId}/contacts`, body);
    },
    onSuccess: () => { toast.success("Contact saved"); onSaved(); },
    onError: (err) => applyServerErrors(err, setError, Object.keys(contactSchema.shape)),
  });
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={existing ? "Edit contact" : "Add contact"}
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button loading={save.isPending} onClick={handleSubmit((v) => save.mutate(v))}>Save</Button></>}>
      <form className="space-y-4" noValidate onSubmit={(ev) => ev.preventDefault()}>
        <Field label="Name" htmlFor="c-name" required error={e.name?.message}><Input id="c-name" {...register("name")} aria-invalid={!!e.name} /></Field>
        <Field label="Designation" htmlFor="c-designation"><Input id="c-designation" {...register("designation")} /></Field>
        <Field label="Email" htmlFor="c-email" error={e.email?.message}><Input id="c-email" type="email" {...register("email")} aria-invalid={!!e.email} /></Field>
        <Field label="Phone" htmlFor="c-phone" error={e.phone?.message}><Input id="c-phone" type="tel" {...register("phone")} aria-invalid={!!e.phone} /></Field>
        <label className="flex items-center gap-2 text-sm"><input type="checkbox" {...register("primary")} className="h-4 w-4 accent-emerald" /> Primary contact</label>
      </form>
    </Dialog>
  );
}

function AddressesCard({ clientId, editable }: { clientId: string; editable: boolean }) {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<Address | "new" | null>(null);
  const [removing, setRemoving] = useState<Address | null>(null);
  const q = useQuery({ queryKey: ["client", clientId, "addresses"], queryFn: () => api.get<Address[]>(`/clients/${clientId}/addresses`) });
  const refresh = () => queryClient.invalidateQueries({ queryKey: ["client", clientId] });

  return (
    <Card>
      <CardHeader title="Addresses"
        actions={editable && <Button size="sm" onClick={() => setEditing("new")}><Plus className="h-4 w-4" /> Add address</Button>} />
      {q.isError ? <ErrorState error={q.error} onRetry={() => q.refetch()} /> : (
        <>
          <ul className="divide-y divide-line">
            {q.isPending && <li className="px-5 py-4 text-sm text-muted">Loading…</li>}
            {q.data?.map((a) => (
              <li key={a.id} className="flex items-start justify-between gap-3 px-5 py-3 text-sm">
                <div>
                  <Badge tone="info">{humanize(a.addressType)}</Badge>
                  <p className="mt-1">{a.line1}{a.line2 ? `, ${a.line2}` : ""}</p>
                  <p className="text-muted">{a.city}, {a.stateName} – {a.pincode}</p>
                </div>
                {editable && <div className="flex">
                  <Button variant="ghost" size="icon" aria-label="Edit address" onClick={() => setEditing(a)}><Pencil className="h-4 w-4" /></Button>
                  <Button variant="ghost" size="icon" aria-label="Remove address" onClick={() => setRemoving(a)}><Trash2 className="h-4 w-4 text-danger" /></Button>
                </div>}
              </li>
            ))}
          </ul>
          {q.data?.length === 0 && <EmptyState title="No addresses" />}
        </>
      )}
      {editing && <AddressDialog clientId={clientId} existing={editing === "new" ? null : editing}
        onClose={() => setEditing(null)} onSaved={() => { refresh(); setEditing(null); }} />}
      <ConfirmDialog open={!!removing} onOpenChange={(o) => !o && setRemoving(null)} title="Remove address"
        description="Remove this address? The removal is audited." confirmLabel="Remove" tone="danger"
        onConfirm={async () => { await api.del(`/clients/${clientId}/addresses/${removing!.id}`); refresh(); toast.success("Address removed"); }} />
    </Card>
  );
}

function AddressDialog({ clientId, existing, onClose, onSaved }: { clientId: string; existing: Address | null; onClose: () => void; onSaved: () => void }) {
  const states = useStates();
  const { register, handleSubmit, formState, setError } = useForm<AddressValues>({
    resolver: zodResolver(addressSchema),
    defaultValues: { addressType: existing?.addressType ?? "REGISTERED", line1: existing?.line1 ?? "", line2: existing?.line2 ?? "",
      city: existing?.city ?? "", stateCode: existing?.stateCode ?? "", pincode: existing?.pincode ?? "" },
  });
  const e = formState.errors;
  const save = useMutation({
    mutationFn: (v: AddressValues) => {
      const body = { ...v, line2: nullIfBlank(v.line2) };
      return existing ? api.put(`/clients/${clientId}/addresses/${existing.id}`, body) : api.post(`/clients/${clientId}/addresses`, body);
    },
    onSuccess: () => { toast.success("Address saved"); onSaved(); },
    onError: (err) => applyServerErrors(err, setError, Object.keys(addressSchema.shape)),
  });
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} wide title={existing ? "Edit address" : "Add address"}
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button loading={save.isPending} onClick={handleSubmit((v) => save.mutate(v))}>Save</Button></>}>
      <form className="grid grid-cols-1 gap-4 sm:grid-cols-2" noValidate onSubmit={(ev) => ev.preventDefault()}>
        <Field label="Type" htmlFor="a-type"><Select id="a-type" {...register("addressType")}>{ADDRESS_TYPES.map((t) => <option key={t} value={t}>{humanize(t)}</option>)}</Select></Field>
        <div />
        <Field label="Address line 1" htmlFor="a-line1" required error={e.line1?.message} className="sm:col-span-2"><Input id="a-line1" {...register("line1")} aria-invalid={!!e.line1} /></Field>
        <Field label="Address line 2" htmlFor="a-line2" className="sm:col-span-2"><Input id="a-line2" {...register("line2")} /></Field>
        <Field label="City" htmlFor="a-city" required error={e.city?.message}><Input id="a-city" {...register("city")} aria-invalid={!!e.city} /></Field>
        <Field label="State / UT" htmlFor="a-state" required error={e.stateCode?.message}>
          <Select id="a-state" {...register("stateCode")} aria-invalid={!!e.stateCode}>
            <option value="">Select…</option>
            {states.data?.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}
          </Select>
        </Field>
        <Field label="PIN code" htmlFor="a-pin" required error={e.pincode?.message}><Input id="a-pin" maxLength={6} inputMode="numeric" {...register("pincode")} aria-invalid={!!e.pincode} /></Field>
      </form>
    </Dialog>
  );
}
