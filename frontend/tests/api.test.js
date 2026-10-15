import test from "node:test";
import assert from "node:assert/strict";
import { requestJson } from "../src/services/transport.js";
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
