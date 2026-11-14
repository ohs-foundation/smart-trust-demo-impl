import { createCipheriv, randomBytes } from "node:crypto";
import { deflateRawSync } from "node:zlib";
import { WitVhl } from "smart-trust-kotlin-hcert-core";
import { describe, expect, it } from "vitest";
import { ig } from "../testing/ig";
import { Unreadable, openSummary, readSummary } from "./summary";

const IPS = ig("Bundle-TrustDemoJ4Ips.json");
const vhl = WitVhl.getInstance();
const base64url = (data: Uint8Array | string) => Buffer.from(data).toString("base64url");

function seal(header: object, key: string, content: Uint8Array, parts: { encryptedKey?: string } = {}) {
  const protectedHeader = base64url(JSON.stringify(header));
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", Buffer.from(key, "base64url"), iv);
  cipher.setAAD(Buffer.from(protectedHeader));
  const text = Buffer.concat([cipher.update(content), cipher.final()]);
  return `${protectedHeader}.${parts.encryptedKey ?? ""}.${base64url(iv)}.${base64url(text)}.${base64url(cipher.getAuthTag())}`;
}

const document = (jwe: string) => [{ contentType: "application/jose", jwe }];

describe("theSummaryDecryptsAsHcertCoreEncryptedIt", () => {
  it("gives the JSON hcert-core sealed", async () => {
    const key = vhl.newKey();
    const jwe = vhl.encrypt(Buffer.from(JSON.stringify(IPS)), "application/fhir+json", key);
    expect(await openSummary(document(jwe), key)).toEqual(IPS);
  });

  it("inflates a zip DEF document the way hcert-core does", async () => {
    const key = vhl.newKey();
    const jwe = seal({ alg: "dir", enc: "A256GCM", zip: "DEF", cty: "application/fhir+json" }, key, deflateRawSync(JSON.stringify(IPS)));
    expect(JSON.parse(Buffer.from(vhl.decrypt(jwe, key).content).toString())).toEqual(IPS);
    expect(await openSummary(document(jwe), key)).toEqual(IPS);
  });
});

describe("aJweWithAnotherAlgorithmIsRefused", () => {
  const key = vhl.newKey();
  const content = Buffer.from(JSON.stringify(IPS));
  it.each([
    ["alg", { alg: "A256KW", enc: "A256GCM" }],
    ["enc", { alg: "dir", enc: "A128GCM" }],
    ["zip", { alg: "dir", enc: "A256GCM", zip: "GZIP" }],
    ["none", { alg: "none", enc: "A256GCM" }],
  ])("refuses another %s", async (_, header) => {
    await expect(openSummary(document(seal(header, key, content)), key)).rejects.toBeInstanceOf(Unreadable);
  });

  it("refuses an encrypted key part and a short JWE", async () => {
    const jwe = seal({ alg: "dir", enc: "A256GCM" }, key, content, { encryptedKey: "AAAA" });
    await expect(openSummary(document(jwe), key)).rejects.toBeInstanceOf(Unreadable);
    await expect(openSummary(document("a.b.c"), key)).rejects.toBeInstanceOf(Unreadable);
  });
});

describe("aJweIsStrictCompactBase64url", () => {
  const key = vhl.newKey();
  const jwe = vhl.encrypt(Buffer.from(JSON.stringify(IPS)), "application/fhir+json", key);
  const [header = "", encryptedKey, iv, text = "", tag] = jwe.split(".");
  const rebuilt = (part: string) => [header, encryptedKey, iv, part, tag].join(".");

  it.each([
    ["padding", `${text}=`],
    ["a plus", `${text}+`],
    ["a slash", `${text}/`],
    ["whitespace", `${text.slice(0, 4)} ${text.slice(4)}`],
  ])("refuses %s in a part", async (_, part) => {
    await expect(openSummary(document(rebuilt(part)), key)).rejects.toBeInstanceOf(Unreadable);
  });

  it("refuses a crit header", async () => {
    const content = Buffer.from(JSON.stringify(IPS));
    const crit = seal({ alg: "dir", enc: "A256GCM", crit: ["exp"], exp: 1 }, key, content);
    await expect(openSummary(document(crit), key)).rejects.toBeInstanceOf(Unreadable);
  });
});

describe("aSummaryThatDoesNotOpen", () => {
  const key = vhl.newKey();
  const jwe = vhl.encrypt(Buffer.from(JSON.stringify(IPS)), "application/fhir+json", key);

  it("is refused for another key, an altered part, a bad key and a non-Bundle", async () => {
    await expect(openSummary(document(jwe), vhl.newKey())).rejects.toBeInstanceOf(Unreadable);
    const [header, empty, iv, text, tag] = jwe.split(".");
    await expect(openSummary(document([header, empty, iv, `A${text?.slice(1)}`, tag].join(".")), key)).rejects.toBeInstanceOf(Unreadable);
    await expect(openSummary(document(jwe), "short")).rejects.toBeInstanceOf(Unreadable);
    const note = vhl.encrypt(Buffer.from(JSON.stringify({ resourceType: "Patient" })), "application/fhir+json", key);
    await expect(openSummary(document(note), key)).rejects.toBeInstanceOf(Unreadable);
  });

  it("names neither the key nor the ciphertext in its message", async () => {
    const error = await openSummary(document(jwe), vhl.newKey()).catch((caught: Error) => caught);
    expect((error as Error).message).not.toContain(key);
    for (const part of jwe.split(".").filter(Boolean)) expect((error as Error).message).not.toContain(part);
  });
});

describe("readSummary", () => {
  it("shows the patient, each dose with its performer, and the summary's date and source", () => {
    const view = readSummary(IPS);
    expect(view.patient).toEqual({ name: "Somsak SENGMANY", birthDate: "2025-10-28", sex: "male" });
    expect(view.immunizations).toHaveLength(6);
    expect(view.immunizations[0]).toEqual({
      vaccine: "Hepatitis B paediatric birth dose",
      date: "2025-10-28T09:15:00+07:00",
      dose: "1",
      performer: "Practitioner/TrustDemoAnan",
    });
    expect(view.date).toBe("2026-10-26T09:00:00+07:00");
    expect(view.source).toBe("Onesa national health portal (Country B, XXK)");
  });
});
