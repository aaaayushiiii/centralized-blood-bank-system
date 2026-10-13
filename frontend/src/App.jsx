import { PREVIEW_ENABLED } from "./preview/config.js";
import { previewPaths } from "./preview/policy.js";
import PreviewOptions from "./components/PreviewOptions.jsx";
import { lazy, Suspense, useEffect, useRef, useState } from "react";
import CitizenPortal from "./pages/CitizenPortal.jsx";
import HospitalCommandCenter from "./pages/HospitalCommandCenter.jsx";
import EmergencyRequest from "./pages/EmergencyRequest.jsx";
import LandingPage from "./pages/LandingPage.jsx";
import RoleSelection from "./pages/RoleSelection.jsx";
import AuthPage from "./pages/AuthPage.jsx";
import Feedback from "./components/Feedback.jsx";
import { useAuth } from "./auth/AuthContext.jsx";
import {
  routes,
  readRoute,
  navigate,
  roleHome,
  canAccess,
} from "./auth/routes.js";
// Build-time command gate ensures the preview workspace and fixtures are excluded.
const PreviewWorkspace =
  import.meta.env.DEV && __ENABLE_LOCAL_PREVIEW__
    ? lazy(() => import("./preview/PreviewWorkspace.jsx"))
    : null;
export default function App() {
  const [path, setPath] = useState(readRoute);
  const { user, loading, error, retry, logout } = useAuth();
  const [hospitalId, setHospitalId] = useState("");
  const [logoutState, setLogoutState] = useState({ loading: false, error: "" });
  const logoutLock = useRef(false);
  const main = useRef(null);
  useEffect(() => {
    const change = () => {
      setPath(readRoute());
      main.current?.focus();
    };
    window.addEventListener("hashchange", change);
    return () => window.removeEventListener("hashchange", change);
  }, []);
  useEffect(() => {
    setHospitalId(
      user?.role === "HOSPITAL_STAFF" ? String(user.hospital_id) : "",
    );
  }, [user]);
  useEffect(() => {
    if (loading) return;
    if (routes[path] && !user)
      navigate(
        routes[path] === "CITIZEN" ? "/citizen/sign-in" : "/hospital/sign-in",
        true,
      );
    else if (
      user &&
      (routes[path] ||
        path.endsWith("/sign-in") ||
        path.endsWith("/sign-up") ||
        path.endsWith("/request-access"))
    ) {
      if (!canAccess(path, user) || !routes[path])
        navigate(roleHome(user), true);
    }
  }, [path, user, loading]);
  async function signOut() {
    if (logoutLock.current) return;
    logoutLock.current = true;
    setLogoutState({ loading: true, error: "" });
    try {
      await logout();
      setHospitalId("");
      navigate("/", true);
      setLogoutState({ loading: false, error: "" });
    } catch (error) {
      setLogoutState({
        loading: false,
        error: `Sign out could not be confirmed. ${error.message}`,
      });
    } finally {
      logoutLock.current = false;
    }
  }
  const staff = user?.role === "HOSPITAL_STAFF";
  const isPreviewRoute = PREVIEW_ENABLED && previewPaths.includes(path);
  const viewLinks = isPreviewRoute
    ? [
        ["/preview/citizen", "Citizen portal"],
        ["/preview/hospital", "Hospital command center"],
        ["/preview/request", "Emergency request"],
      ]
    : user
      ? staff
        ? [
            ["/hospital", "Hospital command center"],
            ["/request", "Emergency request"],
          ]
        : [["/citizen", "Citizen portal"]]
      : [
          ["/", "Home"],
          ["/roles", "Choose your role"],
        ];
  const protectedRoute = Boolean(routes[path]);
  const allowed = canAccess(path, user);
  const sessionBusy = loading || logoutState.loading;
  return (
    <>
      <a
        className="skip-link"
        href="#main-content"
        onClick={(e) => {
          e.preventDefault();
          main.current?.focus();
        }}
      >
        Skip to content
      </a>
      <header className="app-header">
        <a className="brand" href="#/">
          <span className="brand-mark" aria-hidden="true">
            +
          </span>
          <span>
            Centralized Blood Bank<small>Emergency Routing System</small>
          </span>
        </a>
        <div className="auth-header-actions">
          {isPreviewRoute ? (
            <a className="secondary auth-link" href="#/roles">
              Exit preview
            </a>
          ) : user && !loading ? (
            <>
              <span className="auth-user">{user.full_name}</span>
              <button
                className="secondary"
                disabled={logoutState.loading}
                onClick={signOut}
              >
                {logoutState.loading ? "Signing out…" : "Sign out"}
              </button>
            </>
          ) : (
            <a className="secondary auth-link" href="#/roles">
              Sign in
            </a>
          )}
        </div>
      </header>
      {isPreviewRoute && (
        <div className="mode-banner" role="status">
          <span className="mode-dot" />
          <strong>Development preview — not authenticated</strong>
          <span>
            Fictional hospitals, inventory and distances. No actual
            availability. Changes stay in memory and reset when you exit or
            reload. Reference date: 13 Oct 2026. Do not enter real personal
            data.
          </span>
        </div>
      )}
      <nav className="view-nav" aria-label="Main navigation">
        {viewLinks.map(([href, label]) => (
          <a
            key={href}
            href={`#${href}`}
            aria-current={path === href ? "page" : undefined}
          >
            {label}
          </a>
        ))}
      </nav>
      <main id="main-content" ref={main} tabIndex={-1}>
        <Feedback error={logoutState.error} />
        {isPreviewRoute ? (
          <Suspense
            fallback={
              <section className="empty" role="status">
                Loading development preview…
              </section>
            }
          >
            <PreviewWorkspace path={path} />
          </Suspense>
        ) : sessionBusy ? (
          <section className="empty" role="status">
            <h2>
              {logoutState.loading ? "Signing out" : "Checking your session"}
            </h2>
            <p>Please wait while your account session is verified.</p>
            {!logoutState.loading && <PreviewOptions both />}
          </section>
        ) : (
          <>
            <Feedback error={error} />
            {error && (
              <button className="secondary auth-retry" onClick={retry}>
                Retry session check
              </button>
            )}
            {!Object.hasOwn(routes, path) && (
              <section className="empty">
                <h2>Page not found</h2>
                <a href="#/">Return home</a>
              </section>
            )}
            {path === "/" && <LandingPage />}
            {path === "/roles" && <RoleSelection />}
            {!user && path === "/citizen/sign-in" && (
              <AuthPage key={path} />
            )}{" "}
            {!user && path === "/citizen/sign-up" && (
              <AuthPage key={path} registering />
            )}
            {!user && path === "/hospital/sign-in" && (
              <AuthPage key={path} staff />
            )}
            {!user && path === "/hospital/request-access" && (
              <AuthPage key={path} staff registering />
            )}
            {protectedRoute && !allowed && (
              <section className="empty" role="status">
                <p>
                  {user
                    ? "This workspace is not available for your role."
                    : "Sign in to open your workspace."}
                </p>
              </section>
            )}
            {allowed && user && path === "/citizen" && <CitizenPortal />}
            {allowed && staff && path === "/hospital" && (
              <HospitalCommandCenter
                hospitalId={hospitalId}
                setHospitalId={setHospitalId}
                assignedHospitalId={String(user.hospital_id)}
                openRequest={(id) => {
                  setHospitalId(id);
                  navigate("/request");
                }}
              />
            )}{" "}
            {allowed && staff && path === "/request" && (
              <EmergencyRequest
                hospitalId={hospitalId}
                setHospitalId={setHospitalId}
                assignedHospitalId={String(user.hospital_id)}
              />
            )}
          </>
        )}
      </main>
      <footer>
        <span>Centralized Blood Bank & Emergency Routing</span>
        <span>Blood availability enquiries · Hospital coordination</span>
      </footer>
    </>
  );
}
