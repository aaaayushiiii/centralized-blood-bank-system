import test from "node:test";
import assert from "node:assert/strict";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { createServer } from "vite";
import { createAuthClient, validateSession } from "../src/services/authClient.js";
import { createAdminReviewClient, approvalPayload, rejectionPayload, reviewError } from "../src/services/adminReview.js";
import { canAccess, roleHome } from "../src/auth/routes.js";
import { setCsrfToken, getCsrfToken } from "../src/services/sessionSecurity.js";

const admin = { user_id: 77, full_name: "Synthetic Reviewer", email: "reviewer@example.test", role: "ADMIN", status: "ACTIVE" };
const application = { registration_request_id: 700001, hospital_name: "Synthetic Hospital", status: "PENDING", district: "Synthetic District", registration_authority: "TEST_AUTHORITY", jurisdiction_code: "TEST_ONLY", registration_number: "AB-123", registration_key: "AB123", representative_email: "rep@example.test" };
const token = "a".repeat(64), detail = { application, review_token: token };
const approval = () => ({});
const rejection = () => ({ rejection_reason: "Not independently verified" });
const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
async function fetchStub(handler, run) { const old = globalThis.fetch; try { globalThis.fetch = handler; await run(); } finally { globalThis.fetch = old; setCsrfToken(""); } }

test("admin login, cookie session authority and route isolation", async (t) => {
  await t.test("only ACTIVE stored ADMIN session can establish admin UI", () => {
    assert.equal(validateSession({ user: admin, csrf_token: "csrf" }).user.role, "ADMIN");
    assert.throws(() => validateSession({ user: { ...admin, status: "INACTIVE" }, csrf_token: "csrf" }));
    for (const user of [null, { ...admin, role: "CITIZEN" }, { ...admin, role: "HOSPITAL_STAFF" }]) assert.equal(canAccess("/admin", user), false);
    assert.equal(canAccess("/admin", admin), true); assert.equal(roleHome(admin), "/admin");
    assert.equal(canAccess("/citizen", admin), false); assert.equal(canAccess("/hospital", admin), false);
  });
  await t.test("admin login uses existing CSRF, cookies and subsequent /me", async () => {
    const calls = []; setCsrfToken("guest");
    await fetchStub(async (url, options) => { calls.push({ url, options }); return json(url.endsWith("/login") ? { csrf_token: "rotated" } : { user: admin, csrf_token: "rotated" }); }, async () => {
      const client = createAuthClient("http://localhost/api");
      assert.equal((await client.login({ email: admin.email, password: "synthetic-test-only", role: "ADMIN" })).user.role, "ADMIN");
      assert.deepEqual(calls.map((c) => new URL(c.url).pathname), ["/api/auth/login", "/api/auth/me"]);
      assert.equal(calls[0].options.headers["X-CSRF-Token"], "guest"); assert.equal(getCsrfToken(), "rotated");
      for (const c of calls) assert.equal(c.options.credentials, "include");
    });
  });
  await t.test("citizen response cannot authenticate requested ADMIN", async () => {
    setCsrfToken("guest");
    await fetchStub(async (url) => json(url.endsWith("/login") ? { csrf_token: "new" } : { user: { ...admin, role: "CITIZEN" }, csrf_token: "new" }), async () => {
      await assert.rejects(createAuthClient("http://localhost/api").login({ role: "ADMIN" }), /selected role/);
    });
  });
  await t.test("logout clears memory CSRF only after confirmed server logout", async () => {
    setCsrfToken("admin-csrf");
    await fetchStub(async (url, options) => { assert.match(url, /auth\/logout$/); assert.equal(options.headers["X-CSRF-Token"], "admin-csrf"); return json({ status: "LOGGED_OUT" }); }, async () => {
      await createAuthClient("http://localhost/api").logout(); assert.equal(getCsrfToken(), "");
    });
  });
});

test("local demo payloads match simplified backend contracts", async (t) => {
  await t.test("approval requires only token and confirmation and never sends browser audit metadata", () => {
    assert.deepEqual(approvalPayload(application, token, { status: "APPROVED", reviewed_by: "forged", approved_hospital_id: 9, verification_notes: "forged" }), { review_token: token, confirmation: "APPROVE 700001" });
  });
  await t.test("missing token, invalid reference and terminal states are still refused", () => {
    for (const value of ["", undefined, "bad"]) assert.throws(() => approvalPayload(application, value));
    for (const status of ["APPROVED", "REJECTED"]) assert.throws(() => approvalPayload({ ...application, status }, token));
    assert.throws(() => approvalPayload({ ...application, registration_request_id: -1 }, token));
  });
  await t.test("rejection sends reason only alongside token/confirmation; notes are server owned", () => {
    assert.deepEqual(rejectionPayload(application, token, { ...rejection(), verification_notes: "forged", reviewed_by: "forged" }), { review_token: token, confirmation: "REJECT 700001", rejection_reason: "Not independently verified" });
    for (const value of ["", " ", "a".repeat(1001), "\u00e9".repeat(501), "bad\nreason"]) assert.throws(() => rejectionPayload(application, token, { rejection_reason: value }));
  });
});

test("review transport, pagination, CSRF and safe failure handling", async (t) => {
  const api = createAdminReviewClient("http://localhost/api");
  await t.test("session guard rejects ordinary roles and restores server CSRF", async () => {
    for (const role of ["CITIZEN", "HOSPITAL_STAFF", "ADMIN"]) {
      await fetchStub(async () => json({ user: { ...admin, role, hospital_id: 1 }, csrf_token: "secured" }), async () => {
        if (role === "ADMIN") { assert.equal((await api.session()).role, "ADMIN"); assert.equal(getCsrfToken(), "secured"); }
        else await assert.rejects(api.session(), { status: 403 });
      });
    }
  });
  await t.test("all status filters, fixed limit, cursor and detail path", async () => {
    const calls = [];
    await fetchStub(async (url, options) => { calls.push({ url, options }); const q = new URL(url).searchParams; return json(url.endsWith("/700001") ? detail : { applications: [], status: q.get("status"), limit: 20, next_after_id: null }); }, async () => {
      for (const status of ["PENDING", "APPROVED", "REJECTED"]) await api.list(status, 123);
      assert.equal((await api.detail(700001)).review_token, token);
      for (const call of calls.slice(0, 3)) { const q = new URL(call.url).searchParams; assert.equal(q.get("after_id"), "123"); assert.equal(q.get("limit"), "20"); assert.equal(call.options.credentials, "include"); }
      await assert.rejects(api.list("INVALID")); await assert.rejects(api.list("PENDING", -1));
    });
  });
  await t.test("approve/reject make one exact POST with cookies and current CSRF", async () => {
    for (const action of ["approve", "reject"]) {
      setCsrfToken("current-admin-csrf"); let calls = 0;
      await fetchStub(async (url, options) => {
        calls++; assert.match(url, new RegExp(`/700001/${action}$`)); assert.equal(options.method, "POST"); assert.equal(options.credentials, "include"); assert.equal(options.headers["X-CSRF-Token"], "current-admin-csrf");
        assert.deepEqual(JSON.parse(options.body), action === "approve" ? approvalPayload(application, token, approval()) : rejectionPayload(application, token, rejection()));
        return json({ registration_request_id: 700001, status: action === "approve" ? "APPROVED" : "REJECTED", ...(action === "approve" ? { approved_hospital_id: 800001 } : {}) });
      }, async () => { await api.decide(application, token, action, action === "approve" ? approval() : rejection()); assert.equal(calls, 1); });
    }
  });
  await t.test("missing CSRF blocks the POST without guest bootstrap or replay", async () => {
    await fetchStub(() => { throw new Error("Must not fetch"); }, async () => { await assert.rejects(api.decide(application, token, "reject", rejection()), { code: "CSRF_INVALID" }); });
  });
  await t.test("stale tokens, duplicate conflicts, disabled policy and SQL failure display safe fixed messages", async () => {
    for (const [status, code, message] of [[401, "SESSION_INVALID", /ended/], [403, "ADMIN_UNAVAILABLE", /local admin mode/], [403, "CSRF_INVALID", /security token/], [409, "REVIEW_STALE", /stale/], [409, "HOSPITAL_CONFLICT", /conflicts/], [503, "DATABASE_UNAVAILABLE", /could not be confirmed/]]) {
      let calls = 0; setCsrfToken("csrf");
      await fetchStub(async () => { calls++; return json({ error: { code, message: "private SQL must not display" } }, status); }, async () => {
        await assert.rejects(api.decide(application, token, "approve", approval()), (error) => { assert.match(reviewError(error, true), message); assert.doesNotMatch(reviewError(error, true), /private SQL/); return true; }); assert.equal(calls, 1);
      });
    }
  });
  await t.test("network and malformed success are uncertain and never replayed", async () => {
    for (const handler of [async () => { throw new TypeError("offline"); }, async () => json({ status: "APPROVED" })]) {
      let calls = 0; setCsrfToken("csrf");
      await fetchStub(async (...args) => { calls++; return handler(...args); }, async () => {
        await assert.rejects(api.decide(application, token, "approve", approval()), (error) => { assert.match(reviewError(error, true), /Reload the application/); return true; }); assert.equal(calls, 1);
      });
    }
  });
});

function find(node, predicate) {
  if (!node || typeof node !== "object") return null;
  if (predicate(node)) return node;
  for (const child of React.Children.toArray(node.props?.children)) { const match = find(child, predicate); if (match) return match; }
  return null;
}
function hooks() {
  const slots = []; let index = 0, effects = [];
  return {
    useState(initial) { const key = index++; slots[key] ??= { value: typeof initial === "function" ? initial() : initial }; return [slots[key].value, (next) => { slots[key].value = typeof next === "function" ? next(slots[key].value) : next; }]; },
    useRef(initial) { const key = index++; slots[key] ??= { current: initial }; return slots[key]; },
    useEffect(effect, deps) { const key = index++; if (!slots[key] || deps.some((v, i) => !Object.is(v, slots[key].deps[i]))) { slots[key]?.cleanup?.(); slots[key] = { deps }; effects.push(() => { slots[key].cleanup = effect(); }); } },
    render(component) { index = 0; const tree = component(); const pending = effects; effects = []; pending.forEach((effect) => effect()); return tree; },
    unmount() { slots.forEach((slot) => slot?.cleanup?.()); },
  };
}
const tick = () => new Promise((resolve) => setImmediate(resolve));
function deferred() { let resolve; const promise = new Promise((yes) => { resolve = yes; }); return { promise, resolve }; }

test("React administrator screens and decision lifecycle", async (t) => {
  const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: "custom", envDir: false,
    plugins: [{ name: "admin-test-hooks", enforce: "pre", resolveId(id) { if (id.startsWith("virtual:admin-")) return "\0" + id; }, load(id) {
      if (id === "\0virtual:admin-hooks") return 'export {createContext, useContext} from "react";\n' + ["useState", "useRef", "useEffect"].map((name) => `export const ${name} = (...args) => globalThis.__adminHooks.${name}(...args);`).join("\n");
      if (id === "\0virtual:admin-auth") return "export const useAuth = () => globalThis.__adminAuth;";
      if (id === "\0virtual:admin-auth-service") return "export const auth = { getSession: (...args) => globalThis.__adminAuthApi.getSession(...args), logout: (...args) => globalThis.__adminAuthApi.logout(...args) };";
    }, transform(code, id) { const path = id.replaceAll("\\", "/"); if (path.endsWith("/src/auth/AuthContext.jsx")) return code.replace('from "react"', 'from "virtual:admin-hooks"').replace('from "../services/auth.js"', 'from "virtual:admin-auth-service"'); if (["/src/pages/AdminDashboard.jsx", "/src/pages/AuthPage.jsx", "/src/App.jsx"].some((v) => path.endsWith(v))) return code.replace('from "react"', 'from "virtual:admin-hooks"').replace(/from "(?:\.\.\/|\.\/)auth\/AuthContext.jsx"/, 'from "virtual:admin-auth"'); } }],
  });
  const saved = { window: globalThis.window, FormData: globalThis.FormData, hooks: globalThis.__adminHooks, auth: globalThis.__adminAuth };
  try {
    const { default: Dashboard, ReviewForm } = await server.ssrLoadModule("/src/pages/AdminDashboard.jsx");
    const { default: Login } = await server.ssrLoadModule("/src/pages/AuthPage.jsx");
    const { default: Roles } = await server.ssrLoadModule("/src/pages/RoleSelection.jsx");
    const { default: App } = await server.ssrLoadModule("/src/App.jsx");
    const { AuthProvider } = await server.ssrLoadModule("/src/auth/AuthContext.jsx");
    const ssrSecurity = await server.ssrLoadModule("/src/services/sessionSecurity.js");
    globalThis.window = { location: { hash: "#/admin" }, addEventListener() {}, removeEventListener() {}, history: { replaceState() {} }, dispatchEvent() {} };
    globalThis.FormData = class { constructor(form) { this.form = form; } [Symbol.iterator]() { return Object.entries(this.form.values)[Symbol.iterator](); } };
    function mount(Component, props) { const h = hooks(); globalThis.__adminHooks = h; return { h, render: () => { globalThis.__adminHooks = h; return h.render(() => Component(props)); } }; }
    await t.test("admin expiry clears React identity and CSRF at the 30-minute UI upper bound", async () => {
      const previousWindow = globalThis.window, previousSet = globalThis.setTimeout, previousClear = globalThis.clearTimeout;
      const listeners = new Map(), timers = [];
      globalThis.window = { addEventListener: (name, fn) => listeners.set(name, fn), removeEventListener: (name) => listeners.delete(name), dispatchEvent: (event) => listeners.get(event.type)?.() };
      globalThis.setTimeout = (callback, delay) => { timers.push({ callback, delay }); return 1; }; globalThis.clearTimeout = () => {};
      globalThis.__adminAuthApi = { getSession: async () => ({ user: admin }) };
      const m = mount(AuthProvider, {});
      try {
        m.render(); await tick(); assert.equal(m.render().props.value.user.role, "ADMIN");
        assert.equal(timers.length, 1); assert.equal(timers[0].delay, 30 * 60 * 1000);
        ssrSecurity.setCsrfToken("memory-only"); timers[0].callback(); const value = m.render().props.value;
        assert.equal(value.user, null); assert.equal(ssrSecurity.getCsrfToken(), ""); assert.match(value.error, /session has ended/);
      } finally { m.h.unmount(); globalThis.window = previousWindow; globalThis.setTimeout = previousSet; globalThis.clearTimeout = previousClear; delete globalThis.__adminAuthApi; }
    });
    await t.test("provider logout clears administrator state only on server success", async () => {
      let rejectLogout = true, guest = false;
      globalThis.__adminAuthApi = { getSession: async () => ({ user: guest ? null : admin }), logout: async () => { if (rejectLogout) throw new Error("offline"); guest = true; } };
      const m = mount(AuthProvider, {});
      try {
        m.render(); await tick(); let value = m.render().props.value;
        await assert.rejects(value.logout()); assert.equal(m.render().props.value.user.role, "ADMIN");
        rejectLogout = false; await value.logout(); assert.equal(m.render().props.value.user, null);
        await tick(); assert.equal(m.render().props.value.user, null);
      } finally { m.h.unmount(); delete globalThis.__adminAuthApi; }
    });
    await t.test("separate admin entry and login has no signup or privileged fields", () => {
      assert.match(renderToStaticMarkup(React.createElement(Roles)), /Administrator Login/);
      globalThis.__adminAuth = { login() {} }; const m = mount(Login, { admin: true });
      const html = renderToStaticMarkup(m.render()); assert.match(html, /Administrator login/); assert.match(html, /30 minutes/);
      assert.doesNotMatch(html, /Need an account|name="(?:role|reviewed_by)"/); m.h.unmount();
    });
    await t.test("login submits ADMIN, clears password, and blocks simultaneous clicks", async () => {
      const wait = deferred(); let calls = 0;
      globalThis.__adminAuth = { login: async (body) => { calls++; assert.equal(body.role, "ADMIN"); return wait.promise; } };
      const m = mount(Login, { admin: true }), form = { values: { email: admin.email, password: "synthetic" }, elements: { password: { value: "synthetic" } }, reset() {} };
      const handler = find(m.render(), (v) => v.type === "form").props.onSubmit;
      const pending = handler({ preventDefault() {}, currentTarget: form }); await handler({ preventDefault() {}, currentTarget: form }); assert.equal(calls, 1);
      wait.resolve(admin); await pending; assert.equal(form.elements.password.value, ""); m.h.unmount();
    });
    await t.test("login displays safe disabled-mode and invalid-credential messages", async () => {
      for (const status of [401, 403]) {
        globalThis.__adminAuth = { login: async () => { throw Object.assign(new Error("SQL secret"), { status }); } };
        const m = mount(Login, { admin: true }), form = { values: { email: admin.email, password: "synthetic" }, elements: {} };
        await find(m.render(), (v) => v.type === "form").props.onSubmit({ preventDefault() {}, currentTarget: form });
        const html = renderToStaticMarkup(m.render()); assert.doesNotMatch(html, /SQL secret/); assert.match(html, status === 403 ? /Local admin mode/ : /credentials/); m.h.unmount();
      }
    });
    await t.test("App never mounts dashboard for guest, citizen or staff", () => {
      for (const user of [null, { ...admin, role: "CITIZEN" }, { ...admin, role: "HOSPITAL_STAFF", hospital_id: 1 }, admin]) {
        globalThis.window.location.hash = "#/admin";
        globalThis.__adminAuth = { user, loading: false, error: "", retry() {}, logout() {} };
        const m = mount(App, {}); const node = find(m.render(), (v) => v.type?.name === "AdminDashboard"); assert.equal(Boolean(node), user?.role === "ADMIN"); m.h.unmount();
      }
    });
    await t.test("dashboard loads real API contract, status filters and cursor pages", async () => {
      const calls = [], api = { session: async () => admin, list: async (status, after) => { calls.push([status, after]); return { applications: [{ ...application, registration_request_id: after ? 700002 : 700001, status }], next_after_id: after ? null : 700001 }; } };
      const m = mount(Dashboard, { reviewApi: api }); m.render(); await tick();
      await find(m.render(), (v) => v.type === "button" && v.props.children === "Load more applications").props.onClick();
      assert.deepEqual(calls, [["PENDING", 0], ["PENDING", 700001]]);
      find(m.render(), (v) => v.type === "button" && v.props.children === "Approved").props.onClick(); m.render(); await tick(); assert.deepEqual(calls.at(-1), ["APPROVED", 0]);
      assert.match(renderToStaticMarkup(m.render()), /Synthetic Hospital/); m.h.unmount();
    });
    await t.test("late filter/detail responses cannot overwrite a newer selection", async () => {
      const first = deferred(); const api = { session: async () => admin, list: async (status) => status === "PENDING" ? first.promise : { applications: [], next_after_id: null } };
      const m = mount(Dashboard, { reviewApi: api }); m.render(); await tick();
      find(m.render(), (v) => v.type === "button" && v.props.children === "Rejected").props.onClick(); m.render(); await tick();
      first.resolve({ applications: [application], next_after_id: null }); await tick();
      assert.doesNotMatch(renderToStaticMarkup(m.render()), /Synthetic Hospital/); m.h.unmount();
    });
    await t.test("detail fetch retains token in component memory and success refreshes the queue", async () => {
      let reads = 0; const api = { session: async () => admin, list: async () => { reads++; return { applications: [application], next_after_id: null }; }, detail: async (reference) => { assert.equal(reference, 700001); return detail; } };
      const m = mount(Dashboard, { reviewApi: api }); m.render(); await tick();
      await find(m.render(), (v) => v.type === "button" && v.props["aria-pressed"] === false && find(v, (x) => x.type === "strong")).props.onClick();
      const review = find(m.render(), (v) => v.type?.name === "ReviewForm"); assert.equal(review.props.detail.review_token, token);
      review.props.onSuccess({ registration_request_id: 700001, status: "APPROVED", approved_hospital_id: 800001 }); m.render(); await tick();
      assert.equal(reads, 2); assert.equal(find(m.render(), (v) => v.type?.name === "ReviewForm"), null); assert.match(renderToStaticMarkup(m.render()), /active hospital #800001/); m.h.unmount();
    });
    function reviewMount(api, values = approval(), onSuccess = () => {}) {
      let busy = false;
      const m = mount(ReviewForm, { detail, api, onSuccess, onBusy: (v) => { busy = v; } });
      const form = { values, elements: { namedItem: (name) => ({ checked: values[name] === true }) } };
      return { ...m, busy: () => busy, prepare: () => find(m.render(), (v) => v.type === "form").props.onSubmit({ preventDefault() {}, currentTarget: form }) };
    }
    await t.test("official HFR link is accessible and explains manual verification limits", () => {
      const m = reviewMount({ decide() { throw new Error("Must not submit"); } });
      const tree = m.render();
      const link = find(tree, (v) => v.type === "a" && v.props.href === "https://facility.abdm.gov.in/");
      assert.ok(link); assert.equal(link.props.target, "_blank"); assert.equal(link.props.rel, "noopener noreferrer");
      assert.match(link.props.children, /Official ABDM Health Facility Registry.*optional information, opens in a new tab/);
      const html = renderToStaticMarkup(tree);
      assert.match(html, /Official government verification is not performed/);
      assert.match(html, /HFR identity alone does not verify the representative&#x27;s authorization or establish blood-bank licensing/);
      assert.doesNotMatch(html, /type="checkbox"|name="verified_|name="registration_evidence"/);
      m.h.unmount();
    });
    await t.test("simple approval asks for confirmation, disclaims government verification, and prevents double POST", async () => {
      const wait = deferred(); let calls = 0, successes = 0;
      const m = reviewMount({ decide: async (_a, _t, action) => { calls++; assert.equal(action, "approve"); return wait.promise; } }, approval(), () => successes++);
      m.prepare(); assert.equal(calls, 0); const html = renderToStaticMarkup(m.render()); assert.match(html, /Approve this hospital\?/); assert.match(html, /official government verification not performed/); assert.match(html, /cannot be undone/);
      const confirm = find(m.render(), (v) => v.type === "button" && v.props.children === "Confirm final decision").props.onClick;
      const pending = confirm(); await confirm(); assert.equal(calls, 1); assert.equal(m.busy(), true);
      wait.resolve({ registration_request_id: 700001, status: "APPROVED", approved_hospital_id: 800001 }); await pending; assert.equal(successes, 1); assert.equal(m.busy(), false); m.h.unmount();
    });
    await t.test("approval needs no identity, checklist, evidence or typed phrase; Cancel sends nothing", () => {
      let calls = 0; const m = reviewMount({ decide() { calls++; } });
      let html = renderToStaticMarkup(m.render()); assert.match(html, />Approve<\/button>/);
      assert.doesNotMatch(html, /name="(?:verified_|registration_evidence|confirmation|review_context)|type="checkbox"/);
      m.prepare(); find(m.render(), (v) => v.type === "button" && v.props.children === "Cancel").props.onClick();
      assert.equal(calls, 0); assert.ok(find(m.render(), (v) => v.type === "form")); m.h.unmount();
    });
    await t.test("stale/uncertain decisions disable replay and require details reload", async () => {
      for (const error of [Object.assign(new Error("stale"), { status: 409, code: "REVIEW_STALE" }), new Error("offline")]) {
        let calls = 0; const m = reviewMount({ decide: async () => { calls++; throw error; } }); m.prepare();
        await find(m.render(), (v) => v.type === "button" && v.props.children === "Confirm final decision").props.onClick();
        assert.equal(calls, 1); assert.match(renderToStaticMarkup(m.render()), /Reload application details/); assert.equal(find(m.render(), (v) => v.type === "form"), null); m.h.unmount();
      }
    });
    await t.test("rejection requires its own confirmation and sends rejection only", async () => {
      let action; const values = rejection(); const m = reviewMount({ decide: async (_a, _t, value) => { action = value; return { status: "REJECTED" }; } }, values);
      find(m.render(), (v) => v.type === "button" && v.props.type === "button" && v.props.children === "Reject").props.onClick();
      m.prepare(); assert.match(renderToStaticMarkup(m.render()), /without creating a hospital/);
      await find(m.render(), (v) => v.type === "button" && v.props.children === "Confirm final decision").props.onClick(); assert.equal(action, "reject"); m.h.unmount();
    });
    await t.test("approved/rejected records have no decision form", () => {
      for (const status of ["APPROVED", "REJECTED"]) { const m = mount(ReviewForm, { detail: { ...detail, application: { ...application, status } } }); assert.equal(find(m.render(), (v) => v.type === "form"), null); m.h.unmount(); }
    });
    await t.test("unmount during decision cannot update an expired workspace", async () => {
      const wait = deferred(); let calls = 0; const m = reviewMount({ decide: () => wait.promise }, approval(), () => calls++); m.prepare();
      const pending = find(m.render(), (v) => v.type === "button" && v.props.children === "Confirm final decision").props.onClick(); m.h.unmount(); wait.resolve({ status: "APPROVED" }); await pending; assert.equal(calls, 0);
    });
  } finally {
    globalThis.window = saved.window; globalThis.FormData = saved.FormData; globalThis.__adminHooks = saved.hooks; globalThis.__adminAuth = saved.auth; await server.close();
  }
});
