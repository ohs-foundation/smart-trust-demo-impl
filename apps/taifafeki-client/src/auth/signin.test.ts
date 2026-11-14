import { ClientStorage, MedplumClient, MemoryStorage } from "@medplum/core";
import { createHash } from "node:crypto";
import { describe, expect, it, vi } from "vitest";
import { beginSignIn } from "./begin";
import { completeSignIn } from "./complete";

const settings = {
  clientId: "client-1",
  medplumBaseUrl: "http://medplum.test:8480",
  clientOrigin: "http://client.test:8480",
};

describe("sign-in", () => {
  it("redirects to Medplum with an S256 challenge for this client", () => {
    const { location, cookie } = beginSignIn(settings);
    const url = new URL(location);
    expect(url.origin + url.pathname).toBe("http://medplum.test:8480/oauth2/authorize");
    expect(url.searchParams.get("client_id")).toBe("client-1");
    expect(url.searchParams.get("redirect_uri")).toBe("http://client.test:8480");
    expect(url.searchParams.get("code_challenge_method")).toBe("S256");
    const pending = JSON.parse(decodeURIComponent(cookie.split(";")[0]!.slice("pkce=".length)));
    const challenge = createHash("sha256").update(pending.verifier).digest("base64url");
    expect(url.searchParams.get("code_challenge")).toBe(challenge);
    expect(url.searchParams.get("state")).toBe(pending.state);
  });

  it("refuses a response whose state is not the one sent", async () => {
    const { cookie } = beginSignIn(settings);
    const storage = new ClientStorage(new MemoryStorage());
    const medplum = new MedplumClient({ baseUrl: settings.medplumBaseUrl, storage });
    const processCode = vi.spyOn(medplum, "processCode");
    await expect(
      completeSignIn(medplum, storage, cookie.split(";")[0] ?? "", "code", "other"),
    ).rejects.toThrow("does not match");
    expect(processCode).not.toHaveBeenCalled();
  });
});
