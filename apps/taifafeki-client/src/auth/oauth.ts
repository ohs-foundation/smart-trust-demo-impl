import { SCOPE } from "./scope";

export interface AuthorizeParams {
  clientId: string;
}

type Fetch = typeof fetch;

interface Membership {
  id: string;
  project?: { display?: string };
}

interface LoginReply {
  code?: string;
  login?: string;
  memberships?: Membership[];
  mfaRequired?: boolean;
  enrollQrCode?: string;
}

const PROJECT = "Taifafeki Ministry of Public Health";

export function authorizeParams(search: Record<string, unknown>): AuthorizeParams {
  return { clientId: typeof search.client_id === "string" ? search.client_id : "" };
}

async function post(fetcher: Fetch, url: string, body: unknown) {
  const response = await fetcher(url, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  return (await response.json()) as LoginReply;
}

export async function login(
  medplumBaseUrl: string,
  clientId: string,
  codeChallenge: string,
  email: string,
  password: string,
  fetcher: Fetch = fetch,
): Promise<string> {
  let reply = await post(fetcher, `${medplumBaseUrl}/auth/login`, {
    email,
    password,
    clientId,
    scope: SCOPE,
    codeChallenge,
    codeChallengeMethod: "S256",
  });
  if (reply.mfaRequired || reply.enrollQrCode) {
    throw new Error("Sign-in failed: this account needs MFA, which this demo client does not support");
  }
  const membership = reply.memberships?.find((it) => it.project?.display === PROJECT);
  if (!reply.code && reply.login && membership) {
    reply = await post(fetcher, `${medplumBaseUrl}/auth/profile`, {
      login: reply.login,
      profile: membership.id,
    });
  }
  if (!reply.code) throw new Error("Sign-in failed");
  return reply.code;
}
