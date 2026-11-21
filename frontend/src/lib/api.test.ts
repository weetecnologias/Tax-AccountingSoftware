import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, api, qs, setAccessToken } from "./api";

function jsonResponse(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

describe("api client", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    setAccessToken(null);
  });

  it("builds query strings without empty values", () => {
    expect(qs({ q: "acme", status: "", page: 0, x: undefined })).toBe("?q=acme&page=0");
    expect(qs({})).toBe("");
  });

  it("maps problem+json errors to ApiError with field errors", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(400, { detail: "One or more fields are invalid", code: "VALIDATION_FAILED", requestId: "r1",
        errors: [{ field: "pan", message: "PAN must be in the format AAAAA9999A" }] }),
    );
    const err = (await api.post("/clients", {}).catch((e: unknown) => e)) as ApiError;
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(400);
    expect(err.fieldErrors[0].field).toBe("pan");
    expect(err.requestId).toBe("r1");
  });

  it("refreshes once on 401 and retries with the new token", async () => {
    setAccessToken("expired");
    const fetchMock = vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(jsonResponse(401, {}))
      .mockResolvedValueOnce(jsonResponse(200, { accessToken: "fresh" }))
      .mockResolvedValueOnce(jsonResponse(200, { ok: true }));
    const res = await api.get<{ ok: boolean }>("/clients");
    expect(res.ok).toBe(true);
    expect(fetchMock).toHaveBeenCalledTimes(3);
    const retryHeaders = fetchMock.mock.calls[2][1]!.headers as Record<string, string>;
    expect(retryHeaders.Authorization).toBe("Bearer fresh");
  });
});
