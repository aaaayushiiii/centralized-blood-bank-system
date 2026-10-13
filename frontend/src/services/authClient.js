import { requestJson } from "./transport.js";
import { getCsrfToken, setCsrfToken } from "./sessionSecurity.js";
export const ROLES = { CITIZEN: "CITIZEN", STAFF: "HOSPITAL_STAFF" };
export function validateSession(data) {
  if (!data || !Object.hasOwn(data, "user"))
    throw new Error(
      "The session response is incomplete. Please contact support.",
    );
  const user = data.user;
  if (user !== null) {
    if (
      !user ||
      !["CITIZEN", "HOSPITAL_STAFF"].includes(user.role) ||
      typeof user.full_name !== "string" ||
      !user.full_name.trim() ||
      typeof user.email !== "string" ||
      !user.user_id ||
      user.status !== "ACTIVE"
    )
      throw new Error(
        "Your account is not ready for access or the session response is invalid.",
      );
    if (user.role === ROLES.STAFF && !user.hospital_id)
      throw new Error(
        "Your staff account does not have an approved hospital assignment.",
      );
  }
  if (typeof data.csrf_token !== "string" || !data.csrf_token)
    throw new Error("The session could not be secured. Please try again.");
  return data;
}
export function createAuthClient(baseUrl) {
  function checkUrl() {
    if (!/^https?:\/\//.test(baseUrl))
      throw new Error(
        "The sign-in service is not configured. Please contact support.",
      );
  }
  async function send(path, options) {
    checkUrl();
    return requestJson(baseUrl, path, {
      ...options,
      notifyUnauthorized: false,
    });
  }
  return {
    async getSession(signal) {
      let data;
      try {
        data = await send("/auth/me", { signal });
      } catch (error) {
        if (error.status !== 401) throw error;
        data = { user: null, csrf_token: error.csrfToken };
      }
      validateSession(data);
      setCsrfToken(data.csrf_token);
      return data;
    },
    async login(body) {
      if (!getCsrfToken()) await this.getSession();
      const result = await send("/auth/login", { method: "POST", body });
      if (result?.csrf_token) setCsrfToken(result.csrf_token);
      // Only the subsequent cookie-backed /me response establishes UI authentication.
      const session = await this.getSession();
      if (!session.user)
        throw new Error(
          "Sign-in could not establish a session. Check that cookies are enabled and try again.",
        );
      if (session.user.role !== body.role)
        throw new Error(
          "This account does not match the selected role. Choose the correct sign-in screen.",
        );
      return session;
    },
    async registerCitizen(body) {
      if (!getCsrfToken()) await this.getSession();
      const result = await send("/auth/register/citizen", {
        method: "POST",
        body,
      });
      if (result?.status !== "REGISTERED")
        throw new Error(
          "Registration could not be confirmed. Please check with support before retrying.",
        );
      return result;
    },
    async requestHospitalAccess(body) {
      if (!getCsrfToken()) await this.getSession();
      const result = await send("/auth/register/hospital", {
        method: "POST",
        body,
      });
      if (result?.status !== "PENDING_APPROVAL")
        throw new Error(
          "The access request could not be confirmed. Please contact support before retrying.",
        );
      return result;
    },
    async logout() {
      await send("/auth/logout", { method: "POST", body: {} });
      setCsrfToken("");
    },
  };
}
