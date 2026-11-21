import { AlertTriangle, ChevronLeft, ChevronRight, Inbox } from "lucide-react";
import { useState, type ReactNode } from "react";
import { ApiError } from "@/lib/api";
import { humanize } from "@/lib/utils";
import { Badge, Button, Dialog, Field, Textarea, type BadgeTone } from "./ui";

export function PageHeader({ title, description, actions }: { title: string; description?: string; actions?: ReactNode }) {
  return (
    <div className="mb-5 flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 className="text-xl font-semibold text-primary">{title}</h1>
        {description && <p className="mt-1 text-sm text-muted">{description}</p>}
      </div>
      {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
    </div>
  );
}

export function EmptyState({ title, description, action }: { title: string; description?: string; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 px-6 py-12 text-center">
      <Inbox className="h-8 w-8 text-muted/60" aria-hidden />
      <p className="font-medium">{title}</p>
      {description && <p className="max-w-sm text-sm text-muted">{description}</p>}
      {action && <div className="mt-2">{action}</div>}
    </div>
  );
}

export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const message = error instanceof Error ? error.message : "Something went wrong.";
  const requestId = error instanceof ApiError ? error.requestId : undefined;
  return (
    <div role="alert" className="flex flex-col items-center gap-2 px-6 py-10 text-center">
      <AlertTriangle className="h-7 w-7 text-danger" aria-hidden />
      <p className="font-medium">Could not load this data</p>
      <p className="max-w-md text-sm text-muted">{message}</p>
      {requestId && <p className="text-xs text-muted">Request id: {requestId}</p>}
      {onRetry && (
        <Button variant="secondary" size="sm" onClick={onRetry} className="mt-2">
          Try again
        </Button>
      )}
    </div>
  );
}

export function LoadingRows({ rows = 5, cols = 5 }: { rows?: number; cols?: number }) {
  return (
    <>
      {Array.from({ length: rows }).map((_, r) => (
        <tr key={r}>
          {Array.from({ length: cols }).map((__, c) => (
            <td key={c} className="border-b border-line px-4 py-3">
              <div className="h-3.5 animate-pulse rounded bg-line" />
            </td>
          ))}
        </tr>
      ))}
    </>
  );
}

export function Skeleton({ className = "h-20" }: { className?: string }) {
  return <div className={`animate-pulse rounded-card bg-line/70 ${className}`} />;
}

export function Pagination({
  page,
  totalPages,
  totalItems,
  onPage,
}: {
  page: number;
  totalPages: number;
  totalItems: number;
  onPage: (page: number) => void;
}) {
  if (totalItems === 0) return null;
  return (
    <div className="flex items-center justify-between gap-3 px-4 py-3 text-sm text-muted">
      <span>
        {totalItems} record{totalItems === 1 ? "" : "s"} · page {page + 1} of {Math.max(totalPages, 1)}
      </span>
      <div className="flex gap-1">
        <Button variant="secondary" size="icon" aria-label="Previous page" disabled={page === 0} onClick={() => onPage(page - 1)}>
          <ChevronLeft className="h-4 w-4" />
        </Button>
        <Button
          variant="secondary"
          size="icon"
          aria-label="Next page"
          disabled={page + 1 >= totalPages}
          onClick={() => onPage(page + 1)}
        >
          <ChevronRight className="h-4 w-4" />
        </Button>
      </div>
    </div>
  );
}

const STATUS_TONES: Record<string, BadgeTone> = {
  DRAFT: "neutral",
  DOCUMENTS_PENDING: "warning",
  UNDER_VERIFICATION: "warning",
  READY_FOR_APPROVAL: "info",
  ACTIVE: "success",
  ON_HOLD: "warning",
  CLOSED: "danger",
  UPLOADED: "warning",
  REVIEW_REQUIRED: "warning",
  DUPLICATE_SUSPECTED: "danger",
  APPROVED: "success",
  REJECTED: "danger",
  ARCHIVED: "neutral",
  CANCELLED: "danger",
  SUSPENDED: "warning",
  CLEAN: "success",
  NOT_SCANNED: "warning",
};

export function StatusBadge({ status }: { status: string }) {
  return <Badge tone={STATUS_TONES[status] ?? "neutral"}>{humanize(status)}</Badge>;
}

/** Confirmation for consequential actions, optionally collecting a comment. */
export function ConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel,
  tone = "primary",
  commentLabel,
  commentRequired,
  onConfirm,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description: string;
  confirmLabel: string;
  tone?: "primary" | "danger" | "success";
  commentLabel?: string;
  commentRequired?: boolean;
  onConfirm: (comment: string) => Promise<unknown>;
}) {
  const [comment, setComment] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const close = (o: boolean) => {
    if (!o) {
      setComment("");
      setError(null);
    }
    onOpenChange(o);
  };

  const submit = async () => {
    if (commentRequired && !comment.trim()) {
      setError(`${commentLabel ?? "Comment"} is required`);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await onConfirm(comment.trim());
      close(false);
    } catch (e) {
      setError(e instanceof Error ? e.message : "The action failed");
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog
      open={open}
      onOpenChange={close}
      title={title}
      description={description}
      footer={
        <>
          <Button variant="secondary" onClick={() => close(false)} disabled={busy}>
            Cancel
          </Button>
          <Button variant={tone} onClick={submit} loading={busy}>
            {confirmLabel}
          </Button>
        </>
      }
    >
      {commentLabel ? (
        <Field label={commentLabel} htmlFor="confirm-comment" required={commentRequired} error={error ?? undefined}>
          <Textarea
            id="confirm-comment"
            value={comment}
            onChange={(e) => setComment(e.target.value)}
            maxLength={1000}
            aria-invalid={!!error}
          />
        </Field>
      ) : (
        error && (
          <p role="alert" className="text-sm text-danger">
            {error}
          </p>
        )
      )}
    </Dialog>
  );
}

export function DetailList({ items }: { items: { label: string; value: ReactNode }[] }) {
  return (
    <dl className="grid grid-cols-1 gap-x-6 gap-y-4 sm:grid-cols-2 lg:grid-cols-3">
      {items.map((i) => (
        <div key={i.label}>
          <dt className="text-xs font-medium text-muted">{i.label}</dt>
          <dd className="mt-1 text-sm break-words">{i.value ?? "—"}</dd>
        </div>
      ))}
    </dl>
  );
}
