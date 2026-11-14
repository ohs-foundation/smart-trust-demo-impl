import { createHash, randomBytes } from "node:crypto";
import { pkceCookie } from "./cookie";
import { SCOPE } from "./scope";

export interface SignInSettings {
  clientId: string;
  medplumBaseUrl: string;
  medplumInternalUrl: string;
  clientOrigin: string;
}

export function isSecure(settings: SignInSettings) {
  return settings.clientOrigin.startsWith("https:");
}

export function challengeFor(verifier: string) {
  return createHash("sha256").update(verifier).digest("base64url");
}

export function beginSignIn(settings: SignInSettings) {
  const pending = {
    state: randomBytes(24).toString("base64url"),
    verifier: randomBytes(64).toString("base64url"),
  };

  const location = new URL("oauth2/authorize", `${settings.medplumBaseUrl}/`);
  location.searchParams.set("response_type", "code");
  location.searchParams.set("state", pending.state);
  location.searchParams.set("client_id", settings.clientId);
  location.searchParams.set("redirect_uri", settings.clientOrigin);
  location.searchParams.set("code_challenge_method", "S256");
  location.searchParams.set("code_challenge", challengeFor(pending.verifier));
  location.searchParams.set("scope", SCOPE);

  return { location: location.toString(), cookie: pkceCookie(pending, isSecure(settings)) };
}
