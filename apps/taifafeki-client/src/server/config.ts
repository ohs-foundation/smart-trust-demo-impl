import { readFileSync } from "node:fs";
import type { SignInSettings } from "../auth/begin";

export function loadSettings(requestOrigin: string, env = process.env): SignInSettings {
  const clientId = readFileSync(env.MEDPLUM_CLIENT_ID_FILE ?? "medplum-client-id", "utf8").trim();
  const medplumBaseUrl = env.MEDPLUM_BASE_URL;
  if (!medplumBaseUrl) throw new Error("MEDPLUM_BASE_URL is not set");
  return {
    clientId,
    medplumBaseUrl,
    medplumInternalUrl: env.MEDPLUM_INTERNAL_URL ?? medplumBaseUrl,
    clientOrigin: env.CLIENT_ORIGIN ?? requestOrigin,
  };
}
