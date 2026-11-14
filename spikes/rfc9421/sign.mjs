// Signs an ITI-YY5-shaped request the way the facility client's server will, through hcert-core's
// Node build, and writes it as a fixture for the JVM test. HCERT_CORE_JS points at the built
// smart-trust-kotlin-hcert-core.mjs (gradle -p tools/hcert-kotlin :hcert-core:compileProductionLibraryKotlinJs).
import { createHash, createPrivateKey, sign, X509Certificate } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const repo = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const secrets = join(repo, "stack", "taifafeki", "secrets");
const WitVhl = (await import(process.env.HCERT_CORE_JS)).WitVhl.getInstance();

const certPem = readFileSync(join(secrets, "clinic-viewer.crt"), "utf8");
const key = createPrivateKey(readFileSync(join(secrets, "clinic-viewer.key"), "utf8"));
const der = new X509Certificate(certPem).raw;
const keyid = createHash("sha256").update(der).digest().subarray(0, 8).toString("base64");

const body = Buffer.from(JSON.stringify({ recipient: "Kesaro primary care clinic", passcode: "0482" }));
const request = {
  method: "POST",
  authority: "vhl.onesa.example",
  path: "/vhl/manifest/7d1f0c6e",
  contentType: "application/json",
  body: new Uint8Array(body),
  keyid,
  alg: "ecdsa-p256-sha256",
  created: 1792540800,
};
const base = WitVhl.buildSignatureBase(request);
const signature = sign("sha256", Buffer.from(base.base), { key, dsaEncoding: "ieee-p1363" });

const out = join(repo, "libs", "trustkit", "src", "test", "resources", "rfc9421", "signed-request.json");
mkdirSync(dirname(out), { recursive: true });
writeFileSync(
  out,
  JSON.stringify(
    {
      method: request.method,
      authority: request.authority,
      path: request.path,
      body: body.toString("base64"),
      headers: {
        "Content-Type": request.contentType,
        "Content-Digest": base.contentDigest,
        "Signature-Input": base.signatureInput,
        Signature: WitVhl.signatureHeader(new Uint8Array(signature), request.alg),
        "Client-Cert": `:${der.toString("base64")}:`,
      },
    },
    null,
    2,
  ) + "\n",
);
console.log(`wrote ${out}`);
