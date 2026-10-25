import test from "node:test";
import assert from "node:assert/strict";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { createServer } from "vite";
import { hospitalRegistrationFields, hospitalRegistrationPayload } from "../src/services/hospitalRegistrationValidation.js";
import { createHospitalRegistrationClient, registrationFailure } from "../src/services/hospitalRegistration.js";
import { getCsrfToken, setCsrfToken } from "../src/services/sessionSecurity.js";
import { canAccess, readRoute, routes } from "../src/auth/routes.js";
import { requestJson } from "../src/services/transport.js";

const input = () => ({
  hospital_name: " Test Hospital ", address: " 1 Test Road ", district: " Test District ", pincode: "600001",
  official_phone: "+91 90000 00001", official_email: " OFFICE@EXAMPLE.TEST ", registration_authority: " test_authority ",
  jurisdiction_code: " in_tn ", registration_number: " ab-12/3 ", representative_name: " Representative ",
  representative_title: " Coordinator ", representative_email: " REP@EXAMPLE.TEST ", representative_phone: "9000000002",
});
const response = (body, status = 201) => Response.json(body, { status });
const pending = { registration_request_id: 100000, status: "PENDING" };
async function stubFetch(handler, run) {
  const before = globalThis.fetch;
  try { globalThis.fetch = handler; await run(); }
  finally { globalThis.fetch = before; setCsrfToken(""); }
}

test("hospital application validation follows the Java contract", async (t) => {
  await t.test("exact allowlist, canonical identity, trimmed values and no privileged fields", () => {
    const body = hospitalRegistrationPayload({ ...input(), status: "APPROVED", reviewed_by: "attacker", approved_hospital_id: 42, password: "never-send" });
    assert.deepEqual(Object.keys(body).sort(), hospitalRegistrationFields.map(([key]) => key).sort());
    assert.equal(body.hospital_name, "Test Hospital");
    assert.equal(body.registration_authority, "TEST_AUTHORITY");
    assert.equal(body.jurisdiction_code, "IN_TN");
    assert.equal(body.registration_number, "AB123");
    assert.equal(body.official_email, "office@example.test");
    assert.equal(body.representative_email, "rep@example.test");
    assert.equal(body.representative_title, "Coordinator");
  });
  await t.test("all thirteen required fields, types and byte lengths", () => {
    for (const [name, , limit] of hospitalRegistrationFields) {
      for (const value of [undefined, null, " ", 12, "a".repeat(limit + 1)]) {
        assert.throws(() => hospitalRegistrationPayload({ ...input(), [name]: value }), (error) => error.field === name);
      }
    }
    assert.throws(() => hospitalRegistrationPayload({ ...input(), hospital_name: "é".repeat(76) }), /UTF-8 bytes/);
    assert.throws(() => hospitalRegistrationPayload({ ...input(), address: "\u2003\u00a0" }), /required/);
    assert.throws(() => hospitalRegistrationPayload({ ...input(), hospital_name: "bad\nname" }), /invalid characters/);
    assert.throws(() => hospitalRegistrationPayload({ ...input(), hospital_name: "\uD800" }), /invalid characters/);
  });
  await t.test("identity, pincode and contact formats reject invalid inputs", () => {
    for (const [name, value] of [
      ["pincode", "000001"], ["pincode", "12A456"], ["official_phone", "-------"],
      ["representative_phone", "1234567890123456"], ["official_email", "not-email"],
      ["representative_email", "rep @example.test"], ["registration_authority", "Test Authority"],
      ["jurisdiction_code", "IN-TN"], ["registration_number", "../-"], ["registration_number", "ＡＢ123"],
    ]) assert.throws(() => hospitalRegistrationPayload({ ...input(), [name]: value }), (error) => error.field === name);
  });
  await t.test("optional coordinates are omitted or emitted as paired numbers, with exact bounds and precision", () => {
    assert.equal("latitude" in hospitalRegistrationPayload({ ...input(), latitude: " ", longitude: "" }), false);
    let body = hospitalRegistrationPayload({ ...input(), latitude: "-90.000000", longitude: "180" });
    assert.equal(body.latitude, -90); assert.equal(body.longitude, 180);
    body = hospitalRegistrationPayload({ ...input(), latitude: "13.0826800", longitude: "80.270718" });
    assert.equal(body.latitude, 13.08268);
    for (const coords of [
      { latitude: "12", longitude: "" }, { latitude: "", longitude: "77" },
      { latitude: "90.000001", longitude: "77" }, { latitude: "12", longitude: "-180.000001" },
      { latitude: "12.1234567", longitude: "77" }, { latitude: "NaN", longitude: "77" },
      { latitude: "1e2", longitude: "77" }, { latitude: true, longitude: "77" },
    ]) assert.throws(() => hospitalRegistrationPayload({ ...input(), ...coords }));
  });
});

test("hospital application transport uses guest cookies, CSRF and the exact endpoint", async (t) => {
  const client = createHospitalRegistrationClient("http://localhost/api");
  await t.test("optional status check preserves default JSON and 204 transport behavior", async () => {
    await stubFetch(async () => new Response(null, { status: 204 }), async () => {
      assert.equal(await requestJson("http://localhost/api", "/synthetic"), null);
    });
    await stubFetch(async () => response({ value: "unchanged" }, 200), async () => {
      assert.deepEqual(await requestJson("http://localhost/api", "/synthetic"), { value: "unchanged" });
    });
  });
  await t.test("401 guest bootstrap followed by one allowlisted POST and exact 201/PENDING response", async () => {
    const calls = [];
    await stubFetch(async (url, options) => {
      calls.push({ url, options });
      return url.endsWith("/auth/me") ? response({ user: null, csrf_token: "guest-csrf" }, 401) : response(pending);
    }, async () => {
      const result = await client.submit({ ...input(), status: "APPROVED", approved_hospital_id: 99 });
      assert.deepEqual(result, pending); assert.equal(calls.length, 2);
      assert.equal(calls[0].url, "http://localhost/api/auth/me");
      assert.equal(calls[1].url, "http://localhost/api/hospital-registration-requests");
      assert.equal(calls[1].options.method, "POST");
      assert.equal(calls[1].options.headers["X-CSRF-Token"], "guest-csrf");
      for (const call of calls) assert.equal(call.options.credentials, "include");
      assert.deepEqual(JSON.parse(calls[1].options.body), hospitalRegistrationPayload(input()));
    });
  });
  await t.test("existing session token is reused, coordinates are numeric and no login request is sent", async () => {
    setCsrfToken("existing-session"); let calls = 0;
    await stubFetch(async (url, options) => {
      calls++; assert.equal(url, "http://localhost/api/hospital-registration-requests");
      assert.equal(options.headers["X-CSRF-Token"], "existing-session");
      assert.equal(typeof JSON.parse(options.body).latitude, "number"); return response(pending);
    }, async () => {
      await client.submit({ ...input(), latitude: "13.1", longitude: "80.1" }); assert.equal(calls, 1);
    });
  });
  await t.test("invalid fields and missing API configuration never issue requests", async () => {
    await stubFetch(() => { throw new Error("Unexpected request"); }, async () => {
      await assert.rejects(client.submit({}), /required/);
      await assert.rejects(createHospitalRegistrationClient("").submit(input()), /not configured/);
    });
  });
  await t.test("failed bootstrap is safely retryable because no POST was sent", async () => {
    let calls = 0;
    await stubFetch(async (url) => { calls++; assert.match(url, /auth\/me$/); throw new TypeError("offline"); }, async () => {
      await assert.rejects(client.submit(input()), (error) => {
        const failure = registrationFailure(error); assert.equal(failure.uncertain, false);
        assert.match(failure.error, /No application was sent/); return true;
      });
      assert.equal(calls, 1);
    });
  });
  await t.test("invalid success status, missing reference or nonpending response never confirms registration", async () => {
    for (const [body, status] of [[pending, 200], [{}, 201], [{ ...pending, status: "APPROVED" }, 201],
      [{ ...pending, registration_request_id: "100000" }, 201], [{ ...pending, registration_request_id: 0 }, 201],
      [{ ...pending, registration_request_id: 10000000000 }, 201]]) {
      setCsrfToken("token");
      await stubFetch(async () => response(body, status), async () => {
        await assert.rejects(client.submit(input()), (error) => registrationFailure(error).uncertain);
      });
    }
  });
  await t.test("400/409/429/503 failures preserve useful messages and do not retry POST", async () => {
    for (const status of [400, 409, 429, 503]) {
      let calls = 0; setCsrfToken("token");
      await stubFetch(async () => { calls++; return response({ error: { code: "ERROR", message: "Check district" } }, status); }, async () => {
        await assert.rejects(client.submit(input()), (error) => {
          const failure = registrationFailure(error); assert.equal(failure.uncertain, false);
          assert.equal(error.status, status);
          if (status === 409) assert.match(failure.error, /conflicts with an existing record/);
          if (status === 503) assert.match(failure.error, /unavailable/);
          return true;
        });
        assert.equal(calls, 1);
      });
    }
  });
  await t.test("CSRF expiry clears only the in-memory token; manual retry bootstraps without automatic POST replay", async () => {
    let calls = 0; setCsrfToken("expired");
    await stubFetch(async (url) => {
      calls++;
      if (calls === 1) return response({ error: { code: "CSRF_INVALID", message: "Refresh" } }, 403);
      return url.endsWith("/auth/me") ? response({ user: null, csrf_token: "fresh" }, 401) : response(pending);
    }, async () => {
      await assert.rejects(client.submit(input()), { status: 403 });
      assert.equal(calls, 1); assert.equal(getCsrfToken(), "");
      assert.deepEqual(await client.submit(input()), pending); assert.equal(calls, 3);
    });
  });
  await t.test("network failure after POST has unknown outcome and is not automatically replayed", async () => {
    setCsrfToken("token"); let calls = 0;
    await stubFetch(async () => { calls++; throw new TypeError("offline"); }, async () => {
      await assert.rejects(client.submit(input()), (error) => registrationFailure(error).uncertain);
      assert.equal(calls, 1);
    });
  });
});

function find(element, predicate) {
  if (!element || typeof element !== "object") return null;
  if (predicate(element)) return element;
  for (const child of React.Children.toArray(element.props?.children)) {
    const match = find(child, predicate); if (match) return match;
  }
  return null;
}
// Same dependency-free hook scheduler pattern as the existing frontend tests.
// It tests handlers/lifecycle decisions; it does not claim browser layout coverage.
function hooks() {
  const slots = []; let index = 0; let pendingEffects = [];
  return {
    useState(initial) { const key = index++; slots[key] ??= { value: initial }; return [slots[key].value, (next) => { slots[key].value = next; }]; },
    useRef(initial) { const key = index++; slots[key] ??= { current: initial }; return slots[key]; },
    useEffect(effect, deps) {
      const key = index++;
      if (!slots[key] || deps.some((value, i) => !Object.is(value, slots[key].deps[i]))) {
        slots[key]?.cleanup?.(); slots[key] = { deps }; pendingEffects.push(() => { slots[key].cleanup = effect(); });
      }
    },
    render(component) { index = 0; const tree = component(); const effects = pendingEffects; pendingEffects = []; effects.forEach((effect) => effect()); return tree; },
    unmount() { slots.forEach((slot) => slot?.cleanup?.()); },
  };
}
function deferred() { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }

test("React hospital form, navigation and lifecycle", async (t) => {
  const virtual = "\0hospital-registration-hooks";
  const server = await createServer({
    server: { middlewareMode: true, hmr: false }, appType: "custom", envDir: false,
    define: { "import.meta.env.VITE_API_BASE_URL": JSON.stringify("http://localhost/api") },
    plugins: [{
      name: "hospital-registration-test-hooks", enforce: "pre",
      resolveId(id) {
        if (id === "virtual:hospital-registration-hooks") return virtual;
        if (id === "virtual:hospital-registration-auth") return "\0hospital-registration-auth";
      },
      load(id) {
        if (id === virtual) return ["useState", "useEffect", "useRef"].map((name) => `export const ${name} = (...args) => globalThis.__registrationHooks.${name}(...args);`).join("\n");
        if (id === "\0hospital-registration-auth") return "export const useAuth = () => globalThis.__registrationAuth;";
      },
      transform(code, id) {
        const path = id.replaceAll("\\", "/");
        if (path.endsWith("/src/pages/HospitalRegistration.jsx")) return code.replace('from "react"', 'from "virtual:hospital-registration-hooks"');
        if (path.endsWith("/src/App.jsx")) return code.replace('from "./auth/AuthContext.jsx"', 'from "virtual:hospital-registration-auth"');
      },
    }],
  });
  const priorHooks = globalThis.__registrationHooks, priorAuth = globalThis.__registrationAuth, priorFormData = globalThis.FormData, priorWindow = globalThis.window;
  try {
    const { default: Component } = await server.ssrLoadModule("/src/pages/HospitalRegistration.jsx");
    const { default: RoleSelection } = await server.ssrLoadModule("/src/pages/RoleSelection.jsx");
    const { default: LandingPage } = await server.ssrLoadModule("/src/pages/LandingPage.jsx");
    const { default: App } = await server.ssrLoadModule("/src/App.jsx");
    globalThis.FormData = class { constructor(form) { this.form = form; } [Symbol.iterator]() { return Object.entries(this.form.values)[Symbol.iterator](); } };
    function mount(api, values = input()) {
      const h = hooks(); globalThis.__registrationHooks = h;
      const form = { values, resets: 0, focused: null, reset() { this.resets++; }, elements: { namedItem: (key) => ({ focus: () => { form.focused = key; } }) } };
      const render = () => h.render(() => Component({ registrationApi: api }));
      return { h, form, render, submit: () => find(render(), (v) => v.type === "form").props.onSubmit({ preventDefault() {}, currentTarget: form }) };
    }
    await t.test("public dedicated route resolves without redirecting or granting dashboard access", () => {
      globalThis.window = { location: { hash: "#/hospital/register" } };
      assert.equal(readRoute(), "/hospital/register"); assert.equal(routes[readRoute()], null);
      assert.equal(canAccess(readRoute(), null), true);
      assert.equal(canAccess(readRoute(), { role: "HOSPITAL_STAFF" }), true);
      assert.equal(canAccess("/hospital", null), false);
    });
    await t.test("App renders the dedicated route for guests and authenticated staff", () => {
      for (const user of [null, { role: "HOSPITAL_STAFF", full_name: "Test Staff", hospital_id: 90001 }]) {
        globalThis.window = { location: { hash: "#/hospital/register" } };
        globalThis.__registrationAuth = { user, loading: false, error: "", retry() {}, logout() {} };
        const h = hooks(); globalThis.__registrationHooks = h;
        const html = h.render(() => renderToStaticMarkup(React.createElement(App)));
        assert.match(html, /Register your hospital\./); assert.match(html, /name="representative_title"/);
        assert.doesNotMatch(html, /Page not found|This workspace is not available/); h.unmount();
      }
    });
    await t.test("home and role selection expose onboarding while preserving staff links", () => {
      for (const Component of [RoleSelection, LandingPage]) {
        const html = renderToStaticMarkup(React.createElement(Component));
        assert.match(html, /href="#\/hospital\/register"[^>]*>Register Your Hospital/);
        assert.match(html, /href="#\/hospital\/sign-in"/); assert.match(html, /href="#\/hospital\/request-access"/);
      }
    });
    await t.test("rendered form has every exact contract field and no approval or password input", () => {
      const m = mount({ submit: () => { throw new Error("not submitted"); } });
      const html = renderToStaticMarkup(m.render());
      for (const [name] of hospitalRegistrationFields) assert.match(html, new RegExp(`name="${name}"`));
      for (const name of ["latitude", "longitude"]) assert.match(html, new RegExp(`name="${name}"`));
      assert.doesNotMatch(html, /name="(?:status|reviewed_by|approved_hospital_id|password)"/);
      assert.match(html, /does not activate your hospital or create staff login credentials/);
      assert.match(html, /<legend>Official registration identity<\/legend>/); m.h.unmount();
    });
    await t.test("invalid submission focuses the field and never calls the API", async () => {
      let calls = 0; const m = mount({ submit: () => { calls++; } }, { ...input(), pincode: "000000" });
      await m.submit(); assert.equal(calls, 0); assert.equal(m.form.focused, "pincode");
      assert.match(renderToStaticMarkup(m.render()), /six-digit pincode/); m.h.unmount();
    });
    await t.test("pending request blocks repeated submits, shows reference and prevents resubmission after success", async () => {
      let calls = 0; const request = deferred();
      const m = mount({ submit: async (body) => { calls++; assert.deepEqual(body, hospitalRegistrationPayload(input())); return request.promise; } });
      const originalHandler = find(m.render(), (v) => v.type === "form").props.onSubmit;
      const first = m.submit(); await m.submit();
      assert.equal(calls, 1); assert.match(renderToStaticMarkup(m.render()), /disabled=""/);
      request.resolve(pending); await first;
      const html = renderToStaticMarkup(m.render());
      assert.match(html, /Pending administrator approval/); assert.match(html, /100000/);
      assert.match(html, /no staff login credentials have been created/);
      assert.doesNotMatch(html, /<form/); assert.equal(m.form.resets, 1);
      await originalHandler({ preventDefault() {}, currentTarget: m.form }); assert.equal(calls, 1); m.h.unmount();
    });
    await t.test("duplicate, validation and unavailable failures retain form values and allow deliberate retry", async () => {
      for (const status of [400, 409, 429, 503]) {
        let calls = 0;
        const m = mount({ submit: async () => { calls++; if (calls === 1) throw Object.assign(new Error("Check district"), { status }); return pending; } });
        await m.submit(); const html = renderToStaticMarkup(m.render());
        assert.match(html, /role="alert"/); assert.match(html, /<form/); assert.equal(m.form.resets, 0);
        assert.deepEqual(m.form.values, input()); await m.submit(); assert.equal(calls, 2);
        assert.match(renderToStaticMarkup(m.render()), /100000/); m.h.unmount();
      }
    });
    await t.test("unknown result disables further submission without claiming success", async () => {
      let calls = 0; const m = mount({ submit: async () => { calls++; throw new Error("network failed"); } });
      await m.submit(); await m.submit(); const html = renderToStaticMarkup(m.render());
      assert.equal(calls, 1); assert.match(html, /could not confirm whether/); assert.match(html, /disabled=""/);
      assert.doesNotMatch(html, /Registration reference:/); assert.equal(m.form.resets, 0); m.h.unmount();
    });
    await t.test("navigation away during submission does not update an unmounted form", async () => {
      const request = deferred(); const m = mount({ submit: () => request.promise });
      const first = m.submit(); m.h.unmount(); request.resolve(pending); await first;
      assert.equal(m.form.resets, 0);
    });
  } finally {
    if (priorHooks === undefined) delete globalThis.__registrationHooks; else globalThis.__registrationHooks = priorHooks;
    if (priorAuth === undefined) delete globalThis.__registrationAuth; else globalThis.__registrationAuth = priorAuth;
    globalThis.FormData = priorFormData;
    if (priorWindow === undefined) delete globalThis.window; else globalThis.window = priorWindow;
    await server.close();
  }
});
