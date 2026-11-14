import { createHash } from "node:crypto";
import { describe, expect, it } from "vitest";
import { beginSignIn } from "./begin";
import { readPkce } from "./cookie";
import { exchangeCode } from "./exchange";
import { checkAuthorize } from "./guard";

const settings = {
  clientId: "client-1",
  medplumBaseUrl: "http://medplum.test:8480",
  medplumInternalUrl: "http://medplum:8103",
  clientOrigin: "http://client.test:8480",
};

function started() {
  const { location, cookie } = beginSignIn(settings);
  const query = Object.fromEntries(new URL(location).searchParams);
  const pending = readPkce(cookie.split(";")[0]!.slice("pkce=".length))!;
  return { location, cookie, query, pending };
}

describe("sign-in start", () => {
  it("redirects to Medplum with an S256 challenge for this client", () => {
    const { location, query, pending } = started();
    expect(new URL(location).origin + new URL(location).pathname).toBe(
      "http://medplum.test:8480/oauth2/authorize",
    );
    expect(query).toMatchObject({ client_id: "client-1", redirect_uri: settings.clientOrigin });
    expect(query.code_challenge_method).toBe("S256");
    expect(query.code_challenge).toBe(
      createHash("sha256").update(pending.verifier).digest("base64url"),
    );
    expect(query.state).toBe(pending.state);
  });

  it("keeps the verifier in an HttpOnly cookie and out of the URL", () => {
    const { location, cookie, pending } = started();
    expect(location).not.toContain(pending.verifier);
    expect(cookie).toMatch(/; HttpOnly/);
    expect(cookie).toMatch(/; SameSite=Lax/);
    expect(cookie).toMatch(/; Max-Age=600/);
    expect(cookie).not.toMatch(/Secure/);
  });

  it("marks the cookie Secure on an https origin", () => {
    const { cookie } = beginSignIn({ ...settings, clientOrigin: "https://client.test" });
    expect(cookie).toMatch(/; Secure/);
  });
});

describe("aCraftedOauthLinkIsRefused", () => {
  const good = () => started();

  it("accepts the link our own sign-in produced", () => {
    const { query, pending } = good();
    expect(checkAuthorize(settings, pending, query)).toMatchObject({ ok: true });
  });

  it.each([
    ["another redirect_uri", { redirect_uri: "https://evil.example/" }],
    ["a javascript redirect_uri", { redirect_uri: "javascript:alert(1)//" }],
    ["the redirect_uri with a path", { redirect_uri: "http://client.test:8480/x" }],
    ["a plain challenge method", { code_challenge_method: "plain" }],
    ["another client_id", { client_id: "client-2" }],
    ["a challenge not from our cookie", { code_challenge: "attackerChosen" }],
    ["another state", { state: "other" }],
  ])("refuses %s", (_name, change) => {
    const { query, pending } = good();
    expect(checkAuthorize(settings, pending, { ...query, ...change })).toMatchObject({ ok: false });
  });

  it("refuses a link opened with no sign-in pending", () => {
    expect(checkAuthorize(settings, undefined, good().query)).toMatchObject({
      ok: false,
      message: "Start sign-in again",
    });
  });
});

describe("the code exchange", () => {
  function medplum(reply: unknown, seen: { body?: URLSearchParams } = {}) {
    return async (url: string | URL | Request, init?: RequestInit) => {
      expect(String(url)).toBe("http://medplum:8103/oauth2/token");
      seen.body = new URLSearchParams(String(init?.body));
      return new Response(JSON.stringify(reply), { status: 200 });
    };
  }

  it("sends the cookie's verifier from the server and returns the tokens", async () => {
    const { pending } = started();
    const seen: { body?: URLSearchParams } = {};
    const result = await exchangeCode(
      settings,
      pending,
      "code-1",
      pending.state,
      medplum({ access_token: "a", refresh_token: "r", project: { reference: "Project/p" }, profile: { reference: "Practitioner/x" } }, seen),
    );
    expect(seen.body?.get("code_verifier")).toBe(pending.verifier);
    expect(seen.body?.get("code")).toBe("code-1");
    expect(result.tokens).toMatchObject({ accessToken: "a", refreshToken: "r" });
  });

  it("theVerifierNeverReachesThePage", async () => {
    const { pending } = started();
    const result = await exchangeCode(
      settings,
      pending,
      "code-1",
      pending.state,
      medplum({ access_token: "a", refresh_token: "r", project: { reference: "Project/p" }, profile: { reference: "Practitioner/x" } }),
    );
    expect(JSON.stringify(result)).not.toContain(pending.verifier);
    const check = checkAuthorize(settings, pending, started().query);
    expect(JSON.stringify(check)).not.toContain(pending.verifier);
  });

  it("theCookieIsClearedAfterTheExchange", async () => {
    const { pending } = started();
    const ok = await exchangeCode(settings, pending, "c", pending.state, medplum({ access_token: "a", refresh_token: "r", project: { reference: "Project/p" }, profile: { reference: "Practitioner/x" } }));
    const refused = await exchangeCode(settings, pending, "c", "wrong", medplum({}));
    for (const result of [ok, refused]) {
      expect(result.clear).toMatch(/^pkce=; .*Max-Age=0/);
      expect(result.clear).toMatch(/HttpOnly/);
    }
    expect(refused.error).toBeDefined();
    expect(refused.tokens).toBeUndefined();
  });

  it("fails cleanly when the sign-in was replaced by a second tab", async () => {
    const result = await exchangeCode(settings, undefined, "c", "s", medplum({}));
    expect(result.error).toBe("Start sign-in again");
  });
});
