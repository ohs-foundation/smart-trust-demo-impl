export const PKCE_COOKIE = "pkce";
const MAX_AGE_SECONDS = 600;

export interface PkceState {
  state: string;
  verifier: string;
}

function attributes(secure: boolean, maxAge: number) {
  return `; Path=/; HttpOnly; SameSite=Lax; Max-Age=${maxAge}${secure ? "; Secure" : ""}`;
}

export function pkceCookie({ state, verifier }: PkceState, secure: boolean) {
  return `${PKCE_COOKIE}=${state}.${verifier}${attributes(secure, MAX_AGE_SECONDS)}`;
}

export function clearedPkceCookie(secure: boolean) {
  return `${PKCE_COOKIE}=${attributes(secure, 0)}`;
}

export function readPkce(value: string | undefined): PkceState | undefined {
  const [state, verifier, ...rest] = value?.split(".") ?? [];
  return state && verifier && rest.length === 0 ? { state, verifier } : undefined;
}
