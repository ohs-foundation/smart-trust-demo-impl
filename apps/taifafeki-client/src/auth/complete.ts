import type { ClientStorage, MedplumClient } from "@medplum/core";
import { PKCE_COOKIE, type PkceState } from "./cookie";

export async function completeSignIn(
  medplum: MedplumClient,
  storage: ClientStorage,
  cookies: string,
  code: string,
  state: string,
) {
  const raw = cookies
    .split("; ")
    .find((entry) => entry.startsWith(`${PKCE_COOKIE}=`))
    ?.slice(PKCE_COOKIE.length + 1);
  if (!raw) throw new Error("The sign-in did not start here");
  const pending = JSON.parse(decodeURIComponent(raw)) as PkceState;
  if (pending.state !== state) throw new Error("The sign-in response does not match its request");
  storage.setString("codeVerifier", pending.verifier);
  await medplum.processCode(code, { redirectUri: window.location.origin });
}
