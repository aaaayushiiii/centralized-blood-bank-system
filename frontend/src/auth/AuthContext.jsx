import { createContext, useContext, useEffect, useRef, useState } from "react";
import { auth } from "../services/auth.js";
import { setCsrfToken } from "../services/sessionSecurity.js";
const AuthContext = createContext(null);
export function AuthProvider({ children }) {
  const [state, setState] = useState({ user: null, loading: true, error: "" });
  const version = useRef(0);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    const current = ++version.current;
    setState({ user: null, loading: true, error: "" });
    auth
      .getSession(controller.signal)
      .then((session) => {
        if (!controller.signal.aborted && version.current === current)
          setState({ user: session.user, loading: false, error: "" });
      })
      .catch((error) => {
        if (!controller.signal.aborted && version.current === current)
          setState({ user: null, loading: false, error: error.message });
      });
    return () => controller.abort();
  }, [attempt]);
  useEffect(() => {
    const expire = () => {
      ++version.current;
      setCsrfToken("");
      setState({
        user: null,
        loading: false,
        error: "Your session has ended. Please sign in again.",
      });
    };
    window.addEventListener("auth:expired", expire);
    return () => window.removeEventListener("auth:expired", expire);
  }, []);
  useEffect(() => {
    if (state.user?.role !== "ADMIN") return;
    // Upper bound for this UI instance; Oracle-backed requests remain authoritative
    // for sessions restored partway through their absolute 30-minute lifetime.
    const timer = setTimeout(() => window.dispatchEvent(new Event("auth:expired")), 30 * 60 * 1000);
    return () => clearTimeout(timer);
  }, [state.user]);
  async function login(body) {
    const current = ++version.current;
    const session = await auth.login(body);
    if (current !== version.current)
      throw new Error("The session changed. Please sign in again.");
    setState({ user: session.user, loading: false, error: "" });
    return session.user;
  }
  async function logout() {
    ++version.current;
    // Keep session state on a failed logout; do not claim the server ended it.
    await auth.logout();
    setState({ user: null, loading: false, error: "" });
    setAttempt((n) => n + 1); // bootstrap a new guest CSRF token
  }
  return (
    <AuthContext.Provider
      value={{ ...state, login, logout, retry: () => setAttempt((n) => n + 1) }}
    >
      {children}
    </AuthContext.Provider>
  );
}
export function useAuth() {
  return useContext(AuthContext);
}
