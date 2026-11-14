import { WitCodec, WitCose, WitRegistry, WitVerifier } from "smart-trust-kotlin-hcert-core";
import type { Card } from "../hospital/cards";

export const LIMITS = { maxInputChars: 20000, maxInflatedBytes: 1 << 20, maxDepth: 16, maxItems: 1000, maxStringBytes: 1 << 20 };

interface Dvc {
  n: string;
  dob: string;
  vx: { vp: string; dv: string; bo?: string };
}

export const hex = (bytes: Uint8Array) => Buffer.from(bytes).toString("hex");

// Display only: these are the clinic's own cards, so the signature is not checked here.
export function readCard(doseId: string, hc1: string, signerOf: (kid: Uint8Array) => string | undefined = () => undefined): Card {
  const card = WitVerifier.getInstance().readUnverified(hc1, new WitRegistry(), LIMITS);
  const entry = card.entries[0];
  if (!entry) throw new Error(`The card for ${doseId} holds no claim.`);
  const dvc = entry.payload.val as Dvc;
  const { alg } = WitCose.getInstance().parseSign1(WitCodec.getInstance().fromHc1(hc1, LIMITS), LIMITS);
  return {
    doseId,
    hc1,
    holder: dvc.n,
    birth: dvc.dob,
    product: dvc.vx.vp,
    date: dvc.vx.dv,
    lot: dvc.vx.bo ?? "",
    kid: card.kid ? hex(card.kid) : "",
    issuer: card.iss ?? "",
    alg: alg.toUpperCase(),
    signer: card.kid && signerOf(card.kid),
  };
}
