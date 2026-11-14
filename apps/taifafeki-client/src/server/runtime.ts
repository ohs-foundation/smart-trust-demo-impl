import { requireSession } from "./session";
import { DEV_SIGNER, LOCAL_SIGNER, TrustLists } from "./trustLists";
import { LinkHandles, type Viewer } from "./viewer";

const HOUR = 3600;
const RETRY_MILLIS = 30_000;

let viewer: Viewer | undefined;

function required(env: NodeJS.ProcessEnv, name: string): string {
  const value = env[name];
  if (!value) throw new Error(`${name} is not set`);
  return value;
}

async function fetchList(url: string): Promise<string> {
  const response = await fetch(url, { signal: AbortSignal.timeout(10_000) });
  if (!response.ok) throw new Error(`${url} answered ${response.status}`);
  return response.text();
}

export function runtime(env = process.env): Viewer {
  if (viewer) return viewer;
  const now = () => Math.floor(Date.now() / 1000);
  const medplumUrl = env.MEDPLUM_INTERNAL_URL ?? required(env, "MEDPLUM_BASE_URL");
  const trust = new TrustLists(
    {
      local: { url: required(env, "TRUST_LIST_URL"), signer: LOCAL_SIGNER },
      dev: { url: required(env, "DEV_TRUST_LIST_URL"), signer: DEV_SIGNER },
    },
    fetchList,
    now,
  );
  let refreshedAt = now();
  void trust.refresh();
  setInterval(() => {
    if (trust.linkStore() && now() - refreshedAt < HOUR) return;
    refreshedAt = now();
    void trust.refresh();
  }, RETRY_MILLIS).unref();
  viewer = { trust, handles: new LinkHandles(now), session: (token) => requireSession(medplumUrl, token), now };
  return viewer;
}
