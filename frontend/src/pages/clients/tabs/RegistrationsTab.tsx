import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Pencil, Plus } from "lucide-react";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { toast } from "sonner";
import { z } from "zod";
import { EmptyState, ErrorState, LoadingRows, StatusBadge } from "@/components/common";
import { Button, Card, CardHeader, Dialog, Field, Input, Select, Table, Td, Th } from "@/components/ui";
import { api } from "@/lib/api";
import { applyServerErrors, nullIfBlank } from "@/lib/forms";
import { formatDate, humanize } from "@/lib/utils";
import type { GstRegistration } from "@/types/api";
import { gstinSchema } from "@/validation/ids";

const REG_TYPES: Record<string, string> = {
  REGULAR: "Regular", COMPOSITION: "Composition", SEZ_UNIT: "SEZ unit", SEZ_DEVELOPER: "SEZ developer",
  CASUAL: "Casual taxable person", NON_RESIDENT: "Non-resident taxable person", ISD: "Input Service Distributor",
  TDS: "TDS deductor", TCS: "TCS collector",
};

const schema = z
  .object({
    gstin: gstinSchema,
    tradeName: z.string().max(200),
    registrationType: z.string().min(1, "Required"),
    effectiveDate: z.string().min(1, "Required"),
    filingFrequency: z.enum(["MONTHLY", "QUARTERLY"]),
    principalPlace: z.string().max(500),
    status: z.enum(["ACTIVE", "SUSPENDED", "CANCELLED"]),
    cancelledOn: z.string(),
  })
  .refine((v) => v.status !== "CANCELLED" || v.cancelledOn !== "", { path: ["cancelledOn"], message: "Enter the cancellation date" });
type Values = z.input<typeof schema>;
type Parsed = z.output<typeof schema>;

export function RegistrationsTab({ clientId, editable }: { clientId: string; editable: boolean }) {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<GstRegistration | "new" | null>(null);
  const q = useQuery({
    queryKey: ["client", clientId, "gst"],
    queryFn: () => api.get<GstRegistration[]>(`/clients/${clientId}/gst-registrations`),
  });

  return (
    <Card>
      <CardHeader title="GST registrations" description="A legal entity can hold one GSTIN per state or branch."
        actions={editable && <Button size="sm" onClick={() => setEditing("new")}><Plus className="h-4 w-4" /> Add GSTIN</Button>} />
      {q.isError ? <ErrorState error={q.error} onRetry={() => q.refetch()} /> : (
        <>
          <Table>
            <thead><tr><Th>GSTIN</Th><Th>State</Th><Th>Type</Th><Th>Frequency</Th><Th>Effective</Th><Th>Status</Th><Th /></tr></thead>
            <tbody>
              {q.isPending ? <LoadingRows cols={7} rows={2} /> : q.data?.map((g) => (
                <tr key={g.id}>
                  <Td><p className="font-mono">{g.gstin}</p>{g.tradeName && <p className="text-xs text-muted">{g.tradeName}</p>}</Td>
                  <Td>{g.stateName} <span className="text-muted">({g.stateCode})</span></Td>
                  <Td>{REG_TYPES[g.registrationType] ?? humanize(g.registrationType)}</Td>
                  <Td>{humanize(g.filingFrequency)}</Td>
                  <Td>{formatDate(g.effectiveDate)}</Td>
                  <Td><StatusBadge status={g.status} />{g.cancelledOn && <p className="text-xs text-muted">{formatDate(g.cancelledOn)}</p>}</Td>
                  <Td className="text-right">
                    {editable && <Button variant="ghost" size="icon" aria-label={`Edit ${g.gstin}`} onClick={() => setEditing(g)}><Pencil className="h-4 w-4" /></Button>}
                  </Td>
                </tr>
              ))}
            </tbody>
          </Table>
          {q.data?.length === 0 && <EmptyState title="No GST registrations" description="Add each GSTIN held by this client." />}
        </>
      )}
      {editing && (
        <RegistrationDialog clientId={clientId} existing={editing === "new" ? null : editing} onClose={() => setEditing(null)}
          onSaved={() => {
            queryClient.invalidateQueries({ queryKey: ["client", clientId] });
            setEditing(null);
          }} />
      )}
    </Card>
  );
}

function RegistrationDialog({ clientId, existing, onClose, onSaved }: {
  clientId: string; existing: GstRegistration | null; onClose: () => void; onSaved: () => void;
}) {
  const { register, handleSubmit, formState, setError, watch } = useForm<Values, unknown, Parsed>({
    resolver: zodResolver(schema),
    defaultValues: {
      gstin: existing?.gstin ?? "", tradeName: existing?.tradeName ?? "", registrationType: existing?.registrationType ?? "REGULAR",
      effectiveDate: existing?.effectiveDate ?? "", filingFrequency: (existing?.filingFrequency as "MONTHLY") ?? "MONTHLY",
      principalPlace: existing?.principalPlace ?? "", status: (existing?.status as "ACTIVE") ?? "ACTIVE",
      cancelledOn: existing?.cancelledOn ?? "",
    },
  });
  const e = formState.errors;
  const save = useMutation({
    mutationFn: (v: Parsed) => {
      const body = { ...v, tradeName: nullIfBlank(v.tradeName), principalPlace: nullIfBlank(v.principalPlace),
        cancelledOn: v.status === "CANCELLED" ? v.cancelledOn : null };
      return existing
        ? api.put(`/clients/${clientId}/gst-registrations/${existing.id}`, body)
        : api.post(`/clients/${clientId}/gst-registrations`, body);
    },
    onSuccess: () => { toast.success(existing ? "Registration updated" : "GSTIN added"); onSaved(); },
    onError: (err) => applyServerErrors(err, setError, Object.keys(schema.shape)),
  });

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} wide title={existing ? `Edit ${existing.gstin}` : "Add GST registration"}
      description="The PAN inside the GSTIN must match the client's PAN. The state is taken from the first two digits."
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button>
        <Button loading={save.isPending} onClick={handleSubmit((v) => save.mutate(v))}>Save</Button></>}>
      <form className="grid grid-cols-1 gap-4 sm:grid-cols-2" noValidate onSubmit={(ev) => ev.preventDefault()}>
        <Field label="GSTIN" htmlFor="gstin" required error={e.gstin?.message}>
          <Input id="gstin" maxLength={15} className="font-mono uppercase" disabled={!!existing} {...register("gstin")} aria-invalid={!!e.gstin} />
        </Field>
        <Field label="Trade name" htmlFor="tradeName"><Input id="tradeName" {...register("tradeName")} /></Field>
        <Field label="Registration type" htmlFor="registrationType" required>
          <Select id="registrationType" {...register("registrationType")}>
            {Object.entries(REG_TYPES).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
          </Select>
        </Field>
        <Field label="Return filing frequency" htmlFor="filingFrequency" required>
          <Select id="filingFrequency" {...register("filingFrequency")}>
            <option value="MONTHLY">Monthly</option><option value="QUARTERLY">Quarterly (QRMP)</option>
          </Select>
        </Field>
        <Field label="Effective date" htmlFor="effectiveDate" required error={e.effectiveDate?.message}>
          <Input id="effectiveDate" type="date" {...register("effectiveDate")} aria-invalid={!!e.effectiveDate} />
        </Field>
        <Field label="Status" htmlFor="status">
          <Select id="status" {...register("status")}>
            <option value="ACTIVE">Active</option><option value="SUSPENDED">Suspended</option><option value="CANCELLED">Cancelled</option>
          </Select>
        </Field>
        {watch("status") === "CANCELLED" && (
          <Field label="Cancelled on" htmlFor="cancelledOn" required error={e.cancelledOn?.message}>
            <Input id="cancelledOn" type="date" {...register("cancelledOn")} aria-invalid={!!e.cancelledOn} />
          </Field>
        )}
        <Field label="Principal place of business" htmlFor="principalPlace" className="sm:col-span-2">
          <Input id="principalPlace" {...register("principalPlace")} />
        </Field>
      </form>
    </Dialog>
  );
}
