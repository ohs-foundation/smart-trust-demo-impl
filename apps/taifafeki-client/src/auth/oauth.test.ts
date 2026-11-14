import { describe, expect, it } from "vitest";
import { authorizeParams, login } from "./oauth";

const search = { client_id: "client-1", state: "s1", scope: "openid profile" };

function fakeMedplum(replies: Record<string, unknown>, calls: { url: string; body: unknown }[]) {
  return async (url: string | URL | Request, init?: RequestInit) => {
    const path = new URL(String(url)).pathname;
    calls.push({ url: path, body: JSON.parse(String(init?.body)) });
    return new Response(JSON.stringify(replies[path]), { status: 200 });
  };
}

const membership = (id: string, project: string) => ({ id, project: { display: project } });

describe("login", () => {
  it("reads the authorize query", () => {
    expect(authorizeParams(search).clientId).toBe("client-1");
  });

  it("sends the server's S256 challenge with the credentials and returns the code", async () => {
    const calls: { url: string; body: unknown }[] = [];
    const code = await login(
      "http://medplum.test:8480",
      "client-1",
      "chal",
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
    expect(code).toBe("abc");
  });

  it("picks the Taifafeki membership, not the first", async () => {
    const calls: { url: string; body: unknown }[] = [];
    const code = await login(
      "http://medplum.test:8480",
      "client-1",
      "chal",
      "a@b.c",
      "pw",
      fakeMedplum(
        {
          "/auth/login": { login: "l1", memberships: [membership("m0", "Other"), membership("m1", "Taifafeki")] },
          "/auth/profile": { code: "def" },
        },
        calls,
      ),
    );
    expect(calls[1]).toEqual({ url: "/auth/profile", body: { login: "l1", profile: "m1" } });
    expect(code).toBe("def");
  });

  it("refuses a user with no Taifafeki membership", async () => {
    await expect(
      login("http://m", "c", "x", "a@b.c", "pw", fakeMedplum({ "/auth/login": { login: "l", memberships: [membership("m0", "Other")] } }, [])),
    ).rejects.toThrow("Sign-in failed");
  });

  it("says plainly that MFA is not supported", async () => {
    await expect(
      login("http://m", "c", "x", "a@b.c", "pw", fakeMedplum({ "/auth/login": { login: "l", mfaRequired: true } }, [])),
    ).rejects.toThrow("this account needs MFA, which this demo client does not support");
  });

  it("refuses when Medplum offers no code", async () => {
    await expect(login("http://m", "c", "x", "a@b.c", "bad", fakeMedplum({ "/auth/login": {} }, []))).rejects.toThrow("Sign-in failed");
  });
});
