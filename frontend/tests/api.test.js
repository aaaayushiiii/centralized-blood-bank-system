import test from "node:test";
import assert from "node:assert/strict";
import { createMockApi, DEMO_DATE } from "../src/services/mock.js";
import { requestJson } from "../src/services/transport.js";
test("search counts only unexpired available exact-match bags", async () => {
  const api = createMockApi();
  const results = await api.searchHospitals({
    pincode: "632001",
    blood_group: "O",
    rh_factor: "+",
  });
  for (const row of results.hospitals) {
    const inventory = await api.getInventory(row.hospital.hospital_id);
    assert.equal(
      row.available_units,
      inventory.bags.filter(
        (b) =>
          b.blood_group === "O" &&
          b.rh_factor === "+" &&
          b.bag_status === "Available" &&
          b.expiry_date >= DEMO_DATE,
      ).length,
    );
  }
  await assert.rejects(
    api.searchHospitals({ pincode: "999999" }),
    /No location/,
  );
});
test("failed allocation is atomic; successful allocation cannot repeat", async () => {
  const api = createMockApi();
  const before = await api.getInventory("H-DEMO-01");
  const large = await api.createRequest({
    hospital_id: "H-DEMO-01",
    blood_group: "O",
    rh_factor: "+",
    units_requested: 999,
    priority: "Critical",
  });
  await assert.rejects(
    api.allocateRequest(large.request.request_id),
    /Insufficient/,
  );
  assert.deepEqual(await api.getInventory("H-DEMO-01"), before);
  const small = await api.createRequest({
    hospital_id: "H-DEMO-01",
    blood_group: "O",
    rh_factor: "+",
    units_requested: 1,
    priority: "Critical",
  });
  assert.equal(
    (await api.allocateRequest(small.request.request_id)).allocations.length,
    1,
  );
  await assert.rejects(
    api.allocateRequest(small.request.request_id),
    /already/,
  );
});
test("live transport surfaces HTTP, malformed JSON and network errors", async () => {
  const original = globalThis.fetch;
  try {
    globalThis.fetch = async () =>
      new Response(JSON.stringify({ error: { message: "No stock" } }), {
        status: 409,
      });
    await assert.rejects(
      requestJson("http://localhost/api", "/requests"),
      /No stock/,
    );
    globalThis.fetch = async () => new Response("<html>Error</html>");
    await assert.rejects(
      requestJson("http://localhost/api", "/hospitals"),
      /unreadable response/,
    );
    globalThis.fetch = async () => {
      throw new TypeError("Failed to fetch");
    };
    await assert.rejects(
      requestJson("http://localhost/api", "/hospitals"),
      /Cannot connect to the service/,
    );
  } finally {
    globalThis.fetch = original;
  }
});
