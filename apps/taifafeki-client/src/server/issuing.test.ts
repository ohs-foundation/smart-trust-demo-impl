import { describe, expect, it, vi } from "vitest";
import { checkIssueRequest, issueCardFor } from "./issuing";

describe("theIssuerIsCalledWithTheUsersToken", () => {
  it("forwards the signed-in user's token and names the dose", async () => {
    const send = vi.fn(async () => new Response("HC1:ABC", { status: 200 }));
    const card = await issueCardFor("http://issuer:8080", { token: "user-token", immunizationId: "dose-9" }, send as unknown as typeof fetch);
    expect(card).toBe("HC1:ABC");
    const [url, init] = send.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("http://issuer:8080/issuer/$issue");
    expect(init.headers).toMatchObject({ Authorization: "Bearer user-token" });
    expect(JSON.parse(String(init.body))).toEqual({ immunization: "dose-9" });
  });

  it("says why when the issuer refuses", async () => {
    const send = vi.fn(async () => new Response("Medplum answered 403", { status: 403 }));
    await expect(issueCardFor("http://issuer:8080", { token: "t", immunizationId: "d" }, send as unknown as typeof fetch)).rejects.toThrow("refused (403): Medplum answered 403");
  });

  it("asks for a token and a plain dose id", () => {
    expect(() => checkIssueRequest({ immunizationId: "d" })).toThrow("token");
    expect(() => checkIssueRequest({ token: "t", immunizationId: "../Patient" })).toThrow("dose id");
  });
});
