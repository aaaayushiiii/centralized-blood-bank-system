import test from "node:test";
import assert from "node:assert/strict";
import { createServer } from "vite";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";

// Synthetic contract fixtures, never runtime data or evidence of live Oracle integration.
const hospital = { hospital_id: 90001, name: "Contract Test Hospital", address: "Test Address", phone: null };
const row = { hospital, distance_km: 1.25, available_units: 2 };
const tick = () => new Promise((resolve) => setImmediate(resolve));
function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
function find(element, predicate) {
  if (!element || typeof element !== "object") return null;
  if (predicate(element)) return element;
  for (const child of React.Children.toArray(element.props?.children)) {
    const result = find(child, predicate);
    if (result) return result;
  }
  return null;
}
// Test-only hook scheduler. Exercises lifecycle decisions without pretending to be a browser.
function hooks() {
  const slots = [];let index = 0;let pending = [];
  return {
    useState(initial) {
      const key = index++;
      if (!slots[key]) slots[key] = { value: initial };
      return [slots[key].value, (next) => { slots[key].value = typeof next === "function" ? next(slots[key].value) : next; }];
    },
    useRef(initial) {
      const key = index++;
      if (!slots[key]) slots[key] = { current: initial };
      return slots[key];
    },
    useEffect(effect, deps) {
      const key = index++;
      if (!slots[key] || deps.some((v, i) => !Object.is(v, slots[key].deps[i]))) {
        slots[key]?.cleanup?.();slots[key] = { deps };
        pending.push(() => { slots[key].cleanup = effect(); });
      }
    },
    render(component) {
      index = 0;const result = component();const effects = pending;pending = [];
      effects.forEach((effect) => effect());return result;
    },
    unmount() { slots.forEach((slot) => slot?.cleanup?.()); },
  };
}

test("hospital GET response contracts and transport failures", async (t) => {
  const server = await createServer({
    server: { middlewareMode: true, hmr: false }, appType: "custom", envDir: false,
    define: {
      "import.meta.env.VITE_API_BASE_URL": JSON.stringify("http://127.0.0.1:8080/api"),
      "import.meta.env.VITE_API_MODE": JSON.stringify("live"),
    },
  });
  const original = globalThis.fetch;
  try {
    const { api } = await server.ssrLoadModule("/src/services/api.js");
    await t.test("numeric IDs and nullable phone are preserved; empty results stay empty", async () => {
      globalThis.fetch = async () => Response.json([hospital, { ...hospital, hospital_id: 90007 }]);
      assert.deepEqual((await api.getHospitals()).map((h) => h.hospital_id), [90001, 90007]);
      globalThis.fetch = async () => Response.json([]);assert.deepEqual(await api.getHospitals(), []);
      globalThis.fetch = async () => Response.json({ hospitals: [] });assert.deepEqual(await api.searchHospitals({ pincode: "632001" }), { hospitals: [] });
    });
    await t.test("invalid records and duplicate IDs cannot become selectable hospitals", async () => {
      for (const value of [null, {}, { ...hospital, hospital_id: 0 }, { ...hospital, hospital_id: "H-DEMO-01" }, { ...hospital, name: " " }, { ...hospital, address: null }, { ...hospital, phone: 123 }]) {
        globalThis.fetch = async () => Response.json([value]);await assert.rejects(api.getHospitals(), /Unexpected API response/);
      }
      globalThis.fetch = async () => Response.json([hospital, { ...hospital, hospital_id: "90001" }]);
      await assert.rejects(api.getHospitals(), /duplicate hospital ID/);
    });
    await t.test("missing or invalid availability is rejected, never invented", async () => {
      for (const value of [null, { ...row, available_units: undefined }, { ...row, available_units: -1 }, { ...row, available_units: 1.5 }, { ...row, distance_km: -1 }]) {
        globalThis.fetch = async () => Response.json({ hospitals: [value] });await assert.rejects(api.searchHospitals({ pincode: "632001" }), /Unexpected API response/);
      }
    });
    await t.test("blood group, plus Rh and cookies reach the configured Java URL", async () => {
      globalThis.fetch = async (url, options) => {
        const u = new URL(url);assert.equal(u.origin, "http://127.0.0.1:8080");assert.equal(u.pathname, "/api/hospitals/nearby");
        assert.equal(u.searchParams.get("blood_group"), "AB");assert.equal(u.searchParams.get("rh_factor"), "+");assert.equal(u.searchParams.get("pincode"), "632001");
        assert.equal(options.credentials, "include");assert.equal(options.method, "GET");return Response.json({ hospitals: [row] });
      };
      await api.searchHospitals({ pincode: "632001", blood_group: "AB", rh_factor: "+" });
    });
    await t.test("HTTP, location, unreadable JSON and network errors remain visible", async () => {
      for (const [status, message] of [[503, "Oracle unavailable"], [422, "Unknown pincode"]]) {
        globalThis.fetch = async () => Response.json({ error: { code: "TEST", message } }, { status });
        await assert.rejects(api.getHospitals(), (e) => e.status === status && e.message === message);
      }
      globalThis.fetch = async () => new Response("not JSON");await assert.rejects(api.getHospitals(), /unreadable response/);
      globalThis.fetch = async () => { throw new TypeError("Failed to fetch"); };await assert.rejects(api.getHospitals(), /Cannot connect/);
    });
    await t.test("retry makes a fresh GET without a preview fallback", async () => {
      let calls = 0;
      globalThis.fetch = async () => ++calls === 1 ? Response.json({ error: { message: "Unavailable" } }, { status: 503 }) : Response.json([hospital]);
      await assert.rejects(api.getHospitals(), /Unavailable/);assert.deepEqual(await api.getHospitals(), [hospital]);assert.equal(calls, 2);
    });
    await t.test("caller cancellation aborts the transport request", async () => {
      const controller = new AbortController();
      globalThis.fetch = (url, options) => new Promise((resolve, reject) => {
        options.signal.addEventListener("abort", () => reject(new DOMException("Cancelled", "AbortError")), { once: true });
      });
      const request = api.getHospitals(controller.signal);controller.abort();await assert.rejects(request, { name: "AbortError" });
    });
  } finally { globalThis.fetch = original;await server.close(); }
});

test("hospital list and citizen search lifecycle", async (t) => {
  const virtual = "\0hospital-test-hooks";
  const server = await createServer({
    server: { middlewareMode: true, hmr: false }, appType: "custom",
    plugins: [{
      name: "hospital-hook-test-harness", enforce: "pre",
      resolveId(id) { if (id === "virtual:hospital-test-hooks") return virtual; },
      load(id) { if (id === virtual) return ["useState", "useEffect", "useRef"].map((name) => `export const ${name} = (...args) => globalThis.__hospitalTestHooks.${name}(...args);`).join("\n"); },
      transform(code, id) {
        if (/src\/(components\/useHospitals\.js|pages\/CitizenPortal\.jsx)$/.test(id.replaceAll("\\", "/")))
          return code.replace('from "react"', 'from "virtual:hospital-test-hooks"');
      },
    }],
  });
  const previousHooks = globalThis.__hospitalTestHooks, PreviousFormData = globalThis.FormData;
  try {
    const { default: useHospitals } = await server.ssrLoadModule("/src/components/useHospitals.js");
    const { default: CitizenPortal } = await server.ssrLoadModule("/src/pages/CitizenPortal.jsx");
    await t.test("string assignment matches numeric ID 90007", async () => {
      const h = hooks();globalThis.__hospitalTestHooks = h;
      const api = { getHospitals: async () => [hospital, { ...hospital, hospital_id: 90007 }] };
      const render = () => h.render(() => useHospitals("90007", api));
      assert.equal(render().loading, true);await tick();assert.deepEqual(render().hospitals.map((v) => v.hospital_id), [90007]);h.unmount();
    });
    await t.test("hook handles synchronous failures, retry and empty results", async () => {
      const h = hooks();globalThis.__hospitalTestHooks = h;let calls = 0;
      const api = { getHospitals: () => { if (++calls === 1) throw new Error("Offline");return Promise.resolve([]); } };
      const render = () => h.render(() => useHospitals(undefined, api));render();let state = render();assert.equal(state.error, "Offline");assert.deepEqual(state.hospitals, []);
      state.retry();render();await tick();state = render();assert.equal(state.error, "");assert.equal(state.loading, false);assert.deepEqual(state.hospitals, []);h.unmount();
    });
    await t.test("assignment changes abort earlier requests and discard late results", async () => {
      const h = hooks();globalThis.__hospitalTestHooks = h;const requests = [];
      const api = { getHospitals: (signal) => { const d = deferred();requests.push({ ...d, signal });return d.promise; } };
      let assigned = "90001";const render = () => h.render(() => useHospitals(assigned, api));
      render();assigned = "90007";render();assert.equal(requests[0].signal.aborted, true);
      requests[1].resolve([{ ...hospital, hospital_id: 90007 }]);await tick();requests[0].resolve([hospital]);await tick();assert.equal(render().hospitals[0].hospital_id, 90007);h.unmount();
    });
    await t.test("search cancellation, late success, errors, retry and empty state", async () => {
      const h = hooks();globalThis.__hospitalTestHooks = h;const requests = [];
      const api = { searchHospitals: (query, signal) => { const d = deferred();requests.push({ ...d, query, signal });return d.promise; } };
      globalThis.FormData = class { constructor(form) { this.form = form; } get(key) { return this.form[key]; } };
      const render = () => h.render(() => CitizenPortal({ dataApi: api }));
      const event = { preventDefault() {}, currentTarget: { pincode: "632001" } };
      let tree = render(), form = find(tree, (v) => v.type === "form");
      const first = form.props.onSubmit(event);tree = render();assert.match(renderToStaticMarkup(tree), /Cancel search/);
      find(tree, (v) => v.type === "button" && v.props.children === "Cancel search").props.onClick();
      assert.equal(requests[0].signal.aborted, true);requests[0].resolve({ hospitals: [row] });await first;assert.doesNotMatch(renderToStaticMarkup(render()), /Contract Test Hospital/);
      form = find(render(), (v) => v.type === "form");const failed = form.props.onSubmit(event);requests[1].reject(new Error("Oracle unavailable"));await failed;tree = render();
      const html = renderToStaticMarkup(tree);assert.match(html, /Oracle unavailable/);assert.match(html, /Retry hospital search/);assert.doesNotMatch(html, /Your search starts here|Contract Test Hospital/);
      form.props.ref.current = { requestSubmit: () => form.props.onSubmit(event) };
      const retry = find(tree, (v) => v.type === "button" && v.props.children === "Retry hospital search").props.onClick();
      requests[2].resolve({ hospitals: [row] });await retry;assert.match(renderToStaticMarkup(render()), /Contract Test Hospital/);assert.equal(requests[2].query.rh_factor, "+");
      find(render(), (v) => v.props?.name === "pincode").props.onChange();assert.doesNotMatch(renderToStaticMarkup(render()), /Contract Test Hospital/);
      form = find(render(), (v) => v.type === "form");const empty = form.props.onSubmit(event);requests[3].resolve({ hospitals: [] });await empty;assert.match(renderToStaticMarkup(render()), /No hospitals found/);h.unmount();
    });
  } finally {
    if (previousHooks === undefined) delete globalThis.__hospitalTestHooks;else globalThis.__hospitalTestHooks = previousHooks;
    globalThis.FormData = PreviousFormData;await server.close();
  }
});
