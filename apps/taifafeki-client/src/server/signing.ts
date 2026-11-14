import { createHash, createPrivateKey, sign, X509Certificate, type KeyObject } from "node:crypto";
import { readFileSync } from "node:fs";
import { WitVhl } from "smart-trust-kotlin-hcert-core";

const ALG = "ecdsa-p256-sha256";

export interface ClinicIdentity {
  key: KeyObject;
  der: Buffer;
  keyid: string;
}

export interface Outgoing {
  method: "GET" | "POST";
  url: URL;
  contentType?: string;
  body?: Buffer;
}

// Read for each request: `trust-demo reset` issues a new certificate.
export function loadIdentity(keyFile: string, certFile: string): ClinicIdentity {
  const der = new X509Certificate(readFileSync(certFile)).raw;
  const keyid = createHash("sha256").update(der).digest().subarray(0, 8).toString("base64");
  return { key: createPrivateKey(readFileSync(keyFile)), der, keyid };
}

// RFC 9421 over @method, @path with the query, @authority with the port and, for a body, content-type and content-digest.
export function signedHeaders(identity: ClinicIdentity, request: Outgoing, created: number): Record<string, string> {
  const vhl = WitVhl.getInstance();
  const base = vhl.buildSignatureBase({
    method: request.method,
    authority: request.url.host,
    path: request.url.pathname + request.url.search,
    contentType: request.contentType,
    body: request.body && new Uint8Array(request.body),
    keyid: identity.keyid,
    alg: ALG,
    created,
  });
  const signature = sign("sha256", Buffer.from(base.base), { key: identity.key, dsaEncoding: "ieee-p1363" });
  const headers: Record<string, string> = {
    "Signature-Input": base.signatureInput,
    Signature: vhl.signatureHeader(new Uint8Array(signature), ALG),
    "Client-Cert": `:${identity.der.toString("base64")}:`,
  };
  if (request.contentType) headers["Content-Type"] = request.contentType;
  if (base.contentDigest) headers["Content-Digest"] = base.contentDigest;
  return headers;
}
