export interface AuthorizeParams {
  clientId: string;
  redirectUri: string;
  state: string;
  scope: string;
  codeChallenge: string;
  codeChallengeMethod: string;
  nonce?: string;
}

type Fetch = typeof fetch;

export function authorizeParams(search: Record<string, unknown>): AuthorizeParams {
  const text = (name: string) => (typeof search[name] === "string" ? search[name] : "");
  return {
    clientId: text("client_id"),
    redirectUri: text("redirect_uri"),
    state: text("state"),
    scope: text("scope") || "openid",
    codeChallenge: text("code_challenge"),
    codeChallengeMethod: text("code_challenge_method"),
    nonce: text("nonce") || undefined,
  };
}

async function post(fetcher: Fetch, url: string, body: unknown) {
  const response = await fetcher(url, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  return (await response.json()) as { code?: string; login?: string; memberships?: { id: string }[] };
}

export async function login(
  medplumBaseUrl: string,
  params: AuthorizeParams,
  email: string,
  password: string,
  fetcher: Fetch = fetch,
): Promise<string> {
  let reply = await post(fetcher, `${medplumBaseUrl}/auth/login`, {
    email,
    password,
    clientId: params.clientId,
    scope: params.scope,
    nonce: params.nonce,
    codeChallenge: params.codeChallenge,
    codeChallengeMethod: params.codeChallengeMethod,
  });
  const membership = reply.memberships?.[0];
  if (!reply.code && reply.login && membership) {
    reply = await post(fetcher, `${medplumBaseUrl}/auth/profile`, {
      login: reply.login,
      profile: membership.id,
    });
  }
  if (!reply.code) throw new Error("Sign-in failed");
  const back = new URL(params.redirectUri);
  back.searchParams.set("code", reply.code);
  back.searchParams.set("state", params.state);
  return back.toString();
}
