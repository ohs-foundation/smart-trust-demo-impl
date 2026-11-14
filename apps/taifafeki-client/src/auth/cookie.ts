export const PKCE_COOKIE = "pkce";

export interface PkceState {
  state: string;
  verifier: string;
}
