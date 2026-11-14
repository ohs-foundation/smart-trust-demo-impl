import { WitRegistry, WitVerifier } from "smart-trust-kotlin-hcert-core";
import type { Card } from "../hospital/cards";

const LIMITS = { maxInputChars: 20000, maxInflatedBytes: 1 << 20, maxDepth: 16, maxItems: 1000, maxStringBytes: 1 << 20 };

interface Dvc {
  n: string;
  dob: string;
  vx: { vp: string; dv: string; bo?: string };
}

// Display only: these are the clinic's own cards, so the signature is not checked here.
export function readCard(doseId: string, hc1: string): Card {
  const entry = WitVerifier.getInstance().readUnverified(hc1, new WitRegistry(), LIMITS).entries[0];
  if (!entry) throw new Error(`The card for ${doseId} holds no claim.`);
  const dvc = entry.payload.val as Dvc;
  return { doseId, hc1, holder: dvc.n, birth: dvc.dob, product: dvc.vx.vp, date: dvc.vx.dv, lot: dvc.vx.bo ?? "" };
}
