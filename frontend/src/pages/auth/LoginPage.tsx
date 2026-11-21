import { zodResolver } from "@hookform/resolvers/zod";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { Navigate, useLocation, useNavigate } from "react-router-dom";
import { z } from "zod";
import { Button, Field, Input } from "@/components/ui";
import { ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";

const schema = z.object({
  email: z.string().trim().email("Enter a valid email"),
  password: z.string().min(1, "Enter your password"),
});
type Values = z.infer<typeof schema>;

export function LoginPage() {
  const { user, login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [error, setError] = useState<string | null>(null);
  const { register, handleSubmit, formState } = useForm<Values>({ resolver: zodResolver(schema) });

  if (user) return <Navigate to={user.mustChangePassword ? "/change-password" : "/"} replace />;

  const onSubmit = async (v: Values) => {
    setError(null);
    try {
      const me = await login(v.email, v.password);
      const from = (location.state as { from?: string } | null)?.from;
      navigate(me.mustChangePassword ? "/change-password" : (from ?? "/"), { replace: true });
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Unable to sign in. Check your connection and try again.");
    }
  };

  return (
    <div className="flex min-h-screen">
      <div className="hidden w-1/2 flex-col justify-between bg-primary p-12 text-white lg:flex">
        <div className="flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-emerald text-lg font-bold">T</div>
          <span className="text-lg font-semibold tracking-wide">TAXPILOT AI</span>
        </div>
        <div>
          <h1 className="text-3xl leading-tight font-semibold">Your Intelligent Accounting &amp; Tax Department</h1>
          <p className="mt-4 max-w-md text-white/70">
            Client onboarding, document management and a complete audit trail — with maker-checker controls on
            every consequential action.
          </p>
        </div>
        <p className="text-xs text-white/50">Internal use only. All access is logged.</p>
      </div>
      <div className="flex flex-1 items-center justify-center p-6">
        <form onSubmit={handleSubmit(onSubmit)} className="w-full max-w-sm space-y-5" noValidate>
          <div>
            <h2 className="text-xl font-semibold text-primary">Sign in</h2>
            <p className="mt-1 text-sm text-muted">Use your organisation account.</p>
          </div>
          {error && (
            <div role="alert" className="rounded-md border border-danger/30 bg-danger-soft px-3 py-2 text-sm text-danger">
              {error}
            </div>
          )}
          <Field label="Email" htmlFor="email" error={formState.errors.email?.message}>
            <Input id="email" type="email" autoComplete="username" autoFocus {...register("email")}
              aria-invalid={!!formState.errors.email} />
          </Field>
          <Field label="Password" htmlFor="password" error={formState.errors.password?.message}>
            <Input id="password" type="password" autoComplete="current-password" {...register("password")}
              aria-invalid={!!formState.errors.password} />
          </Field>
          <Button type="submit" className="w-full" size="lg" loading={formState.isSubmitting}>
            Sign in
          </Button>
          <p className="text-center text-xs text-muted">
            Forgot your password? Ask an administrator to reset it.
          </p>
        </form>
      </div>
    </div>
  );
}
