import { createHash, createPrivateKey, sign, X509Certificate } from "node:crypto";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { WitIssuer, WitRegistry, WitTrust } from "smart-trust-kotlin-hcert-core";
import { describe, expect, it, vi } from "vitest";
import { NoSession, requireSession } from "./session";
import { DEV_SIGNER, LOCAL_SIGNER, TrustLists } from "./trustLists";
import { LinkHandles, scanLink, type Viewer } from "./viewer";

const TESTKIT = join(import.meta.dirname, "..", "..", "..", "..", "testkit");
const read = (...path: string[]) => readFileSync(join(TESTKIT, ...path), "utf8");

const NOW = Date.parse("2026-11-14T00:00:00Z") / 1000;
const LOCAL_URL = "http://gdhcn/local-test-list.json";
const DEV_URL = "http://gdhcn/dev/v2/trustlist/did.json";
const KEY = "86F8LY5LlWAa1-OS_FgrTnYNqFHJP2ey5RSKLJBN9jk";

interface Link {
  signedBy?: string;
  exp?: number;
  flag?: string;
  hcertExp?: number;
}

function link({ signedBy = "XXK_LINK", exp = NOW + 86400 * 30, flag = "LP", hcertExp = NOW + 86400 }: Link = {}): string {
  const registry = new WitRegistry();
  const pending = WitIssuer.getInstance().prepare(
    {
      payloads: [{ tag: "vhl", val: { url: "https://vhl.onesa.example:8443/vhl/List/_search?_id=abc", key: KEY, exp: BigInt(exp), flag, label: "Somsak's health summary", v: 1 } }],
      iss: "XX",
      validity: { iat: BigInt(NOW - 2 * 86400), exp: BigInt(hcertExp) },
      dscDer: new X509Certificate(read(`${signedBy}.crt.pem`)).raw,
      alg: "es256",
      allowDevelopmentSubclaims: false,
    },
    registry,
  );
  const signature = sign("sha256", Buffer.from(pending.toBeSigned()), { key: createPrivateKey(read(`${signedBy}.key.pem`)), dsaEncoding: "ieee-p1363" });
  return WitIssuer.getInstance().finish(pending, new Uint8Array(signature)).hc1;
}

async function viewer(fetchText?: (url: string) => Promise<string>): Promise<Viewer> {
  const trust = new TrustLists(
    { local: { url: LOCAL_URL, signer: LOCAL_SIGNER }, dev: { url: DEV_URL, signer: DEV_SIGNER } },
    fetchText ?? (async (url) => (url === LOCAL_URL ? read("local-test-list.json") : read("who", "root-did.json"))),
    () => NOW,
  );
  await trust.refresh();
  return {
    trust,
    handles: new LinkHandles(() => NOW),
    clinic: { hosts: [], identity: () => { throw new Error("no clinic key"); }, send: fetch },
    session: async (token) => {
      if (token !== "anan-token") throw new NoSession();
      return "Practitioner/anan";
    },
    now: () => NOW,
  };
}

const scan = (v: Viewer, text: string) => scanLink(v, { token: "anan-token", text });

describe("aFailedLinkIsTreatedAsPaper", () => {
  it("names the step and the reason for a link whose signature does not hold", async () => {
    const good = link();
    const middle = Math.floor(good.length / 2);
    const tampered = good.slice(0, middle) + (good[middle] === "A" ? "B" : "A") + good.slice(middle + 1);
    const outcome = await scan(await viewer(), tampered);
    expect(outcome).toMatchObject({ kind: "notVerified", step: expect.any(String), reason: expect.any(String) });
  });

  it("refuses text that is not a health certificate", async () => {
    expect(await scan(await viewer(), "not a link")).toMatchObject({ kind: "notVerified", step: "prefix" });
  });
});

describe("anExpiredLinkIsTreatedAsPaper", () => {
  it("reports a verified link whose own expiry has passed", async () => {
    const exp = NOW - 86400;
    expect(await scan(await viewer(), link({ exp }))).toEqual({ kind: "expired", exp });
  });

  it("reports a link whose certificate has expired", async () => {
    const hcertExp = NOW - 60 * 60;
    expect(await scan(await viewer(), link({ hcertExp }))).toEqual({ kind: "expired", exp: hcertExp });
  });
});

describe("aVerifiedLinkIsFetched", () => {
  it("returns what the next step needs, with a handle and no key", async () => {
    const v = await viewer();
    const outcome = await scan(v, link());
    expect(outcome).toMatchObject({
      kind: "fetch",
      url: "https://vhl.onesa.example:8443/vhl/List/_search?_id=abc",
      label: "Somsak's health summary",
      exp: NOW + 86400,
      needsPasscode: true,
      issuer: "XXK",
    });
    expect(JSON.stringify(outcome)).not.toContain(KEY);
    if (outcome.kind !== "fetch") throw new Error("not fetched");
    expect(v.handles.get(outcome.handle, "Practitioner/anan")?.key).toBe(KEY);
    expect(v.handles.get(outcome.handle, "Practitioner/someone-else")).toBeUndefined();
  });

  it("asks for no passcode when the link is not flagged P", async () => {
    expect(await scan(await viewer(), link({ flag: "L" }))).toMatchObject({ kind: "fetch", needsPasscode: false });
  });

  it("forgets a handle after ten minutes", async () => {
    let now = NOW;
    const handles = new LinkHandles(() => now);
    const handle = handles.keep({ url: "u", key: KEY, exp: NOW + 86400 }, "Practitioner/anan");
    now += 601;
    expect(handles.get(handle, "Practitioner/anan")).toBeUndefined();
  });

  it("forgets a handle when the link expires, if that comes first", () => {
    let now = NOW;
    const handles = new LinkHandles(() => now);
    const handle = handles.keep({ url: "u", key: KEY, exp: NOW + 60 }, "Practitioner/anan");
    expect(handles.get(handle, "Practitioner/anan")).toBeDefined();
    now += 60;
    expect(handles.get(handle, "Practitioner/anan")).toBeUndefined();
  });

  it("keeps twenty handles per profile and drops the oldest", () => {
    const handles = new LinkHandles(() => NOW);
    const held = Array.from({ length: 21 }, () => handles.keep({ url: "u", key: KEY, exp: NOW + 86400 }, "Practitioner/anan"));
    const other = handles.keep({ url: "u", key: KEY, exp: NOW + 86400 }, "Practitioner/other");
    expect(handles.get(held[0]!, "Practitioner/anan")).toBeUndefined();
    expect(handles.get(held[1]!, "Practitioner/anan")).toBeDefined();
    expect(handles.get(held[20]!, "Practitioner/anan")).toBeDefined();
    expect(handles.get(other, "Practitioner/other")).toBeDefined();
  });
});

describe("aLinkSignedByTheClinicAnchorIsRefused", () => {
  it("refuses a link whose signer is the list's #CSCA key", async () => {
    expect(await scan(await viewer(), link({ signedBy: "XXR_CLINIC_ANCHOR" }))).toMatchObject({ kind: "notVerified", step: "key-usage" });
  });
});

describe("aLinkFromAnUnlistedKeyIsRefused", () => {
  it("fails at the trust lookup and names the kid it looked for", async () => {
    const kid = createHash("sha256").update(new X509Certificate(read("UNKNOWN_LINK.crt.pem")).raw).digest().subarray(0, 8).toString("hex");
    expect(await scan(await viewer(), link({ signedBy: "UNKNOWN_LINK" }))).toEqual({ kind: "notVerified", step: "trust-lookup", reason: "kid-not-found", kid });
  });
});

describe("theStoresNeverMixDomains", () => {
  it("fails a link signed by the card key", async () => {
    expect(await scan(await viewer(), link({ signedBy: "XXR_CARD" }))).toMatchObject({ kind: "notVerified", step: "trust-lookup", reason: "kid-not-found" });
  });
});

describe("aCallerWithoutAMedplumSessionIsRefused", () => {
  it("refuses a scan without a valid token, before looking at the link", async () => {
    const v = await viewer();
    await expect(scanLink(v, { token: "", text: link() })).rejects.toBeInstanceOf(NoSession);
    await expect(scanLink(v, { token: "forged", text: link() })).rejects.toBeInstanceOf(NoSession);
  });

  it("asks Medplum who the token belongs to", async () => {
    const seen: { url: string; authorization: string | null }[] = [];
    const medplum = async (url: string | URL | Request, init?: RequestInit) => {
      seen.push({ url: String(url), authorization: new Headers(init?.headers).get("authorization") });
      return Response.json({ profile: { resourceType: "Practitioner", id: "anan" } });
    };
    expect(await requireSession("http://medplum:8103", "anan-token", medplum)).toBe("Practitioner/anan");
    expect(seen).toEqual([{ url: "http://medplum:8103/auth/me", authorization: "Bearer anan-token" }]);
    await expect(requireSession("http://medplum:8103", "stale", async () => new Response("", { status: 401 }))).rejects.toBeInstanceOf(NoSession);
    await expect(requireSession("http://medplum:8103", undefined, medplum)).rejects.toBeInstanceOf(NoSession);
  });

  it("accepts only a Practitioner profile", async () => {
    for (const resourceType of ["Patient", "RelatedPerson", "Device"]) {
      const medplum = async () => Response.json({ profile: { resourceType, id: "x" } });
      await expect(requireSession("http://medplum:8103", "token", medplum)).rejects.toBeInstanceOf(NoSession);
    }
  });
});

describe("theTrustListsAreChecked", () => {
  it("agrees with testkit about the pinned signers", () => {
    expect(LOCAL_SIGNER).toBe(read("gdhcn-signer-did.json"));
    expect(DEV_SIGNER).toBe(read("who", "WHO-signing-did.json"));
    expect(() => WitTrust.getInstance().verifyDidProof(read("local-test-list.json"), LOCAL_SIGNER)).not.toThrow();
    expect(() => WitTrust.getInstance().verifyDidProof(read("who", "root-did.json"), DEV_SIGNER)).not.toThrow();
  });

  it("loads neither list when its proof fails, and says why", async () => {
    const v = await viewer(async (url) => (url === LOCAL_URL ? read("local-test-list.json").replace("XXK", "XXQ") : read("who", "root-did.json")));
    expect(await scan(v, link())).toMatchObject({ kind: "notVerified", step: "trust-lookup" });
    const [local, dev] = v.trust.statuses();
    expect(local).toMatchObject({ name: "local", fetchedAt: undefined, lastFailure: { at: NOW, reason: expect.stringContaining("proof") } });
    expect(dev).toMatchObject({ name: "dev", fetchedAt: NOW, lastFailure: undefined });
  });

  it("keeps the last good list when a refresh fails", async () => {
    let down = false;
    const v = await viewer(async (url) => {
      if (down) throw new Error("connection refused");
      return url === LOCAL_URL ? read("local-test-list.json") : read("who", "root-did.json");
    });
    down = true;
    await v.trust.refresh();
    expect(await scan(v, link())).toMatchObject({ kind: "fetch" });
    expect(v.trust.statuses()[0]).toMatchObject({ fetchedAt: NOW, lastFailure: { reason: "connection refused" } });
  });
});

describe("aListIsNeverReplacedByAnOlderOne", () => {
  const listCreated = (created: string) => read("local-test-list.json").replace("2026-11-14T00:00:00Z", created);
  // The re-dated lists are not re-signed, so the proof check is stubbed; the comparison of `created` is what is under test.
  const proofHolds = () => vi.spyOn(WitTrust.getInstance(), "verifyDidProof").mockImplementation(() => undefined);

  it("compares the proof's created time and keeps the newer list", async () => {
    const spy = proofHolds();
    try {
      let served = listCreated("2026-11-14T06:00:00Z");
      const trust = new TrustLists(
        { local: { url: LOCAL_URL, signer: LOCAL_SIGNER }, dev: { url: DEV_URL, signer: DEV_SIGNER } },
        async (url) => (url === LOCAL_URL ? served : read("who", "root-did.json")),
        () => NOW,
      );
      await trust.refresh();
      served = listCreated("2026-11-14T05:00:00Z");
      await trust.refresh();
      expect(trust.statuses()[0]).toMatchObject({ fetchedAt: NOW, lastFailure: { reason: expect.stringContaining("older") } });
      served = listCreated("2026-11-14T07:00:00Z");
      await trust.refresh();
      expect(trust.statuses()[0]?.lastFailure).toBeUndefined();
    } finally {
      spy.mockRestore();
    }
  });

  it("shows a last good list older than 24 hours as stale", async () => {
    let now = NOW;
    let down = false;
    const trust = new TrustLists(
      { local: { url: LOCAL_URL, signer: LOCAL_SIGNER }, dev: { url: DEV_URL, signer: DEV_SIGNER } },
      async (url) => {
        if (down) throw new Error("connection refused");
        return url === LOCAL_URL ? read("local-test-list.json") : read("who", "root-did.json");
      },
      () => now,
    );
    await trust.refresh();
    down = true;
    now += 24 * 3600;
    await trust.refresh();
    expect(trust.statuses()[0]?.stale).toBe(false);
    now += 1;
    expect(trust.statuses()[0]?.stale).toBe(true);
  });
});
