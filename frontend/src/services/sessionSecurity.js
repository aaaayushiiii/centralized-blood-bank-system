// CSRF token only; the authentication cookie remains inaccessible to JavaScript.
// No passwords, JWTs or session credentials are persisted in browser storage.
let csrfToken = "";
export function getCsrfToken() {
  return csrfToken;
}
export function setCsrfToken(value) {
  csrfToken = typeof value === "string" ? value : "";
}
