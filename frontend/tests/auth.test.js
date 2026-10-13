import test from "node:test";
import assert from "node:assert/strict";
import {
  createAuthClient,
  validateSession,
} from "../src/services/authClient.js";
import { validateAuthFields } from "../src/services/authValidation.js";
import { canAccess, roleHome } from "../src/auth/routes.js";
import { requestJson } from "../src/services/transport.js";
import { getCsrfToken, setCsrfToken } from "../src/services/sessionSecurity.js";
// Test response fixtures only. The application has no fake-auth implementation.
const citizen = {
  user_id: "U-TEST",
  full_name: "Test User",
  email: "test@example.test",
  role: "CITIZEN",
  status: "ACTIVE",
  hospital_id: null,
};
const staff = { ...citizen, role: "HOSPITAL_STAFF", hospital_id: "H-TEST" };
const json = (body, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
async function withFetch(handler, run) {
  const original = globalThis.fetch;
  try {
    globalThis.fetch = handler;
    await run();
  } finally {
    globalThis.fetch = original;
    setCsrfToken("");
  }
}
test("guest session is not authenticated; CSRF bootstrap accepts specified 401 contract", async () => {
  await withFetch(
    async () =>
      json({ csrf_token: "test-token", error: { message: "Sign in" } }, 401),
    async () => {
      const session = await createAuthClient(
        "http://localhost/api",
      ).getSession();
      assert.equal(session.user, null);
      assert.equal(getCsrfToken(), "test-token");
    },
  );
});
test("login includes cookies and requires a subsequent cookie-backed session", async () => {
  const calls = [];
  setCsrfToken("test-guest-token");
  await withFetch(
    async (url, options) => {
      calls.push({ url, options });
      return url.endsWith("/login")
        ? json({ csrf_token: "test-user-token" })
        : json({ user: citizen, csrf_token: "test-user-token" });
    },
    async () => {
      const session = await createAuthClient("http://localhost/api").login({
        email: citizen.email,
        password: "test-password-only",
        role: "CITIZEN",
      });
      assert.equal(session.user.role, "CITIZEN");
      assert.equal(calls.length, 2);
      assert.equal(calls[0].url, "http://localhost/api/auth/login");
      assert.equal(calls[1].url, "http://localhost/api/auth/me");
      assert.equal(calls[0].options.credentials, "include");
      assert.equal(calls[1].options.credentials, "include");
      assert.equal(
        calls[0].options.headers["X-CSRF-Token"],
        "test-guest-token",
      );
    },
  );
});
test("login does not accept success without a session, wrong roles or network failures", async () => {
  await withFetch(
    async (url) =>
      url.endsWith("/login")
        ? json({})
        : json({ user: null, csrf_token: "test-token" }),
    async () => {
      await assert.rejects(
        createAuthClient("http://localhost/api").login({ role: "CITIZEN" }),
        /could not establish/,
      );
    },
  );
  await withFetch(
    async (url) =>
      url.endsWith("/login")
        ? json({})
        : json({ user: staff, csrf_token: "test-token" }),
    async () => {
      await assert.rejects(
        createAuthClient("http://localhost/api").login({ role: "CITIZEN" }),
        /selected role/,
      );
    },
  );
  await withFetch(
    async () => {
      throw new TypeError("offline");
    },
    async () => {
      await assert.rejects(
        createAuthClient("http://localhost/api").login({ role: "CITIZEN" }),
        /Cannot connect/,
      );
    },
  );
});
test("staff requires ACTIVE status and an approved hospital assignment", () => {
  assert.throws(
    () =>
      validateSession({
        user: { ...staff, status: "PENDING_APPROVAL" },
        csrf_token: "test-token",
      }),
    /not ready/,
  );
  assert.throws(
    () =>
      validateSession({
        user: { ...staff, hospital_id: null },
        csrf_token: "test-token",
      }),
    /hospital assignment/,
  );
  assert.throws(() => validateSession({ user: citizen }), /secured/);
});
test("registration does not establish authentication; staff access stays pending", async () => {
  const calls = [];
  await withFetch(
    async (url) => {
      calls.push(url);
      return json(
        {
          status: url.endsWith("/hospital") ? "PENDING_APPROVAL" : "REGISTERED",
        },
        201,
      );
    },
    async () => {
      setCsrfToken("test-guest-token");
      const client = createAuthClient("http://localhost/api");
      assert.equal((await client.registerCitizen({})).status, "REGISTERED");
      assert.equal(
        (await client.requestHospitalAccess({})).status,
        "PENDING_APPROVAL",
      );
      assert.deepEqual(calls, [
        "http://localhost/api/auth/register/citizen",
        "http://localhost/api/auth/register/hospital",
      ]);
    },
  );
});
test("logout supports 204; failed logout keeps security state and reports failure", async () => {
  await withFetch(
    async () => new Response(null, { status: 204 }),
    async () => {
      setCsrfToken("test-token");
      await createAuthClient("http://localhost/api").logout();
      assert.equal(getCsrfToken(), "");
    },
  );
  await withFetch(
    async () => json({ error: { message: "Try later" } }, 503),
    async () => {
      setCsrfToken("test-token");
      await assert.rejects(
        createAuthClient("http://localhost/api").logout(),
        /Try later/,
      );
      assert.equal(getCsrfToken(), "test-token");
    },
  );
});
test("unauthorized operations emit session expiry; failed login does not", async () => {
  const originalWindow = globalThis.window;
  const events = [];
  globalThis.window = { dispatchEvent: (event) => events.push(event.type) };
  try {
    await withFetch(
      async () => json({ error: { message: "Session expired" } }, 401),
      async () => {
        await assert.rejects(
          requestJson("http://localhost/api", "/hospitals"),
          (error) => error.status === 401,
        );
        assert.deepEqual(events, ["auth:expired"]);
        events.length = 0;
        setCsrfToken("test-guest-token");
        await assert.rejects(
          createAuthClient("http://localhost/api").login({ role: "CITIZEN" }),
          /Session expired/,
        );
        assert.deepEqual(events, []);
      },
    );
  } finally {
    if (originalWindow === undefined) delete globalThis.window;
    else globalThis.window = originalWindow;
  }
});
test("role routes reject anonymous and wrong-role access", () => {
  assert.equal(canAccess("/citizen", null), false);
  assert.equal(canAccess("/hospital", citizen), false);
  assert.equal(canAccess("/request", citizen), false);
  assert.equal(canAccess("/citizen", staff), false);
  assert.equal(canAccess("/citizen", citizen), true);
  assert.equal(canAccess("/hospital", staff), true);
  assert.equal(canAccess("/request", staff), true);
  assert.equal(canAccess("/roles", null), true);
  assert.equal(roleHome(citizen), "/citizen");
  assert.equal(roleHome(staff), "/hospital");
});
test("auth validation rejects invalid email, short/mismatched passwords and missing hospital", () => {
  const input = {
    full_name: "Test User",
    email: "test@example.test",
    password: "test-only-long-password",
    confirm_password: "test-only-long-password",
    hospital_id: "H-TEST",
  };
  assert.equal(
    validateAuthFields(input, { registering: true, staff: true }),
    "",
  );
  assert.match(validateAuthFields({ ...input, email: "bad" }), /email/);
  assert.match(
    validateAuthFields({ ...input, password: "short" }, { registering: true }),
    /12 characters/,
  );
  assert.match(
    validateAuthFields(
      { ...input, confirm_password: "different" },
      { registering: true },
    ),
    /match/,
  );
  assert.match(
    validateAuthFields(
      { ...input, hospital_id: "" },
      { registering: true, staff: true },
    ),
    /hospital/,
  );
});

test("login bootstraps a fresh guest CSRF token when none is available", async () => {
  const urls = [];
  setCsrfToken("");
  await withFetch(
    async (url, options) => {
      urls.push(url);
      if (urls.length === 1)
        return json({ user: null, csrf_token: "fresh-guest-token" });
      if (url.endsWith("/login")) {
        assert.equal(options.headers["X-CSRF-Token"], "fresh-guest-token");
        return json({ csrf_token: "fresh-user-token" });
      }
      return json({ user: citizen, csrf_token: "fresh-user-token" });
    },
    async () => {
      const session = await createAuthClient("http://localhost/api").login({
        role: "CITIZEN",
      });
      assert.equal(session.user.user_id, citizen.user_id);
      assert.deepEqual(
        urls.map((url) => url.split("/").pop()),
        ["me", "login", "me"],
      );
    },
  );
});
