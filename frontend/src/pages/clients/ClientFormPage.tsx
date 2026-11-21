import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect } from "react";
import { useForm } from "react-hook-form";
import { useNavigate, useParams } from "react-router-dom";
import { toast } from "sonner";
import { z } from "zod";
import { ErrorState, PageHeader, Skeleton } from "@/components/common";
import { Button, Card, CardHeader, Field, Input, Select, Textarea } from "@/components/ui";
import { useAssignableUsers, useEntityTypes } from "@/hooks/useReference";
import { api } from "@/lib/api";
import { applyServerErrors, nullIfBlank } from "@/lib/forms";
import { MONTHS } from "@/lib/utils";
import type { ClientDetail } from "@/types/api";
import { optionalTanSchema, panSchema } from "@/validation/ids";

const schema = z.object({
  displayName: z.string().trim().min(1, "Required").max(200),
  legalName: z.string().trim().min(1, "Required").max(200),
  entityType: z.string().min(1, "Choose an entity type"),
  pan: panSchema,
  tan: optionalTanSchema,
  dateOfIncorporation: z.string(),
  email: z.string().trim().refine((v) => v === "" || z.email().safeParse(v).success, "Enter a valid email"),
  phone: z.string().trim().refine((v) => v === "" || /^[+0-9 -]{7,20}$/.test(v), "Enter a valid phone number"),
  fyStartMonth: z.coerce.number().int().min(1).max(12),
  accountingMethod: z.enum(["ACCRUAL", "CASH"]),
  accountantId: z.string(),
  reviewerId: z.string(),
  notes: z.string().max(2000),
});
type Values = z.input<typeof schema>;
type Parsed = z.output<typeof schema>;

const FIELDS = Object.keys(schema.shape);

export function ClientFormPage() {
  const { id } = useParams();
  const editing = !!id;
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const entityTypes = useEntityTypes();
  const accountants = useAssignableUsers("CLIENT_WRITE");
  const reviewers = useAssignableUsers("CLIENT_APPROVE");

  const existing = useQuery({
    queryKey: ["client", id],
    queryFn: () => api.get<ClientDetail>(`/clients/${id}`),
    enabled: editing,
  });

  const form = useForm<Values, unknown, Parsed>({
    resolver: zodResolver(schema),
    defaultValues: {
      displayName: "", legalName: "", entityType: "", pan: "", tan: "", dateOfIncorporation: "", email: "",
      phone: "", fyStartMonth: 4, accountingMethod: "ACCRUAL", accountantId: "", reviewerId: "", notes: "",
    },
  });
  const { register, handleSubmit, formState, setError, reset, watch } = form;
  const errors = formState.errors;

  useEffect(() => {
    const c = existing.data;
    if (c) {
      reset({
        displayName: c.displayName, legalName: c.legalName, entityType: c.entityType,
        pan: c.panRevealed ? c.pan : "", tan: c.tan ?? "", dateOfIncorporation: c.dateOfIncorporation ?? "",
        email: c.email ?? "", phone: c.phone ?? "", fyStartMonth: c.fyStartMonth, accountingMethod: c.accountingMethod,
        accountantId: c.accountant?.id ?? "", reviewerId: c.reviewer?.id ?? "", notes: c.notes ?? "",
      });
    }
  }, [existing.data, reset]);

  const entityType = watch("entityType");
  const pan = String(watch("pan") ?? "").toUpperCase();
  const allowedHolder = entityTypes.data?.find((t) => t.value === entityType)?.panHolderTypes;
  const panMismatch = pan.length >= 4 && allowedHolder && !allowedHolder.includes(pan[3]);
  const identityLocked = editing && existing.data && !["DRAFT", "DOCUMENTS_PENDING", "UNDER_VERIFICATION"].includes(existing.data.status);
  const panHidden = editing && existing.data && !existing.data.panRevealed;

  const save = useMutation({
    mutationFn: (v: Parsed) => {
      const body = {
        displayName: v.displayName, legalName: v.legalName, entityType: v.entityType, pan: v.pan,
        tan: nullIfBlank(v.tan as string), dateOfIncorporation: nullIfBlank(v.dateOfIncorporation),
        email: nullIfBlank(v.email), phone: nullIfBlank(v.phone), fyStartMonth: v.fyStartMonth,
        accountingMethod: v.accountingMethod, accountantId: nullIfBlank(v.accountantId),
        reviewerId: nullIfBlank(v.reviewerId), notes: nullIfBlank(v.notes),
        version: existing.data?.version,
      };
      return editing ? api.put<ClientDetail>(`/clients/${id}`, body) : api.post<ClientDetail>("/clients", body);
    },
    onSuccess: (c) => {
      queryClient.invalidateQueries({ queryKey: ["clients"] });
      queryClient.setQueryData(["client", c.id], c);
      queryClient.invalidateQueries({ queryKey: ["dashboard"] });
      toast.success(editing ? "Client updated" : `Client ${c.clientCode} created as a draft`);
      navigate(`/clients/${c.id}`);
    },
    onError: (e) => applyServerErrors(e, setError, FIELDS),
  });

  if (editing && existing.isError) return <ErrorState error={existing.error} onRetry={() => existing.refetch()} />;
  if (editing && existing.isPending) return <Skeleton className="h-96" />;
  if (panHidden) {
    return <ErrorState error={new Error("Editing a client requires permission to view its full PAN.")} />;
  }

  return (
    <div>
      <PageHeader
        title={editing ? `Edit ${existing.data?.displayName}` : "New client"}
        description={editing ? existing.data?.clientCode : "The client starts as a draft and moves through onboarding before activation."}
      />
      <form onSubmit={handleSubmit((v) => save.mutate(v))} noValidate className="space-y-5">
        <Card>
          <CardHeader title="Identity" description="Legal entity and tax identifiers" />
          <div className="grid grid-cols-1 gap-4 p-5 md:grid-cols-2">
            <Field label="Display name" htmlFor="displayName" required error={errors.displayName?.message}>
              <Input id="displayName" {...register("displayName")} aria-invalid={!!errors.displayName} />
            </Field>
            <Field label="Legal name" htmlFor="legalName" required error={errors.legalName?.message}>
              <Input id="legalName" {...register("legalName")} aria-invalid={!!errors.legalName} />
            </Field>
            <Field label="Entity type" htmlFor="entityType" required error={errors.entityType?.message}
              hint={identityLocked ? "Locked after the client is sent for approval" : undefined}>
              <Select id="entityType" {...register("entityType")} disabled={!!identityLocked} aria-invalid={!!errors.entityType}>
                <option value="">Select…</option>
                {entityTypes.data?.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
              </Select>
            </Field>
            <Field label="PAN" htmlFor="pan" required
              error={errors.pan?.message ?? (panMismatch ? `4th character should be ${allowedHolder?.join(" or ")} for this entity type` : undefined)}
              hint={identityLocked ? "Locked after the client is sent for approval" : "Format AAAAA9999A"}>
              <Input id="pan" maxLength={10} className="font-mono uppercase" autoComplete="off" {...register("pan")}
                disabled={!!identityLocked} aria-invalid={!!errors.pan || !!panMismatch} />
            </Field>
            <Field label="TAN" htmlFor="tan" error={errors.tan?.message} hint="Optional · format AAAA99999A">
              <Input id="tan" maxLength={10} className="font-mono uppercase" {...register("tan")} aria-invalid={!!errors.tan} />
            </Field>
            <Field label="Date of incorporation / birth" htmlFor="dateOfIncorporation">
              <Input id="dateOfIncorporation" type="date" {...register("dateOfIncorporation")} />
            </Field>
          </div>
        </Card>

        <Card>
          <CardHeader title="Contact and accounting" />
          <div className="grid grid-cols-1 gap-4 p-5 md:grid-cols-2">
            <Field label="Email" htmlFor="email" error={errors.email?.message}>
              <Input id="email" type="email" {...register("email")} aria-invalid={!!errors.email} />
            </Field>
            <Field label="Phone" htmlFor="phone" error={errors.phone?.message}>
              <Input id="phone" type="tel" {...register("phone")} aria-invalid={!!errors.phone} />
            </Field>
            <Field label="Financial year starts in" htmlFor="fyStartMonth">
              <Select id="fyStartMonth" {...register("fyStartMonth")}>
                {MONTHS.map((m, i) => <option key={m} value={i + 1}>{m}</option>)}
              </Select>
            </Field>
            <Field label="Accounting method" htmlFor="accountingMethod">
              <Select id="accountingMethod" {...register("accountingMethod")}>
                <option value="ACCRUAL">Accrual</option>
                <option value="CASH">Cash</option>
              </Select>
            </Field>
          </div>
        </Card>

        <Card>
          <CardHeader title="Assignment" description="Both are required before the client can be sent for approval. The reviewer must be a different person who can approve clients." />
          <div className="grid grid-cols-1 gap-4 p-5 md:grid-cols-2">
            <Field label="Accountant" htmlFor="accountantId" error={errors.accountantId?.message}>
              <Select id="accountantId" {...register("accountantId")}>
                <option value="">Unassigned</option>
                {accountants.data?.map((u) => <option key={u.id} value={u.id}>{u.fullName} ({u.email})</option>)}
              </Select>
            </Field>
            <Field label="Reviewer" htmlFor="reviewerId" error={errors.reviewerId?.message}>
              <Select id="reviewerId" {...register("reviewerId")} aria-invalid={!!errors.reviewerId}>
                <option value="">Unassigned</option>
                {reviewers.data?.map((u) => <option key={u.id} value={u.id}>{u.fullName} ({u.email})</option>)}
              </Select>
            </Field>
            <Field label="Notes" htmlFor="notes" className="md:col-span-2">
              <Textarea id="notes" maxLength={2000} {...register("notes")} />
            </Field>
          </div>
        </Card>

        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={() => navigate(-1)}>Cancel</Button>
          <Button type="submit" loading={save.isPending}>{editing ? "Save changes" : "Create client"}</Button>
        </div>
      </form>
    </div>
  );
}
