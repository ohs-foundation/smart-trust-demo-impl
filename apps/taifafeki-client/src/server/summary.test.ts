import { createHash, createPublicKey, verify, X509Certificate } from "node:crypto";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { WitVhl } from "smart-trust-kotlin-hcert-core";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NoSession } from "./session";
import { loadIdentity, signedHeaders } from "./signing";
import { fetchSummary, type SummaryOutcome } from "./summary";
import { LinkHandles, type Viewer } from "./viewer";

const REPO = join(import.meta.dirname, "..", "..", "..", "..");
const TESTKIT = join(REPO, "testkit");
const VHL_SOURCES = join(REPO, "services", "vhl", "src");
const NOW = Date.parse("2026-11-14T09:00:00Z") / 1000;
const HOST = "vhl.onesa.example:8443";
const KEY = "86F8LY5LlWAa1-OS_FgrTnYNqFHJP2ey5RSKLJBN9jk";
const PASSCODE = "0482";
const OWNER = "Practitioner/anan";
const SEARCH = "_id=list-1&code=folder&status=current&patient.identifier=urn%3Aietf%3Arfc%3A3986%7CPatient%2Fp1";
const LINK_URL = `https://${HOST}/vhl/List/_search?${SEARCH}`;
const REASONS = "http://smart.who.int/trust-demo/CodeSystem/TrustDemoVhlRefusalReason";
const identity = loadIdentity(join(TESTKIT, "XXK_LINK.key.pem"), join(TESTKIT, "XXK_LINK.crt.pem"));

const manifest = (documentUrl: string) =>
  JSON.stringify({
    resourceType: "Bundle",
    type: "searchset",
    entry: [
      { resource: { resourceType: "List", entry: [{ item: { reference: "DocumentReference/doc-1" } }] }, search: { mode: "match" } },
      {
        resource: { resourceType: "DocumentReference", id: "doc-1", content: [{ attachment: { url: documentUrl, contentType: "application/fhir+json" } }] },
        search: { mode: "include" },
      },
    ],
  });

const ATTEMPTS_LEFT = "http://ohs.dev/fhir/trust-demo/StructureDefinition/passcode-attempts-left";
const outcome = (system: string, code?: string, extension?: object[]) =>
  JSON.stringify({ resourceType: "OperationOutcome", issue: [{ extension, severity: "error", code: "forbidden", ...(code && { details: { coding: [{ system, code }] } }) }] });

interface Seen {
  url: string;
  method: string;
  headers: Record<string, string>;
  body?: string;
  redirect?: RequestRedirect;
}

function onesa(answer: (seen: Seen) => Response | undefined = () => undefined) {
  const seen: Seen[] = [];
  const send = async (url: string | URL | Request, init?: RequestInit) => {
    const call: Seen = {
      url: String(url),
      method: init?.method ?? "GET",
      headers: Object.fromEntries(new Headers(init?.headers)),
      body: init?.body ? Buffer.from(init.body as Uint8Array).toString() : undefined,
      redirect: init?.redirect,
    };
    seen.push(call);
    return (
      answer(call) ??
      (call.method === "POST" ? new Response(manifest(`https://${HOST}/vhl/Binary/doc-1`)) : new Response("eyJhbGciOiJkaXIifQ..iv.ct.tag", { headers: { "Content-Type": "application/jose" } }))
    );
  };
  return { seen, send: send as typeof fetch };
}

function setup(answer?: (seen: Seen) => Response | undefined, link: { url?: string; flag?: string; host?: string } = {}) {
  const fake = onesa(answer);
  const handles = new LinkHandles(() => NOW);
  const handle = handles.keep({ url: link.url ?? LINK_URL, flag: link.flag ?? "LP", exp: NOW + 86400 }, OWNER);
  const viewer: Pick<Viewer, "handles" | "session" | "now" | "clinic"> = {
    handles,
    now: () => NOW,
    session: async (token) => {
      if (token !== "anan-token") throw new NoSession();
      return OWNER;
    },
    clinic: { hosts: [link.host ?? HOST], identity: () => identity, send: fake.send },
  };
  const call = (passcode = PASSCODE, token = "anan-token", use = handle) => fetchSummary(viewer, { token, handle: use, passcode });
  return { ...fake, handle, handles, viewer, call };
}

afterEach(() => vi.restoreAllMocks());

describe("aCallerWithoutASessionIsRefused", () => {
  it("refuses before anything is sent", async () => {
    const { call, seen } = setup();
    await expect(call(PASSCODE, "")).rejects.toBeInstanceOf(NoSession);
    await expect(call(PASSCODE, "forged")).rejects.toBeInstanceOf(NoSession);
    expect(seen).toEqual([]);
  });

  it("refuses another profile's handle", async () => {
    const { handle, viewer, seen } = setup();
    const other = { ...viewer, session: async () => "Practitioner/someone-else" };
    expect(await fetchSummary(other, { token: "t", handle, passcode: PASSCODE })).toMatchObject({ kind: "refused" });
    expect(seen).toEqual([]);
  });
});

describe("aLinkOffTheAllowListIsRefused", () => {
  const withHost = (host: string, scheme = "https") => `${scheme}://${host}/vhl/List/_search?${SEARCH}`;
  it.each([
    ["another host", withHost("vhl.evil.example:8443")],
    ["another port", withHost("vhl.onesa.example:9443")],
    ["no port", withHost("vhl.onesa.example")],
    ["userinfo", withHost(`user:pass@${HOST}`)],
    ["userinfo naming the host", withHost(`${HOST}@vhl.evil.example`)],
    ["http", withHost(HOST, "http")],
    ["a backslash", `https://vhl.evil.example\\@${HOST}/vhl/List/_search?${SEARCH}`],
  ])("sends nothing for %s", async (_name, url) => {
    const { call, seen } = setup(undefined, { url });
    expect(await call()).toMatchObject({ kind: "refused" });
    expect(seen).toEqual([]);
  });

  it("fetches no document the manifest places off the list", async () => {
    const { call, seen } = setup((request) => (request.method === "POST" ? new Response(manifest("https://vhl.evil.example:8443/vhl/Binary/doc-1")) : undefined));
    expect(await call()).toMatchObject({ kind: "refused" });
    expect(seen.map((request) => request.method)).toEqual(["POST"]);
  });
});

describe("aLiveHostWithoutAPort", () => {
  const LIVE = "vhl.onesa.demo.example.org";
  const live = (url: string) => `https://${LIVE}${url}`;

  it.each([["no port", `https://${LIVE}`], ["an explicit default port", `https://${LIVE}:443`]])("signs and fetches for %s", async (_name, origin) => {
    const url = `${origin}/vhl/List/_search?${SEARCH}`;
    const { call, seen } = setup((request) => (request.method === "POST" ? new Response(manifest(live("/vhl/Binary/doc-1"))) : undefined), { url, host: LIVE });
    expect(await call()).toMatchObject({ kind: "released" });
    expect(seen.map((request) => new URL(request.url).host)).toEqual([LIVE, LIVE]);
  });

  it("still refuses a demo port", async () => {
    const { call, seen } = setup(undefined, { url: `https://${LIVE}:8443/vhl/List/_search?${SEARCH}`, host: LIVE });
    expect(await call()).toMatchObject({ kind: "refused" });
    expect(seen).toEqual([]);
  });
});

describe("aSharedSummaryIsFetched", () => {
  it("sends a signed manifest request, then a signed GET for each document, and returns only the encrypted documents", async () => {
    const { call, seen } = setup();
    const result = await call();
    expect(result).toEqual({ kind: "released", documents: [{ contentType: "application/fhir+json", jwe: "eyJhbGciOiJkaXIifQ..iv.ct.tag" }] });
    expect(JSON.stringify(result)).not.toContain(KEY);
    expect(JSON.stringify(result)).not.toMatch(/"key"\s*:/);

    const [post, get] = seen;
    expect(post).toMatchObject({ method: "POST", url: `https://${HOST}/vhl/List/_search`, redirect: "error" });
    expect(post?.headers["content-type"]).toBe("application/x-www-form-urlencoded");
    expect(new URLSearchParams(post?.body).get("passcode")).toBe(PASSCODE);
    expect(new URLSearchParams(post?.body).get("recipient")).toBe("Kesaro primary care clinic");
    expect(get).toMatchObject({ method: "GET", url: `https://${HOST}/vhl/Binary/doc-1`, redirect: "error" });
    expect(get?.headers["content-type"]).toBeUndefined();
    for (const request of seen) expect(request.headers).toMatchObject({ signature: expect.stringMatching(/^sig1=:/), "signature-input": expect.any(String), "client-cert": expect.stringMatching(/^:.+:$/) });
  });

  it("sends no passcode for a link without the P flag", async () => {
    const { call, seen } = setup(undefined, { flag: "L" });
    expect(await call("")).toMatchObject({ kind: "released" });
    expect(new URLSearchParams(seen[0]?.body).has("passcode")).toBe(false);
  });
});

describe("theSignatureBaseIsWhatTheServiceVerifies", () => {
  const read = (...path: string[]) => readFileSync(join(VHL_SOURCES, ...path), "utf8");
  const verifierSource = read("main", "kotlin", "dev", "ohs", "trustdemo", "vhl", "SignedRequestVerifier.kt");
  const signerSource = read("test", "kotlin", "dev", "ohs", "trustdemo", "vhl", "Signing.kt");
  const BODY = Buffer.from("_id=list-1&recipient=Kesaro+clinic&passcode=0482");
  const request = { method: "POST", url: new URL(`https://vhl.onesa.test:8443/vhl/List/_search`), contentType: "application/x-www-form-urlencoded", body: BODY } as const;
  const digest = `sha-256=:${createHash("sha256").update(BODY).digest("base64")}:`;
  const params = `("@method" "@path" "@authority" "content-type" "content-digest");created=${NOW};keyid="${identity.keyid}";alg="ecdsa-p256-sha256"`;
  const expectedBase = [
    '"@method": POST',
    '"@path": /vhl/List/_search',
    '"@authority": vhl.onesa.test:8443',
    '"content-type": application/x-www-form-urlencoded',
    `"content-digest": ${digest}`,
    `"@signature-params": ${params}`,
  ].join("\n");

  it("reads the service's source for the pieces the client must match", () => {
    expect(verifierSource).toContain("Hcert.vhl.buildSignatureBase(");
    expect(verifierSource).toContain("authority = authority,");
    expect(verifierSource).toContain("path = request.target,");
    expect(verifierSource).toContain('contentType = request.header("Content-Type"),');
    expect(verifierSource).toContain("body = request.body,");
    expect(verifierSource).toContain('private const val ALGORITHM = "ecdsa-p256-sha256"');
    expect(signerSource).toContain('const val FORM = "application/x-www-form-urlencoded"');
  });

  it("signs the RFC 9421 base for the request, which the verifier rebuilds from the received request", () => {
    const headers = signedHeaders(identity, request, NOW);
    expect(headers["Signature-Input"]).toBe(`sig1=${params}`);
    expect(headers["Content-Digest"]).toBe(digest);

    const verifierPattern = /private val SIGNATURE_INPUT =\s*Regex\(\s*"""(.+?)"""\s*\)/s.exec(verifierSource)?.[1];
    const parsed = new RegExp(`^${verifierPattern}$`).exec(headers["Signature-Input"] ?? "");
    expect(parsed?.slice(1)).toEqual([String(NOW), identity.keyid, "ecdsa-p256-sha256"]);

    const rebuilt = WitVhl.getInstance().buildSignatureBase({
      method: "POST",
      authority: "vhl.onesa.test:8443",
      path: "/vhl/List/_search",
      contentType: headers["Content-Type"],
      body: new Uint8Array(BODY),
      keyid: parsed?.[2] ?? "",
      alg: parsed?.[3] ?? "",
      created: Number(parsed?.[1]),
    });
    expect(rebuilt.signatureInput).toBe(headers["Signature-Input"]);
    expect(rebuilt.contentDigest).toBe(headers["Content-Digest"]);
    expect(Buffer.from(rebuilt.base).toString()).toBe(expectedBase);

    const raw = Buffer.from(headers.Signature?.replace(/^sig1=:|:$/g, "") ?? "", "base64");
    const publicKey = createPublicKey(new X509Certificate(readFileSync(join(TESTKIT, "XXK_LINK.crt.pem"))).publicKey.export({ type: "spki", format: "pem" }));
    expect(raw).toHaveLength(64);
    expect(verify("sha256", Buffer.from(rebuilt.base), { key: publicKey, dsaEncoding: "ieee-p1363" }, raw)).toBe(true);
    expect(headers["Client-Cert"]).toBe(`:${new X509Certificate(readFileSync(join(TESTKIT, "XXK_LINK.crt.pem"))).raw.toString("base64")}:`);
  });

  it("covers the query in the path and sends no body headers for a GET", () => {
    const headers = signedHeaders(identity, { method: "GET", url: new URL("https://vhl.onesa.test:8443/vhl/Binary/doc-1?x=1") }, NOW);
    expect(headers["Signature-Input"]).toBe(`sig1=("@method" "@path" "@authority");created=${NOW};keyid="${identity.keyid}";alg="ecdsa-p256-sha256"`);
    expect(headers["Content-Digest"]).toBeUndefined();
    expect(headers["Content-Type"]).toBeUndefined();
  });
});

describe("aRefusalFromOnesaIsAPlainMessage", () => {
  const refusal = (status: number, body: string) => (request: Seen) => (request.method === "POST" ? new Response(body, { status }) : undefined);
  it.each([
    ["withdrawn", "The family withdrew this share."],
    ["expired", "The share has expired."],
    ["wrong-passcode", "Wrong passcode."],
  ])("says %s", async (code, message) => {
    expect(await setup(refusal(403, outcome(REASONS, code))).call()).toEqual({ kind: "refused", message, status: 403, reason: code });
  });

  it("says the link is locked after three wrong passcodes", async () => {
    const body = outcome("http://ohs.dev/fhir/trust-demo/CodeSystem/vhl-refusal-reason", "attempts-exhausted");
    expect(await setup(refusal(403, body)).call()).toEqual({ kind: "refused", message: "Three wrong passcodes; the link is locked.", status: 403, reason: "attempts-exhausted" });
  });

  it("says how many passcode attempts Onesa has left", async () => {
    const body = outcome(REASONS, "wrong-passcode", [{ url: ATTEMPTS_LEFT, valueUnsignedInt: 2 }]);
    expect(await setup(refusal(403, body)).call()).toEqual({ kind: "refused", message: "Wrong passcode.", status: 403, reason: "wrong-passcode", attemptsLeft: 2 });
  });

  it.each([["a string", "2"], ["a negative number", -1], ["a fraction", 1.5], ["another extension's value", undefined]])("ignores attempts left given as %s", async (_, value) => {
    const extension = value === undefined ? [{ url: `${ATTEMPTS_LEFT}-other`, valueUnsignedInt: 2 }] : [{ url: ATTEMPTS_LEFT, valueUnsignedInt: value }];
    const result = await setup(refusal(403, outcome(REASONS, "wrong-passcode", extension))).call();
    expect(result).toEqual({ kind: "refused", message: "Wrong passcode.", status: 403, reason: "wrong-passcode" });
  });

  it("says there is nothing to release for a bare 403", async () => {
    expect(await setup(refusal(403, outcome(REASONS))).call()).toEqual({ kind: "refused", message: "Nothing to release.", status: 403 });
    expect(await setup(refusal(403, "")).call()).toEqual({ kind: "refused", message: "Nothing to release.", status: 403 });
  });

  it("treats a reason named like an object property as no reason", async () => {
    expect(await setup(refusal(403, outcome(REASONS, "constructor"))).call()).toEqual({ kind: "refused", message: "Nothing to release.", status: 403 });
  });

  it("refuses a reply larger than the client accepts", async () => {
    const huge = (request: Seen) => (request.method === "GET" ? new Response("x".repeat(5_000_001)) : undefined);
    expect(await setup(huge).call()).toEqual({ kind: "refused", message: "Onesa sent more than the client accepts." });
  });

  it("says so when the clinic key cannot be read", async () => {
    const { viewer, handle } = setup();
    viewer.clinic = {
      ...viewer.clinic,
      identity: () => {
        throw new Error("EACCES");
      },
    };
    expect(await fetchSummary(viewer, { token: "anan-token", handle, passcode: PASSCODE })).toEqual({ kind: "refused", message: "The clinic key cannot be read on this server." });
  });

  it("names the status for anything else", async () => {
    expect(await setup(refusal(401, outcome(REASONS))).call()).toEqual({ kind: "refused", message: "Onesa refused the request (HTTP 401).", status: 401 });
    expect(await setup(refusal(503, "down")).call()).toEqual({ kind: "refused", message: "Onesa refused the request (HTTP 503).", status: 503 });
  });

  it("explains a refused document the same way", async () => {
    const result = await setup((request) => (request.method === "GET" ? new Response(outcome(REASONS, "withdrawn"), { status: 403 }) : undefined)).call();
    expect(result).toEqual({ kind: "refused", message: "The family withdrew this share.", status: 403, reason: "withdrawn" });
  });

  it("lets a wrong passcode be retried with the same handle", async () => {
    let wrong = true;
    const { call } = setup((request) => (request.method === "POST" && wrong ? new Response(outcome(REASONS, "wrong-passcode"), { status: 403 }) : undefined));
    expect(await call("1111")).toMatchObject({ kind: "refused", message: "Wrong passcode." });
    wrong = false;
    expect(await call(PASSCODE)).toMatchObject({ kind: "released" });
  });

  it("says so when Onesa cannot be reached or redirects", async () => {
    const result = await setup(() => {
      throw new TypeError("fetch failed");
    }).call();
    expect(result).toEqual({ kind: "refused", message: "Onesa could not be reached." });
  });
});

describe("thePasscodeIsNeverLogged", () => {
  it("keeps the passcode and the key out of every console call and refusal", async () => {
    const spies = (["log", "info", "warn", "error", "debug"] as const).map((level) => vi.spyOn(console, level).mockImplementation(() => undefined));
    const wrong = "7391";
    const results: SummaryOutcome[] = [
      await setup().call(wrong),
      await setup(() => new Response(outcome(REASONS, "wrong-passcode"), { status: 403 })).call(wrong),
      await setup(() => new Response(`echo ${wrong} ${KEY}`, { status: 500 })).call(wrong),
      await setup(() => {
        throw new Error(`boom ${wrong} ${KEY}`);
      }).call(wrong),
    ];
    const logged = JSON.stringify(spies.flatMap((spy) => spy.mock.calls));
    expect(logged).not.toContain(wrong);
    expect(logged).not.toContain(KEY);
    for (const result of results.filter((it) => it.kind === "refused")) {
      expect(JSON.stringify(result)).not.toContain(wrong);
      expect(JSON.stringify(result)).not.toContain(KEY);
    }
  });
});
