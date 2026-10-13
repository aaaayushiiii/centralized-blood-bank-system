import test from "node:test";
import assert from "node:assert/strict";
import { build, createServer, loadConfigFromFile } from "vite";
import { allowLocalPreview } from "../src/preview/policy.js";
import { canAccess } from "../src/auth/routes.js";
import { createMockApi } from "../src/services/mock.js";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
test("preview requires an explicit flag and a development serve command", () => {
  assert.equal(allowLocalPreview({ command: "serve", enabled: "true" }), true);
  for (const enabled of [undefined, "false", "TRUE", "1"])
    assert.equal(allowLocalPreview({ command: "serve", enabled }), false);
  assert.equal(allowLocalPreview({ command: "build", enabled: "true" }), false);
  assert.equal(
    allowLocalPreview({ command: "serve", isPreview: true, enabled: "true" }),
    false,
  );
  assert.equal(canAccess("/hospital", null), false);
  assert.equal(canAccess("/citizen", null), false);
  assert.equal(canAccess("/request", null), false);
});
test("preview inventory, search and mutations are memory-only without network calls", async () => {
  const previous = globalThis.fetch;
  globalThis.fetch = () => {
    throw new Error("Preview must never call a real API");
  };
  try {
    const api = createMockApi();
    assert.equal((await api.getHospitals())[0].hospital_id, "H-DEMO-01");
    assert.ok((await api.getInventory("H-DEMO-01")).bags.length > 0);
    assert.equal(
      (
        await api.searchHospitals({
          pincode: "632001",
          blood_group: "O",
          rh_factor: "+",
        })
      ).hospitals.length,
      3,
    );
    assert.ok(
      (await api.registerDonor({ full_name: "Preview test" })).donor.donor_id,
    );
    const { request } = await api.createRequest({
      hospital_id: "H-DEMO-01",
      blood_group: "O",
      rh_factor: "+",
      units_requested: 1,
      priority: "Critical",
    });
    assert.equal(
      (await api.allocateRequest(request.request_id)).allocations.length,
      1,
    );
    await assert.rejects(
      createMockApi().allocateRequest(request.request_id),
      /not found/,
    );
  } finally {
    globalThis.fetch = previous;
  }
});
test("preview route renders independently of session-loading and labels unauthenticated data", async () => {
  const previousFlag = process.env.VITE_ENABLE_PREVIEW;
  const previousWindow = globalThis.window;
  process.env.VITE_ENABLE_PREVIEW = "true";
  globalThis.window = { location: { hash: "#/preview/hospital" } };
  const server = await createServer({
    server: { middlewareMode: true, hmr: false },
    appType: "custom",
  });
  try {
    const { default: App } = await server.ssrLoadModule("/src/App.jsx");
    const { AuthProvider } = await server.ssrLoadModule(
      "/src/auth/AuthContext.jsx",
    );
    const html = renderToStaticMarkup(
      React.createElement(AuthProvider, null, React.createElement(App)),
    );
    assert.match(html, /not authenticated/);
    assert.match(html, /Fictional hospitals/);
    assert.match(html, /Exit preview/);
    assert.doesNotMatch(html, /Checking your session/);
    assert.doesNotMatch(html, /Approved staff access/);
  } finally {
    await server.close();
    if (previousFlag === undefined) delete process.env.VITE_ENABLE_PREVIEW;
    else process.env.VITE_ENABLE_PREVIEW = previousFlag;
    if (previousWindow === undefined) delete globalThis.window;
    else globalThis.window = previousWindow;
  }
});
test("build/production-preview config disables preview even when the flag is true", async () => {
  const previous = process.env.VITE_ENABLE_PREVIEW;
  process.env.VITE_ENABLE_PREVIEW = "true";
  try {
    for (const environment of [
      { command: "build", mode: "production" },
      { command: "build", mode: "development" },
      { command: "serve", mode: "production", isPreview: true },
    ]) {
      const result = await loadConfigFromFile(environment);
      assert.equal(result.config.define.__ENABLE_LOCAL_PREVIEW__, "false");
    }
  } finally {
    if (previous === undefined) delete process.env.VITE_ENABLE_PREVIEW;
    else process.env.VITE_ENABLE_PREVIEW = previous;
  }
});
test("built assets exclude preview workspace and data despite development env overrides", async () => {
  const previousFlag = process.env.VITE_ENABLE_PREVIEW,
    previousNodeEnv = process.env.NODE_ENV;
  process.env.VITE_ENABLE_PREVIEW = "true";
  process.env.NODE_ENV = "development";
  try {
    const result = await build({
      mode: "development",
      logLevel: "silent",
      build: { write: false },
    });
    const outputs = (Array.isArray(result) ? result : [result]).flatMap(
      (r) => r.output,
    );
    const code = outputs
      .filter((o) => o.type === "chunk")
      .map((o) => o.code)
      .join("\n");
    assert.equal(
      /Sample North Hospital|H-DEMO-01|B-DEMO-|632014/.test(code),
      false,
      "Preview fixtures must be excluded from built assets",
    );
    const modules = outputs
      .filter((o) => o.type === "chunk")
      .flatMap((o) => Object.keys(o.modules))
      .join("\n");
    assert.doesNotMatch(modules, /PreviewWorkspace\.jsx|services\/mock\.js/);
  } finally {
    if (previousFlag === undefined) delete process.env.VITE_ENABLE_PREVIEW;
    else process.env.VITE_ENABLE_PREVIEW = previousFlag;
    if (previousNodeEnv === undefined) delete process.env.NODE_ENV;
    else process.env.NODE_ENV = previousNodeEnv;
  }
});
