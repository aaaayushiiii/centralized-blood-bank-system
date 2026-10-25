import { requestJson, ApiError } from "./transport.js";
import { validateSession } from "./authClient.js";
import { getCsrfToken, setCsrfToken } from "./sessionSecurity.js";

export const REVIEW_STATUSES = ["PENDING", "APPROVED", "REJECTED"];
const bytes = new TextEncoder();
function text(value, label, max) {
  if (typeof value !== "string" || !value.trim() || /[\p{Cc}\p{Cs}]/u.test(value) || bytes.encode(value.trim()).length > max)
    throw new Error(`${label} is required (up to ${max} UTF-8 bytes, without control characters).`);
  return value.trim();
}
function id(value) {
  if (!Number.isSafeInteger(Number(value)) || !/^[1-9][0-9]{0,9}$/.test(String(value))) throw new Error("Invalid application reference.");
  return String(value);
}
function common(application, token, confirmation, action) {
  const reference = id(application.registration_request_id);
  if (application.status !== "PENDING") throw new Error("Only pending applications can be reviewed.");
  if (!/^[0-9a-f]{64}$/.test(token || "")) throw new Error("Reload the application to obtain a current review token.");
  if (confirmation !== `${action} ${reference}`) throw new Error(`Type ${action} ${reference} exactly to confirm.`);
  return { review_token: token, confirmation };
}
export function approvalPayload(application, token) {
  return common(application, token, `APPROVE ${application.registration_request_id}`, "APPROVE");
}
export function rejectionPayload(application, token, values) {
  return { ...common(application, token, `REJECT ${application.registration_request_id}`, "REJECT"),
    rejection_reason: text(values.rejection_reason, "Rejection reason", 1000) };
}
export function reviewError(error, decision = false) {
  if (error.status === 401) return "Your administrator session has ended. Sign in again.";
  if (error.code === "CSRF_INVALID") return "The security token has expired. Reload the application before reviewing again.";
  if (error.status === 403) return "Administrator access is unavailable. Check the authorized account and local admin mode.";
  if (error.code === "REVIEW_STALE") return "This review is stale. Reload the application and repeat your checks.";
  if (error.status === 409) return "The application was already reviewed, is busy, or conflicts with another record. Reload and investigate before retrying.";
  if (error.status === 404) return "This application is no longer available. Refresh the list.";
  if (decision) return "The decision could not be confirmed. Reload the application to check its status before retrying.";
  return "Hospital applications could not be loaded. Check the local backend and try again.";
}
export function createAdminReviewClient(baseUrl) {
  const root = "/admin/hospital-registration-requests";
  function send(path, options) {
    if (!/^https?:\/\//.test(baseUrl)) throw new Error("The administrator service is not configured.");
    return requestJson(baseUrl, path, options);
  }
  return {
    async session(signal) {
      const data = validateSession(await send("/admin/auth/me", { signal }));
      if (data.user?.role !== "ADMIN") throw new ApiError("Administrator required", 403, "ADMIN_REQUIRED");
      setCsrfToken(data.csrf_token);
      return data.user;
    },
    async list(status = "PENDING", after = 0, signal) {
      if (!REVIEW_STATUSES.includes(status) || !/^(0|[1-9][0-9]{0,9})$/.test(String(after))) throw new Error("Invalid filter or cursor.");
      const data = await send(`${root}?status=${status}&after_id=${after}&limit=20`, { signal });
      if (!Array.isArray(data?.applications) || data.status !== status || data.applications.length > 20
        || (data.next_after_id !== null && (!Number.isSafeInteger(data.next_after_id) || data.next_after_id <= Number(after))))
        throw new Error("Invalid application list response.");
      return data;
    },
    async detail(reference, signal) {
      const data = await send(`${root}/${id(reference)}`, { signal });
      if (Number(data?.application?.registration_request_id) !== Number(reference) || !REVIEW_STATUSES.includes(data.application.status)
        || !/^[a-f0-9]{64}$/.test(data.review_token || "")) throw new Error("Invalid application detail response.");
      return data;
    },
    async decide(application, token, action, values) {
      if (!["approve", "reject"].includes(action)) throw new Error("Invalid review action.");
      const body = action === "approve" ? approvalPayload(application, token, values) : rejectionPayload(application, token, values);
      if (!getCsrfToken()) throw new ApiError("Reload security context", 403, "CSRF_INVALID");
      const data = await send(`${root}/${id(application.registration_request_id)}/${action}`, { method: "POST", body, expectedStatus: 200 });
      if (data?.registration_request_id !== application.registration_request_id || data.status !== (action === "approve" ? "APPROVED" : "REJECTED")
        || (action === "approve" && (!Number.isSafeInteger(data.approved_hospital_id) || data.approved_hospital_id < 1)))
        throw new Error("Decision response could not be confirmed.");
      return data;
    },
  };
}
export const adminReview = createAdminReviewClient(import.meta.env?.VITE_API_BASE_URL || "");
