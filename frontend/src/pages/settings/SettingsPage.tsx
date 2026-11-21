import { zodResolver } from "@hookform/resolvers/zod";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { KeyRound, Pencil, Plus } from "lucide-react";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { Link, useSearchParams } from "react-router-dom";
import { toast } from "sonner";
import { z } from "zod";
import { ConfirmDialog, ErrorState, LoadingRows, PageHeader, Pagination } from "@/components/common";
import { Badge, Button, Card, CardHeader, Dialog, Field, Input, Table, Tabs, TabsContent, TabsList, TabsTrigger, Td, Th } from "@/components/ui";
import { api, qs } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { applyServerErrors } from "@/lib/forms";
import { formatDateTime, humanize } from "@/lib/utils";
import type { Page, Permission, Role, User } from "@/types/api";
import { passwordSchema } from "@/validation/ids";

export function SettingsPage() {
  const { can } = useAuth();
  const [params, setParams] = useSearchParams();
  const tab = params.get("tab") ?? "users";
  return (
    <div>
      <PageHeader title="Settings" description="Users, roles and permissions"
        actions={<Link to="/change-password"><Button variant="secondary"><KeyRound className="h-4 w-4" /> Change my password</Button></Link>} />
      <Tabs value={tab} onValueChange={(v) => setParams({ tab: v }, { replace: true })}>
        <TabsList>
          <TabsTrigger value="users">Users</TabsTrigger>
          {can("ROLE_READ") && <TabsTrigger value="roles">Roles &amp; permissions</TabsTrigger>}
        </TabsList>
        <TabsContent value="users" className="mt-5"><UsersTab /></TabsContent>
        {can("ROLE_READ") && <TabsContent value="roles" className="mt-5"><RolesTab /></TabsContent>}
      </Tabs>
    </div>
  );
}

// ------------------------------------------------------------------ users

function UsersTab() {
  const { can, user: me } = useAuth();
  const [page, setPage] = useState(0);
  const [editing, setEditing] = useState<User | "new" | null>(null);
  const [resetting, setResetting] = useState<User | null>(null);
  const users = useQuery({ queryKey: ["users", page], queryFn: () => api.get<Page<User>>(`/users${qs({ page, size: 20 })}`),
    placeholderData: keepPreviousData });
  const roles = useQuery({ queryKey: ["roles"], queryFn: () => api.get<Role[]>("/roles"), enabled: can("ROLE_READ") });

  return (
    <Card>
      <CardHeader title="Users" description="Staff who can sign in. New users must change their temporary password at first sign-in."
        actions={can("USER_WRITE") && <Button size="sm" onClick={() => setEditing("new")}><Plus className="h-4 w-4" /> New user</Button>} />
      {users.isError ? <ErrorState error={users.error} onRetry={() => users.refetch()} /> : (
        <>
          <Table>
            <thead><tr><Th>Name</Th><Th>Roles</Th><Th>Status</Th><Th>Last sign-in</Th><Th /></tr></thead>
            <tbody>
              {users.isPending ? <LoadingRows cols={5} /> : users.data?.items.map((u) => (
                <tr key={u.id}>
                  <Td><p className="font-medium">{u.fullName}{u.id === me?.id && <span className="text-muted"> (you)</span>}</p><p className="text-xs text-muted">{u.email}</p></Td>
                  <Td><div className="flex flex-wrap gap-1">{u.roles.map((r) => <Badge key={r} tone="info">{r}</Badge>)}</div></Td>
                  <Td className="space-x-1">
                    {u.active ? <Badge tone="success">Active</Badge> : <Badge tone="danger">Inactive</Badge>}
                    {u.locked && <Badge tone="warning">Locked</Badge>}
                    {u.mustChangePassword && <Badge>Temporary password</Badge>}
                  </Td>
                  <Td className="text-muted">{formatDateTime(u.lastLoginAt)}</Td>
                  <Td className="text-right whitespace-nowrap">
                    {can("USER_WRITE") && <>
                      <Button variant="ghost" size="icon" aria-label={`Edit ${u.fullName}`} onClick={() => setEditing(u)}><Pencil className="h-4 w-4" /></Button>
                      <Button variant="ghost" size="icon" aria-label={`Reset password for ${u.fullName}`} title="Reset password" onClick={() => setResetting(u)}><KeyRound className="h-4 w-4" /></Button>
                    </>}
                  </Td>
                </tr>
              ))}
            </tbody>
          </Table>
          {users.data && <Pagination page={users.data.page} totalPages={users.data.totalPages} totalItems={users.data.totalItems} onPage={setPage} />}
        </>
      )}
      {editing && roles.data && <UserDialog existing={editing === "new" ? null : editing} roles={roles.data} onClose={() => setEditing(null)} />}
      {resetting && <ResetPasswordDialog user={resetting} onClose={() => setResetting(null)} />}
    </Card>
  );
}

const newUserSchema = z.object({
  email: z.string().trim().email("Enter a valid email"),
  fullName: z.string().trim().min(1, "Required").max(200),
  temporaryPassword: passwordSchema,
  roles: z.array(z.string()).min(1, "Choose at least one role"),
  active: z.boolean(),
});
const editUserSchema = newUserSchema.extend({ email: z.string(), temporaryPassword: z.string() });
type UserValues = z.infer<typeof newUserSchema>;

function UserDialog({ existing, roles, onClose }: { existing: User | null; roles: Role[]; onClose: () => void }) {
  const queryClient = useQueryClient();
  const [confirmDeactivate, setConfirmDeactivate] = useState<UserValues | null>(null);
  const { register, handleSubmit, formState, setError } = useForm<UserValues>({
    resolver: zodResolver(existing ? editUserSchema : newUserSchema),
    defaultValues: { email: existing?.email ?? "", fullName: existing?.fullName ?? "", temporaryPassword: "",
      roles: existing?.roles ?? [], active: existing?.active ?? true },
  });
  const e = formState.errors;
  const save = useMutation({
    mutationFn: (v: UserValues) => existing
      ? api.put(`/users/${existing.id}`, { fullName: v.fullName, active: v.active, roles: v.roles, version: existing.version })
      : api.post("/users", { email: v.email, fullName: v.fullName, temporaryPassword: v.temporaryPassword, roles: v.roles }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["users"] });
      toast.success(existing ? "User updated" : "User created. Share the temporary password securely.");
      onClose();
    },
    onError: (err) => applyServerErrors(err, setError, ["email", "fullName", "temporaryPassword", "roles"]),
  });
  const submit = (v: UserValues) => {
    if (existing?.active && !v.active) setConfirmDeactivate(v);
    else save.mutate(v);
  };

  return (
    <>
      <Dialog open onOpenChange={(o) => !o && onClose()} title={existing ? `Edit ${existing.fullName}` : "New user"}
        footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button loading={save.isPending} onClick={handleSubmit(submit)}>Save</Button></>}>
        <form className="space-y-4" noValidate onSubmit={(ev) => ev.preventDefault()}>
          <Field label="Email" htmlFor="u-email" required={!existing} error={e.email?.message}>
            <Input id="u-email" type="email" disabled={!!existing} {...register("email")} aria-invalid={!!e.email} />
          </Field>
          <Field label="Full name" htmlFor="u-name" required error={e.fullName?.message}>
            <Input id="u-name" {...register("fullName")} aria-invalid={!!e.fullName} />
          </Field>
          {!existing && (
            <Field label="Temporary password" htmlFor="u-pw" required error={e.temporaryPassword?.message}
              hint="12+ characters with upper-case, lower-case and a digit. The user must change it at first sign-in.">
              <Input id="u-pw" type="password" autoComplete="new-password" {...register("temporaryPassword")} aria-invalid={!!e.temporaryPassword} />
            </Field>
          )}
          <fieldset>
            <legend className="text-xs font-medium text-muted">Roles <span className="text-danger">*</span></legend>
            <div className="mt-2 space-y-2">
              {roles.map((r) => (
                <label key={r.code} className="flex items-start gap-2 text-sm">
                  <input type="checkbox" value={r.code} {...register("roles")} className="mt-0.5 h-4 w-4 accent-emerald" />
                  <span><span className="font-medium">{r.name}</span>{r.description && <span className="block text-xs text-muted">{r.description}</span>}</span>
                </label>
              ))}
            </div>
            {e.roles && <p role="alert" className="mt-1 text-xs text-danger">{e.roles.message}</p>}
          </fieldset>
          {existing && (
            <label className="flex items-center gap-2 text-sm"><input type="checkbox" {...register("active")} className="h-4 w-4 accent-emerald" /> Active (can sign in)</label>
          )}
        </form>
      </Dialog>
      <ConfirmDialog open={!!confirmDeactivate} onOpenChange={(o) => !o && setConfirmDeactivate(null)} title="Deactivate user"
        description={`${existing?.fullName} will be signed out everywhere and will not be able to sign in.`} confirmLabel="Deactivate" tone="danger"
        onConfirm={async () => { await save.mutateAsync(confirmDeactivate!); }} />
    </>
  );
}

function ResetPasswordDialog({ user, onClose }: { user: User; onClose: () => void }) {
  const queryClient = useQueryClient();
  const schema = z.object({ temporaryPassword: passwordSchema });
  const { register, handleSubmit, formState, setError } = useForm<{ temporaryPassword: string }>({ resolver: zodResolver(schema) });
  const reset = useMutation({
    mutationFn: (v: { temporaryPassword: string }) => api.post(`/users/${user.id}/reset-password`, v),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ["users"] }); toast.success("Password reset. The user must change it at next sign-in."); onClose(); },
    onError: (err) => applyServerErrors(err, setError, ["temporaryPassword"]),
  });
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={`Reset password for ${user.fullName}`}
      description="All of the user's sessions end immediately. Share the temporary password through a secure channel."
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button variant="danger" loading={reset.isPending} onClick={handleSubmit((v) => reset.mutate(v))}>Reset password</Button></>}>
      <Field label="Temporary password" htmlFor="r-pw" required error={formState.errors.temporaryPassword?.message}>
        <Input id="r-pw" type="password" autoComplete="new-password" {...register("temporaryPassword")} aria-invalid={!!formState.errors.temporaryPassword} />
      </Field>
    </Dialog>
  );
}

// ------------------------------------------------------------------ roles

function RolesTab() {
  const { can } = useAuth();
  const [editing, setEditing] = useState<Role | "new" | null>(null);
  const roles = useQuery({ queryKey: ["roles"], queryFn: () => api.get<Role[]>("/roles") });
  const perms = useQuery({ queryKey: ["permissions"], queryFn: () => api.get<Permission[]>("/permissions") });

  return (
    <Card>
      <CardHeader title="Roles" description="Permissions are granted through roles. Changes apply at each user's next token refresh (within 15 minutes)."
        actions={can("ROLE_WRITE") && <Button size="sm" onClick={() => setEditing("new")}><Plus className="h-4 w-4" /> New role</Button>} />
      {roles.isError ? <ErrorState error={roles.error} onRetry={() => roles.refetch()} /> : (
        <ul className="divide-y divide-line">
          {roles.data?.map((r) => (
            <li key={r.id} className="flex flex-wrap items-start justify-between gap-3 px-5 py-4">
              <div className="min-w-0 flex-1">
                <p className="font-medium">{r.name} <span className="font-mono text-xs text-muted">{r.code}</span> {r.systemRole && <Badge className="ml-1">System</Badge>}</p>
                {r.description && <p className="text-sm text-muted">{r.description}</p>}
                <div className="mt-2 flex flex-wrap gap-1">{r.permissions.map((p) => <Badge key={p} tone="info">{humanize(p)}</Badge>)}</div>
              </div>
              {can("ROLE_WRITE") && r.code !== "ADMIN" && (
                <Button variant="ghost" size="icon" aria-label={`Edit ${r.name}`} onClick={() => setEditing(r)}><Pencil className="h-4 w-4" /></Button>
              )}
            </li>
          ))}
        </ul>
      )}
      {editing && perms.data && <RoleDialog existing={editing === "new" ? null : editing} permissions={perms.data} onClose={() => setEditing(null)} />}
    </Card>
  );
}

const roleSchema = z.object({
  code: z.string().trim().regex(/^[A-Z][A-Z0-9_]{1,59}$/, "Upper-case letters, digits and _"),
  name: z.string().trim().min(1, "Required").max(120),
  description: z.string().max(300),
  permissions: z.array(z.string()).min(1, "Choose at least one permission"),
});
type RoleValues = z.infer<typeof roleSchema>;

function RoleDialog({ existing, permissions, onClose }: { existing: Role | null; permissions: Permission[]; onClose: () => void }) {
  const queryClient = useQueryClient();
  const { register, handleSubmit, formState, setError } = useForm<RoleValues>({
    resolver: zodResolver(roleSchema),
    defaultValues: { code: existing?.code ?? "", name: existing?.name ?? "", description: existing?.description ?? "", permissions: existing?.permissions ?? [] },
  });
  const e = formState.errors;
  const save = useMutation({
    mutationFn: (v: RoleValues) => existing
      ? api.put(`/roles/${existing.id}`, { name: v.name, description: v.description || null, permissions: v.permissions, version: existing.version })
      : api.post("/roles", { ...v, description: v.description || null }),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ["roles"] }); toast.success("Role saved"); onClose(); },
    onError: (err) => applyServerErrors(err, setError, ["code", "name", "description", "permissions"]),
  });
  const modules = [...new Set(permissions.map((p) => p.module))];

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} wide title={existing ? `Edit ${existing.name}` : "New role"}
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button loading={save.isPending} onClick={handleSubmit((v) => save.mutate(v))}>Save</Button></>}>
      <form className="space-y-4" noValidate onSubmit={(ev) => ev.preventDefault()}>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label="Code" htmlFor="r-code" required error={e.code?.message}><Input id="r-code" className="font-mono uppercase" disabled={!!existing} {...register("code")} aria-invalid={!!e.code} /></Field>
          <Field label="Name" htmlFor="r-name" required error={e.name?.message}><Input id="r-name" {...register("name")} aria-invalid={!!e.name} /></Field>
        </div>
        <Field label="Description" htmlFor="r-desc"><Input id="r-desc" {...register("description")} /></Field>
        <fieldset>
          <legend className="text-xs font-medium text-muted">Permissions</legend>
          <div className="mt-2 grid grid-cols-1 gap-4 sm:grid-cols-2">
            {modules.map((m) => (
              <div key={m}>
                <p className="mb-1 text-xs font-semibold text-primary uppercase">{m}</p>
                {permissions.filter((p) => p.module === m).map((p) => (
                  <label key={p.code} className="flex items-start gap-2 py-0.5 text-sm">
                    <input type="checkbox" value={p.code} {...register("permissions")} className="mt-0.5 h-4 w-4 accent-emerald" />
                    <span>{p.description}</span>
                  </label>
                ))}
              </div>
            ))}
          </div>
          {e.permissions && <p role="alert" className="mt-1 text-xs text-danger">{e.permissions.message}</p>}
        </fieldset>
      </form>
    </Dialog>
  );
}
