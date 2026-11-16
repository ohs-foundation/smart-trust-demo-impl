import { WitRegistry, WitVerifier } from "smart-trust-kotlin-hcert-core";
import { hex, LIMITS } from "./readCard";
import type { LinkStore } from "./trustLists";

const OPTIONS = { limits: LIMITS, clockSkewSeconds: 300 };

export interface Link {
  url: string;
  flag?: string;
  label?: string;
  exp: number;
}

export type Checked =
  | { kind: "notVerified"; step: string; reason: string; kid?: string }
  | { kind: "expired"; exp: number }
  | { kind: "fetch"; link: Link; issuer: string };

const notVerified = (step: string, reason: string): Checked => ({ kind: "notVerified", step, reason });

// The link's key signed into the certificate is read by the verifier and dropped here; the browser reads its own copy.
// DT.06: failed is paper, verified but expired is paper, verified and current is fetched.
export function checkLink(hc1: string, links: LinkStore | undefined, now: number): Checked {
  if (!links) return notVerified("trust-lookup", "the link trust list has not been loaded");
  const verifier = WitVerifier.getInstance();
  const registry = new WitRegistry();
  let result = verifier.verify(hc1, links.store, registry, BigInt(now), OPTIONS);
  // The verifier reports a passed link expiry as a failure; at the certificate's own issue time the signature can still be checked.
  const lapsed = result.tag === "failed" && result.val.step === "time" && result.val.reason === "expired";
  if (lapsed) {
    const issuedAt = verifier.readUnverified(hc1, registry, LIMITS).iat;
    if (issuedAt !== undefined) result = verifier.verify(hc1, links.store, registry, issuedAt, OPTIONS);
  }
  if (result.tag === "failed") return { ...notVerified(result.val.step, result.val.reason), ...kidOf(hc1, registry) };

  const signer = Buffer.from(result.val.dsc.der).toString("base64");
  if (links.anchors.has(signer)) return notVerified("key-usage", "the signer is the clinic anchor, which certifies keys and does not sign links");
  const entry = result.val.entries.find((it) => it.payload.tag === "vhl");
  if (!entry) return notVerified("payload", "the certificate holds no link");
  if (!entry.keyUsageAuthorised) return notVerified("key-usage", "the signer's key usage does not allow a link");

  const vhl = entry.payload.val;
  const exp = Math.min(Number(result.val.exp), vhl.exp === undefined ? Infinity : Number(vhl.exp));
  if (lapsed) return { kind: "expired", exp };
  return { kind: "fetch", link: { url: vhl.url, flag: vhl.flag, label: vhl.label, exp }, issuer: result.val.dsc.participant ?? result.val.iss };
}

function kidOf(hc1: string, registry: WitRegistry): { kid?: string } {
  try {
    const { kid } = WitVerifier.getInstance().readUnverified(hc1, registry, LIMITS);
    return kid ? { kid: hex(kid) } : {};
  } catch {
    return {};
  }
}
