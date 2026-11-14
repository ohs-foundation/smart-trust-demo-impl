// Sends a real ITI-YY5 (or ITI-68) request to a VHL service, signed the way the facility client's
// server will sign it: the manifest request comes from hcert-core's WitVhl.buildManifestRequest for
// a link the service issued, signed with the clinic key. HCERT_CORE_JS points at the built
// smart-trust-kotlin-hcert-core.mjs; run with NODE_EXTRA_CA_CERTS=stack/tls/ca.crt.
//   node send.mjs <HC1:... link> <passcode|-> [--tamper] [--created-offset <seconds>] [--document <url>] [--follow]
import { createHash, createPrivateKey, sign, X509Certificate } from "node:crypto";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const repo = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const secrets = join(repo, "stack", "taifafeki", "secrets");
const hcert = await import(process.env.HCERT_CORE_JS);
const WitVhl = hcert.WitVhl.getInstance();
const LIMITS = { maxInputChars: 20000, maxInflatedBytes: 1 << 20, maxDepth: 16, maxItems: 1000, maxStringBytes: 1 << 20 };

const [link, passcode, ...flags] = process.argv.slice(2);
const option = (name) => flags[flags.indexOf(name) + 1];
const createdOffset = flags.includes("--created-offset") ? Number(option("--created-offset")) : 0;

const certPem = readFileSync(join(secrets, "clinic-viewer.crt"), "utf8");
const key = createPrivateKey(readFileSync(join(secrets, "clinic-viewer.key"), "utf8"));
const der = new X509Certificate(certPem).raw;
const keyid = createHash("sha256").update(der).digest().subarray(0, 8).toString("base64");

const claim = hcert.WitVerifier.getInstance().readUnverified(link, new hcert.WitRegistry(), LIMITS).entries[0].payload;
const payload = claim.val;
let method;
let url;
let contentType;
let body;
if (flags.includes("--document")) {
  method = "GET";
  url = new URL(option("--document"));
} else {
  const manifest = WitVhl.buildManifestRequest(payload, "Kesaro primary care clinic", passcode === "-" ? null : passcode, null);
  method = "POST";
  url = new URL(manifest.url);
  contentType = manifest.contentType;
  body = Buffer.from(manifest.body);
}

async function send(method, url, contentType, body, tamper = false) {
  const request = {
    method,
    authority: url.host,
    path: url.pathname + url.search,
    contentType,
    body: body && new Uint8Array(body),
    keyid,
    alg: "ecdsa-p256-sha256",
    created: Math.floor(Date.now() / 1000) + createdOffset,
  };
  const base = WitVhl.buildSignatureBase(request);
  const signature = sign("sha256", Buffer.from(base.base), { key, dsaEncoding: "ieee-p1363" });

  const headers = {
    "Signature-Input": base.signatureInput,
    Signature: WitVhl.signatureHeader(new Uint8Array(signature), request.alg),
    "Client-Cert": `:${der.toString("base64")}:`,
  };
  if (contentType) headers["Content-Type"] = contentType;
  if (base.contentDigest) headers["Content-Digest"] = base.contentDigest;

  const sent = tamper && body ? Buffer.from(body.toString().replace(/passcode=\d/, "passcode=9")) : body;
  const response = await fetch(url, { method, headers, body: sent });
  console.log(`${method} ${url.pathname} -> ${response.status}`);
  return response;
}

const response = await send(method, url, contentType, body, flags.includes("--tamper"));
if (!flags.includes("--follow")) {
  console.log(await response.text());
} else if (response.status === 200) {
  const documents = WitVhl.parseManifest(await response.text());
  console.log(`manifest documents: ${documents.length}`);
  const document = await send("GET", new URL(documents[0].url), undefined, undefined);
  if (document.status === 200) {
    const decrypted = WitVhl.decrypt(await document.text(), payload.key);
    const bundle = JSON.parse(Buffer.from(decrypted.content).toString("utf8"));
    console.log(`decrypted ${bundle.resourceType} with ${bundle.entry.length} entries`);
  } else {
    console.log(await document.text());
  }
} else {
  console.log(await response.text());
}
