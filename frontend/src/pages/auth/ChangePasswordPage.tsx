import { zodResolver } from "@hookform/resolvers/zod";
import { useForm } from "react-hook-form";
import { useNavigate } from "react-router-dom";
import { toast } from "sonner";
import { z } from "zod";
import { Button, Card, Field, Input } from "@/components/ui";
import { useAuth } from "@/lib/auth";
import { applyServerErrors } from "@/lib/forms";
import { passwordSchema } from "@/validation/ids";

const schema = z
  .object({
    currentPassword: z.string().min(1, "Enter your current password"),
    newPassword: passwordSchema,
    confirm: z.string(),
  })
  .refine((v) => v.newPassword === v.confirm, { path: ["confirm"], message: "Passwords do not match" })
  .refine((v) => v.newPassword !== v.currentPassword, { path: ["newPassword"], message: "Choose a different password" });
type Values = z.infer<typeof schema>;

export function ChangePasswordPage() {
  const { user, changePassword, logout } = useAuth();
  const navigate = useNavigate();
  const { register, handleSubmit, formState, setError } = useForm<Values>({ resolver: zodResolver(schema) });
  const forced = !!user?.mustChangePassword;

  const onSubmit = async (v: Values) => {
    try {
      await changePassword(v.currentPassword, v.newPassword);
      toast.success("Password changed");
      navigate("/", { replace: true });
    } catch (e) {
      applyServerErrors(e, setError, ["currentPassword", "newPassword"]);
    }
  };

  return (
    <div className="flex min-h-screen items-center justify-center p-6">
      <Card className="w-full max-w-md p-6">
        <h1 className="text-lg font-semibold text-primary">{forced ? "Set a new password" : "Change password"}</h1>
        <p className="mt-1 text-sm text-muted">
          {forced
            ? "Your account uses a temporary password. Choose a new one to continue."
            : "Other signed-in sessions will be signed out."}
        </p>
        <form onSubmit={handleSubmit(onSubmit)} className="mt-5 space-y-4" noValidate>
          <Field label="Current password" htmlFor="currentPassword" error={formState.errors.currentPassword?.message}>
            <Input id="currentPassword" type="password" autoComplete="current-password" {...register("currentPassword")}
              aria-invalid={!!formState.errors.currentPassword} />
          </Field>
          <Field label="New password" htmlFor="newPassword" error={formState.errors.newPassword?.message}
            hint="12+ characters with upper-case, lower-case and a digit">
            <Input id="newPassword" type="password" autoComplete="new-password" {...register("newPassword")}
              aria-invalid={!!formState.errors.newPassword} />
          </Field>
          <Field label="Confirm new password" htmlFor="confirm" error={formState.errors.confirm?.message}>
            <Input id="confirm" type="password" autoComplete="new-password" {...register("confirm")}
              aria-invalid={!!formState.errors.confirm} />
          </Field>
          <div className="flex gap-2 pt-2">
            <Button type="submit" loading={formState.isSubmitting} className="flex-1">
              Save password
            </Button>
            {forced ? (
              <Button variant="secondary" onClick={() => logout().then(() => navigate("/login"))}>
                Sign out
              </Button>
            ) : (
              <Button variant="secondary" onClick={() => navigate(-1)}>
                Cancel
              </Button>
            )}
          </div>
        </form>
      </Card>
    </div>
  );
}
