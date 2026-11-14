import { createServerFn } from "@tanstack/react-start";
import { getCookie, getRequestUrl, setResponseHeader } from "@tanstack/react-start/server";

export const checkSignIn = createServerFn({ method: "POST" })
  .validator((query: Record<string, unknown>) => query)
  .handler(async ({ data }) => {
    const { loadSettings } = await import("./config");
    const { checkAuthorize } = await import("../auth/guard");
    const { PKCE_COOKIE, readPkce } = await import("../auth/cookie");
    const settings = loadSettings(getRequestUrl().origin);
    const guard = checkAuthorize(settings, readPkce(getCookie(PKCE_COOKIE)), data);
    return guard.ok
      ? { ok: true as const, challenge: guard.challenge, clientId: settings.clientId, medplumBaseUrl: settings.medplumBaseUrl }
      : guard;
  });

export const exchange = createServerFn({ method: "POST" })
  .validator((input: { code: string; state: string }) => input)
  .handler(async ({ data }) => {
    const { loadSettings } = await import("./config");
    const { exchangeCode } = await import("../auth/exchange");
    const { PKCE_COOKIE, readPkce } = await import("../auth/cookie");
    const settings = loadSettings(getRequestUrl().origin);
    const result = await exchangeCode(
      settings,
      readPkce(getCookie(PKCE_COOKIE)),
      data.code,
      data.state,
    );
    setResponseHeader("Set-Cookie", result.clear);
    return { tokens: result.tokens, error: result.error };
  });

export const getSettings = createServerFn({ method: "GET" }).handler(async () => {
  const { loadSettings } = await import("./config");
  const { clientId, medplumBaseUrl } = loadSettings(getRequestUrl().origin);
  return { clientId, medplumBaseUrl };
});
