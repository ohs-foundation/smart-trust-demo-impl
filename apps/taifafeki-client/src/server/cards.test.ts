import { createHash, createPrivateKey, sign, X509Certificate } from "node:crypto";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { WitIssuer, WitRegistry } from "smart-trust-kotlin-hcert-core";
import { describe, expect, it, vi } from "vitest";
import { NoSession } from "./session";
import { checkCardRequest, readCardsFor } from "./cardReads";
import { readCard } from "./readCard";
import { DEV_SIGNER, LOCAL_SIGNER, TrustLists } from "./trustLists";

const TESTKIT = join(import.meta.dirname, "..", "..", "..", "..", "testkit");
const read = (...path: string[]) => readFileSync(join(TESTKIT, ...path), "utf8");
const NOW = Date.parse("2026-11-14T00:00:00Z") / 1000;
const kidOf = (stand: string) => createHash("sha256").update(new X509Certificate(read(`${stand}.crt.pem`)).raw).digest().subarray(0, 8).toString("hex");

function card(signedBy: string): string {
  const pending = WitIssuer.getInstance().prepare(
    {
      payloads: [{ tag: "dvc-min", val: { n: "Somsak SENGMANY", dob: "2025-10-28", s: "male", nt: "XXR", vx: { vp: "a3K3X000006MiiXUAS", dv: "2025-10-28", is: "TrustDemoKesaroHospital", bo: "XXR-4821" }, v: "1.0.0", extensions: [] } }],
      iss: "XR",
      validity: { iat: BigInt(NOW), exp: BigInt(NOW + 86400) },
      dscDer: new X509Certificate(read(`${signedBy}.crt.pem`)).raw,
      alg: "es256",
      allowDevelopmentSubclaims: true,
    },
    new WitRegistry(),
  );
  const signature = sign("sha256", Buffer.from(pending.toBeSigned()), { key: createPrivateKey(read(`${signedBy}.key.pem`)), dsaEncoding: "ieee-p1363" });
  return WitIssuer.getInstance().finish(pending, new Uint8Array(signature)).hc1;
}

async function trustLists() {
  const trust = new TrustLists(
    { local: { url: "local", signer: LOCAL_SIGNER }, dev: { url: "dev", signer: DEV_SIGNER } },
    async (url) => (url === "local" ? read("local-test-list.json") : read("who", "root-did.json")),
    () => NOW,
  );
  await trust.refresh();
  return trust;
}

describe("readCardTexts", () => {
  it("refuses a request with no session and reads nothing", async () => {
    const session = vi.fn(async () => Promise.reject(new NoSession()));
    const read = vi.fn();
    await expect(readCardsFor({ session }, read, { token: "", hospitalNumber: "CRH-HN-68-104377", texts: [] })).rejects.toBeInstanceOf(NoSession);
    expect(read).not.toHaveBeenCalled();
  });

  it("refuses an empty, blank or over-long hospital number and a non-array of texts", () => {
    const ok = { token: "t", hospitalNumber: "CRH-HN-68-104377", texts: [] };
    expect(checkCardRequest(ok)).toEqual(ok);
    for (const hospitalNumber of ["", "   ", "x".repeat(65)]) expect(() => checkCardRequest({ ...ok, hospitalNumber })).toThrow();
    expect(() => checkCardRequest({ ...ok, texts: "nope" })).toThrow();
    expect(() => checkCardRequest(null)).toThrow();
  });
});

describe("aCardSaysWhoSignedIt", () => {
  it("gives the card's kid, issuer and algorithm, and the participant the trust list holds that kid for", async () => {
    const trust = await trustLists();
    expect(readCard("hepb", card("XXR_CARD"), (kid) => trust.signerOf(kid))).toEqual({
      doseId: "hepb",
      hc1: expect.stringMatching(/^HC1:/),
      holder: "Somsak SENGMANY",
      birth: "2025-10-28",
      product: "a3K3X000006MiiXUAS",
      date: "2025-10-28",
      lot: "XXR-4821",
      kid: kidOf("XXR_CARD"),
      issuer: "XR",
      alg: "ES256",
      signer: "XXR",
    });
  });

  it("names no signer for a key on no list", async () => {
    const trust = await trustLists();
    const unlisted = readCard("hepb", card("UNKNOWN_LINK"), (kid) => trust.signerOf(kid));
    expect(unlisted).toMatchObject({ kid: kidOf("UNKNOWN_LINK"), issuer: "XR" });
    expect(unlisted.signer).toBeUndefined();
  });
});

describe("theHospitalsParticipation", () => {
  it("lists the participants on each trust list", async () => {
    const [local, dev] = (await trustLists()).statuses();
    expect(local?.participants).toEqual(["XXR", "XXU"]);
    expect(dev?.participants).toContain("XXR");
    expect(dev?.participants).not.toContain("trustlist");
  });
});
