import { challengeFor, type SignInSettings } from "./begin";
import type { PkceState } from "./cookie";

export type Guard = { ok: true; challenge: string } | { ok: false; message: string };

export function checkAuthorize(
  settings: SignInSettings,
  pending: PkceState | undefined,
  query: Record<string, unknown>,
): Guard {
  if (!pending) return { ok: false, message: "Start sign-in again" };
  const challenge = challengeFor(pending.verifier);
  const matches =
    query.client_id === settings.clientId &&
    query.redirect_uri === settings.clientOrigin &&
    query.code_challenge_method === "S256" &&
    query.code_challenge === challenge &&
    query.state === pending.state;
  return matches ? { ok: true, challenge } : { ok: false, message: "This sign-in link is not valid" };
}
