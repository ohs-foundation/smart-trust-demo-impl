import { describe, expect, it } from "vitest";
import { authorizeParams, login } from "./oauth";

const params = authorizeParams({
  response_type: "code",
  client_id: "client-1",
  redirect_uri: "http://client.test:8480",
  state: "s1",
  scope: "openid profile",
  code_challenge: "chal",
  code_challenge_method: "S256",
});

function fakeMedplum(replies: Record<string, unknown>, calls: { url: string; body: unknown }[]) {
  return async (url: string | URL | Request, init?: RequestInit) => {
    const path = new URL(String(url)).pathname;
    calls.push({ url: path, body: JSON.parse(String(init?.body)) });
    return new Response(JSON.stringify(replies[path]), { status: 200 });
  };
}

describe("login", () => {
  it("sends the PKCE challenge with the credentials and returns to the client with the code", async () => {
    const calls: { url: string; body: unknown }[] = [];
    const to = await login(
      "http://medplum.test:8480",
      params,
      "anan@kesaro.example",
      "pw",
      fakeMedplum({ "/auth/login": { code: "abc" } }, calls),
    );
    expect(calls[0]?.body).toMatchObject({
      email: "anan@kesaro.example",
      clientId: "client-1",
      codeChallenge: "chal",
      codeChallengeMethod: "S256",
    });
    expect(to).toBe("http://client.test:8480/?code=abc&state=s1");
  });

  it("picks the only membership when Medplum asks which profile", async () => {
    const calls: { url: string; body: unknown }[] = [];
    const to = await login(
      "http://medplum.test:8480",
      params,
      "anan@kesaro.example",
      "pw",
      fakeMedplum(
        {
          "/auth/login": { login: "l1", memberships: [{ id: "m1" }] },
          "/auth/profile": { code: "def" },
        },
        calls,
      ),
    );
    expect(calls[1]).toEqual({ url: "/auth/profile", body: { login: "l1", profile: "m1" } });
    expect(to).toBe("http://client.test:8480/?code=def&state=s1");
  });

  it("refuses when Medplum offers no code", async () => {
    await expect(
      login("http://medplum.test:8480", params, "a@b.c", "bad", fakeMedplum({ "/auth/login": {} }, [])),
    ).rejects.toThrow("Sign-in failed");
  });
});
