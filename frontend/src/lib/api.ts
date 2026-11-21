/**
 * Thin fetch client. The access token lives only in memory; the refresh token is an HttpOnly cookie
 * the browser sends to /api/v1/auth/* automatically. A 401 triggers one silent refresh, then a retry.
 */

export type FieldError = { field: string; message: string };

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly fieldErrors: FieldError[];
  readonly requestId?: string;

  constructor(status: number, code: string, message: string, fieldErrors: FieldError[] = [], requestId?: string) {
    super(message);
    this.status = status;
    this.code = code;
    this.fieldErrors = fieldErrors;
    this.requestId = requestId;
  }
}

const BASE = "/api/v1";
let accessToken: string | null = null;
let refreshInFlight: Promise<boolean> | null = null;
let onSessionEnded: (() => void) | null = null;

export function setAccessToken(token: string | null) {
  accessToken = token;
}

export function setSessionEndedHandler(handler: () => void) {
  onSessionEnded = handler;
}

export async function parseError(res: Response): Promise<ApiError> {
  let body: Record<string, unknown> = {};
  try {
    body = await res.json();
  } catch {
    /* non-JSON error body */
  }
  const message =
    (body.detail as string) ??
    (res.status === 403 ? "You do not have permission to do that." : `Request failed (${res.status})`);
  return new ApiError(
    res.status,
    (body.code as string) ?? "HTTP_" + res.status,
    message,
    (body.errors as FieldError[]) ?? [],
    body.requestId as string | undefined,
  );
}

/** Attempts a silent refresh using the HttpOnly cookie. Concurrent callers share one request. */
export function refreshSession(): Promise<boolean> {
  if (!refreshInFlight) {
    refreshInFlight = fetch(`${BASE}/auth/refresh`, {
      method: "POST",
      credentials: "same-origin",
      headers: { "X-Requested-With": "taxpilot" },
    })
      .then(async (res) => {
        if (!res.ok) {
          accessToken = null;
          return false;
        }
        const data = await res.json();
        accessToken = data.accessToken;
        return true;
      })
      .catch(() => false)
      .finally(() => {
        refreshInFlight = null;
      });
  }
  return refreshInFlight;
}

type RequestOptions = { method?: string; body?: unknown; signal?: AbortSignal; raw?: boolean };

async function send(path: string, opts: RequestOptions): Promise<Response> {
  const headers: Record<string, string> = { "X-Requested-With": "taxpilot" };
  let body: BodyInit | undefined;
  if (opts.body instanceof FormData) {
    body = opts.body;
  } else if (opts.body !== undefined) {
    headers["Content-Type"] = "application/json";
    body = JSON.stringify(opts.body);
  }
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`;
  return fetch(`${BASE}${path}`, {
    method: opts.method ?? "GET",
    headers,
    body,
    signal: opts.signal,
    credentials: "same-origin",
  });
}

export async function request<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  let res = await send(path, opts);
  if (res.status === 401 && !path.startsWith("/auth/login")) {
    if (await refreshSession()) {
      res = await send(path, opts);
    } else {
      onSessionEnded?.();
    }
  }
  if (!res.ok) throw await parseError(res);
  if (opts.raw) return res as unknown as T;
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export const api = {
  get: <T>(path: string, signal?: AbortSignal) => request<T>(path, { signal }),
  post: <T>(path: string, body?: unknown) => request<T>(path, { method: "POST", body }),
  put: <T>(path: string, body?: unknown) => request<T>(path, { method: "PUT", body }),
  del: <T>(path: string) => request<T>(path, { method: "DELETE" }),
};

/** Downloads an authenticated file and hands it to the browser as a save dialog. */
export async function downloadFile(path: string, fallbackName: string) {
  const res = await request<Response>(path, { raw: true });
  const blob = await res.blob();
  const disposition = res.headers.get("Content-Disposition") ?? "";
  const utf = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
  const plain = /filename="?([^";]+)"?/i.exec(disposition);
  const name = utf ? decodeURIComponent(utf[1]) : plain ? plain[1] : fallbackName;
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export function qs(params: Record<string, string | number | boolean | undefined | null>): string {
  const sp = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== "") sp.set(k, String(v));
  }
  const s = sp.toString();
  return s ? `?${s}` : "";
}
