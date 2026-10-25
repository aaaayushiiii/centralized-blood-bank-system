import { createAuthClient } from "./authClient.js";
import { requestJson, ApiError } from "./transport.js";
import { getCsrfToken, setCsrfToken } from "./sessionSecurity.js";
import { hospitalRegistrationPayload } from "./hospitalRegistrationValidation.js";

export function createHospitalRegistrationClient(baseUrl) {
  const sessions = createAuthClient(baseUrl);
  return {
    async submit(values) {
      if (!/^https?:\/\//.test(baseUrl))
        throw new ApiError("The hospital registration service is not configured. Please contact support.", 0, "CONFIGURATION_REQUIRED");
      const body = hospitalRegistrationPayload(values);
      if (!getCsrfToken()) {
        try { await sessions.getSession(); }
        catch (error) {
          throw new ApiError("A secure submission session could not be established. No application was sent. Try again when the service is available.", error.status || 0, "SESSION_UNAVAILABLE");
        }
      }
      let result;
      try {
        result = await requestJson(baseUrl, "/hospital-registration-requests", {
          method: "POST", body, notifyUnauthorized: false, expectedStatus: 201,
        });
      } catch (error) {
        if (error.code === "CSRF_INVALID" || error.status === 401) setCsrfToken("");
        throw error;
      }
      if (!result || result.status !== "PENDING" || !Number.isSafeInteger(result.registration_request_id)
          || result.registration_request_id < 1 || result.registration_request_id > 9999999999) {
        throw new ApiError("The application result could not be confirmed. Contact the network administrator before submitting again.", 201, "SUBMISSION_UNCONFIRMED");
      }
      return { registration_request_id: result.registration_request_id, status: "PENDING" };
    },
  };
}

export const hospitalRegistration = createHospitalRegistrationClient(import.meta.env?.VITE_API_BASE_URL || "");

export function registrationFailure(error) {
  if (error.field) return { error: error.message, uncertain: false };
  if (error.code === "SESSION_UNAVAILABLE") return { error: error.message, uncertain: false };
  if (error.status === 409) return {
    error: "This application conflicts with an existing record. Contact the network administrator to resolve the registration; submitting again does not grant approval.", uncertain: false,
  };
  if (error.code === "CONFIGURATION_REQUIRED") return { error: error.message, uncertain: false };
  if (error.code === "CSRF_INVALID" || error.status === 401) return {
    error: "Your submission session has expired. Submit again to refresh the secure session; your details have been kept.", uncertain: false,
  };
  if (error.status === 403) return { error: "This submission could not be authorized. Refresh the page or contact support.", uncertain: false };
  if (error.status === 429) return { error: "Too many submissions. Wait at least ten minutes before trying again; your details have been kept.", uncertain: false };
  if (error.status === 503) return { error: "The registration service is unavailable. Your details have been kept. Try later; if a previous attempt had an unknown result, contact the administrator first.", uncertain: false };
  if (error.status === 400 || error.status === 413 || error.status === 415) return {
    error: error.message || "Check your application details and try again.", uncertain: false,
  };
  return {
    error: "We could not confirm whether your application was received. Contact the network administrator before submitting again to avoid a duplicate application.", uncertain: true,
  };
}
