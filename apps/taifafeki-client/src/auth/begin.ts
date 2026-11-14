import { createHash, randomBytes } from "node:crypto";
import { PKCE_COOKIE, type PkceState } from "./cookie";

export interface SignInSettings {
  clientId: string;
  medplumBaseUrl: string;
  clientOrigin: string;
}

// The SDK's own startPkce needs a browser's window and silently falls back to a plain challenge.
export function beginSignIn(settings: SignInSettings) {
  const state = randomBytes(24).toString("base64url");
  const verifier = randomBytes(64).toString("base64url");
  const challenge = createHash("sha256").update(verifier).digest("base64url");

  const location = new URL("oauth2/authorize", `${settings.medplumBaseUrl}/`);
  location.searchParams.set("response_type", "code");
  location.searchParams.set("state", state);
  location.searchParams.set("client_id", settings.clientId);
  location.searchParams.set("redirect_uri", settings.clientOrigin);
  location.searchParams.set("code_challenge_method", "S256");
  location.searchParams.set("code_challenge", challenge);
  location.searchParams.set("scope", "openid profile");

  // Readable by the page so the SDK, which keeps its verifier in storage, can finish the exchange.
  const value = encodeURIComponent(JSON.stringify({ state, verifier } satisfies PkceState));
  return {
    location: location.toString(),
    cookie: `${PKCE_COOKIE}=${value}; Path=/; SameSite=Lax; Max-Age=600`,
  };
}
