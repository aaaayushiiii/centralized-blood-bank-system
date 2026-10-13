import { getCsrfToken } from "./sessionSecurity.js";
export class ApiError extends Error {
  constructor(message, status = 0, code = "") {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
  }
}
// Cookie sessions are included on auth and operational requests. No mock fallback.
export async function requestJson(
  baseUrl,
  path,
  { method = "GET", body, signal, notifyUnauthorized = true } = {},
) {
  const controller = new AbortController();
  const abort = () => controller.abort();
  signal?.addEventListener("abort", abort, { once: true });
  if (signal?.aborted) controller.abort();
  const timeout = setTimeout(abort, 15000);
  try {
    const csrf = getCsrfToken();
    const response = await fetch(`${baseUrl.replace(/\/$/, "")}${path}`, {
      method,
      credentials: "include",
      cache: "no-store",
      signal: controller.signal,
      headers: {
        Accept: "application/json",
        ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
        ...(method !== "GET" && csrf ? { "X-CSRF-Token": csrf } : {}),
      },
      ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
    });
    if (
      response.status === 401 &&
      notifyUnauthorized &&
      typeof window !== "undefined"
    )
      window.dispatchEvent(new Event("auth:expired"));
    if (response.status === 204 && response.ok) return null;
    let data;
    try {
      data = await response.json();
    } catch {
      throw new ApiError(
        `The service returned an unreadable response (HTTP ${response.status}).`,
        response.status,
      );
    }
    if (!response.ok) {
      // /me can provide a guest CSRF token when it returns 401.
      const error = new ApiError(
        data?.error?.message || `Request failed (HTTP ${response.status}).`,
        response.status,
        data?.error?.code,
      );
      error.csrfToken = data?.csrf_token;
      throw error;
    }
    return data;
  } catch (error) {
    if (signal?.aborted) throw error;
    if (error.name === "AbortError")
      throw new ApiError(
        "The service took too long to respond. Check the result before retrying.",
      );
    if (error instanceof TypeError)
      throw new ApiError(
        "Cannot connect to the service. Please try again when the connection is available.",
      );
    throw error;
  } finally {
    clearTimeout(timeout);
    signal?.removeEventListener("abort", abort);
  }
}
