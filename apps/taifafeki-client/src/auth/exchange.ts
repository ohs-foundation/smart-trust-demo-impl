import { isSecure, type SignInSettings } from "./begin";
import { clearedPkceCookie, type PkceState } from "./cookie";

export interface Tokens {
  accessToken: string;
  refreshToken: string;
  project: { reference: string };
  profile: { reference: string };
}

interface TokenReply {
  access_token?: string;
  refresh_token?: string;
  project?: { reference: string };
  profile?: { reference: string };
}

type Fetch = typeof fetch;

export async function exchangeCode(
  settings: SignInSettings,
  pending: PkceState | undefined,
  code: string,
  state: string,
  fetcher: Fetch = fetch,
): Promise<{ tokens?: Tokens; error?: string; clear: string }> {
  const clear = clearedPkceCookie(isSecure(settings));
  if (!pending || pending.state !== state) return { error: "Start sign-in again", clear };
  let reply: TokenReply;
  try {
    const response = await fetcher(`${settings.medplumInternalUrl}/oauth2/token`, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "authorization_code",
        code,
        client_id: settings.clientId,
        redirect_uri: settings.clientOrigin,
        code_verifier: pending.verifier,
      }),
    });
    reply = (await response.json()) as TokenReply;
  } catch {
    return { error: "Sign-in failed", clear };
  }
  if (!reply.access_token || !reply.refresh_token || !reply.project || !reply.profile) {
    return { error: "Sign-in failed", clear };
  }
  return {
    tokens: {
      accessToken: reply.access_token,
      refreshToken: reply.refresh_token,
      project: reply.project,
      profile: reply.profile,
    },
    clear,
  };
}
