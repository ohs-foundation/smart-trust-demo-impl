import { createPrivateKey, sign, X509Certificate } from "node:crypto";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { WitIssuer, WitRegistry } from "smart-trust-kotlin-hcert-core";

const TESTKIT = join(import.meta.dirname, "..", "..", "..", "..", "testkit");
const read = (file: string) => readFileSync(join(TESTKIT, file), "utf8");

export const NOW = Date.parse("2026-11-14T00:00:00Z") / 1000;
export const KEY = "86F8LY5LlWAa1-OS_FgrTnYNqFHJP2ey5RSKLJBN9jk";

interface Link {
  key?: string;
  signedBy?: string;
  exp?: number;
  flag?: string;
  hcertExp?: number;
}

export function issueLink({ key = KEY, signedBy = "XXU_LINK", exp = NOW + 86400 * 30, flag = "LP", hcertExp = NOW + 86400 }: Link = {}): string {
  const registry = new WitRegistry();
  const pending = WitIssuer.getInstance().prepare(
    {
      payloads: [{ tag: "vhl", val: { url: "https://vhl.onesa.example:8443/vhl/List/_search?_id=abc", key, exp: BigInt(exp), flag, label: "Somsak's health summary", v: 1 } }],
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
