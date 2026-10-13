import test from "node:test";
import assert from "node:assert/strict";
import { createServer } from "vite";
// Verify that the operational service still sends the agreed paths/methods.
// Fixtures replace fetch only inside this test; no runtime mock auth is added.
test("operational API wrappers preserve paths, payloads and cookie credentials", async () => {
  const server = await createServer({
    server: { middlewareMode: true, hmr: false },
    appType: "custom",
  });
  const originalFetch = globalThis.fetch;
  const calls = [];
  const hospital = {
    hospital_id: "H-TEST",
    name: "Test Hospital",
    address: "Test Address",
    phone: "0000000000",
  };
  globalThis.fetch = async (url, options) => {
    calls.push({
      path: new URL(url).pathname,
      query: new URL(url).searchParams,
      options,
    });
    const path = new URL(url).pathname;
    let result;
    if (path.endsWith("/nearby"))
      result = {
        hospitals: [{ hospital, distance_km: 1, available_units: 2 }],
      };
    else if (path.endsWith("/inventory"))
      result = { as_of_date: "2026-10-13", near_expiry_days: 7, bags: [] };
    else if (path.endsWith("/donors"))
      result = { donor: { donor_id: "D-TEST" } };
    else if (path.endsWith("/allocations"))
      result = {
        request: { request_id: "R-TEST", request_status: "Allocated" },
        allocations: [],
      };
    else if (path.endsWith("/requests"))
      result = { request: { request_id: "R-TEST", request_status: "Pending" } };
    else result = [hospital];
    return new Response(JSON.stringify(result), { status: 200 });
  };
  try {
    const { api } = await server.ssrLoadModule("/src/services/api.js");
    await api.getHospitals();
    await api.searchHospitals({
      pincode: "632001",
      blood_group: "O",
      rh_factor: "+",
    });
    await api.getInventory("H-TEST");
    await api.registerDonor({ full_name: "Test Donor" });
    await api.createRequest({ hospital_id: "H-TEST", units_requested: 1 });
    await api.allocateRequest("R-TEST");
    assert.deepEqual(
      calls.map((call) => call.path),
      [
        "/api/hospitals",
        "/api/hospitals/nearby",
        "/api/hospitals/H-TEST/inventory",
        "/api/donors",
        "/api/requests",
        "/api/requests/R-TEST/allocations",
      ],
    );
    assert.deepEqual(
      calls.map((call) => call.options.method),
      ["GET", "GET", "GET", "POST", "POST", "POST"],
    );
    calls.forEach((call) => assert.equal(call.options.credentials, "include"));
    assert.equal(calls[1].query.get("rh_factor"), "+");
    assert.equal(calls[1].query.get("pincode"), "632001");
    assert.deepEqual(JSON.parse(calls[4].options.body), {
      hospital_id: "H-TEST",
      units_requested: 1,
    });
  } finally {
    globalThis.fetch = originalFetch;
    await server.close();
  }
});
