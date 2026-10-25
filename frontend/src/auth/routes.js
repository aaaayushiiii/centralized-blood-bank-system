export const routes = {
  "/": null,
  "/roles": null,
  "/citizen/sign-in": null,
  "/citizen/sign-up": null,
  "/hospital/sign-in": null,
  "/hospital/request-access": null,
  "/hospital/register": null,
  "/admin/sign-in": null,
  "/admin": "ADMIN",
  "/citizen": "CITIZEN",
  "/hospital": "HOSPITAL_STAFF",
  "/request": "HOSPITAL_STAFF",
};
export function roleHome(user) {
  if (user?.role === "ADMIN") return "/admin";
  return user?.role === "HOSPITAL_STAFF" ? "/hospital" : "/citizen";
}
export function canAccess(path, user) {
  return (
    Object.hasOwn(routes, path) &&
    (!routes[path] || user?.role === routes[path])
  );
}
export function readRoute() {
  const hash = window.location.hash.slice(1);
  const path = hash ? (hash.startsWith("/") ? hash : `/${hash}`) : "/";
  return path;
}
export function navigate(path, replace = false) {
  if (replace) {
    window.history.replaceState(null, "", `#${path}`);
    window.dispatchEvent(new Event("hashchange"));
  } else window.location.hash = path;
}
