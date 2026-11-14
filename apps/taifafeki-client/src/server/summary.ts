import { WitVhl } from "smart-trust-kotlin-hcert-core";
import { signedHeaders, type ClinicIdentity, type Outgoing } from "./signing";
import type { Viewer } from "./viewer";

const MAX_DOCUMENTS = 10;
const MAX_REPLY_BYTES = 5_000_000;
const TIMEOUT_MILLIS = 15_000;
const RECIPIENT = "Kesaro primary care clinic";
const DEMO_REASONS = "http://smart.who.int/trust-demo/CodeSystem/TrustDemoVhlRefusalReason";
const OHS_REASONS = "http://ohs.dev/fhir/trust-demo/CodeSystem/vhl-refusal-reason";

const REFUSALS: Record<string, string> = {
  withdrawn: "The family withdrew this share.",
  expired: "The share has expired.",
  "wrong-passcode": "Wrong passcode.",
  "attempts-exhausted": "Three wrong passcodes; the link is locked.",
};

export interface Clinic {
  hosts: readonly string[];
  identity: () => ClinicIdentity;
  send: typeof fetch;
}

export type SummaryOutcome =
  | { kind: "refused"; message: string }
  | { kind: "released"; documents: { contentType: string; jwe: string }[]; key: string };

const refused = (message: string): SummaryOutcome => ({ kind: "refused", message });

class Refusal extends Error {}

// Only an https URL on exactly one listed host:port, without userinfo.
function onesaUrl(text: string, hosts: readonly string[]): URL {
  let url: URL;
  try {
    url = new URL(text);
  } catch {
    throw new Refusal("The link does not point at Onesa.");
  }
  if (url.protocol !== "https:" || url.username || url.password || !hosts.includes(url.host)) throw new Refusal("The link does not point at Onesa.");
  return url;
}

function reasonOf(text: string): string | undefined {
  try {
    const outcome = JSON.parse(text) as { issue?: { details?: { coding?: { system?: string; code?: string }[] } }[] };
    const codings = outcome.issue?.flatMap((issue) => issue.details?.coding ?? []) ?? [];
    return codings.find((coding) => (coding.system === DEMO_REASONS || coding.system === OHS_REASONS) && coding.code && Object.hasOwn(REFUSALS, coding.code))?.code;
  } catch {
    return undefined;
  }
}

function refusal(response: Response, text: string): Refusal {
  const reason = reasonOf(text);
  if (reason) return new Refusal(REFUSALS[reason]);
  return new Refusal(response.status === 403 ? "Nothing to release." : `Onesa refused the request (HTTP ${response.status}).`);
}

async function capped(response: Response): Promise<string> {
  const tooLarge = new Refusal("Onesa sent more than the client accepts.");
  if (Number(response.headers.get("content-length") ?? 0) > MAX_REPLY_BYTES) throw tooLarge;
  const reader = response.body?.getReader();
  if (!reader) return "";
  const chunks: Uint8Array[] = [];
  let size = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > MAX_REPLY_BYTES) {
      await reader.cancel();
      throw tooLarge;
    }
    chunks.push(value);
  }
  return Buffer.concat(chunks).toString("utf8");
}

export async function fetchSummary(
  viewer: Pick<Viewer, "handles" | "session" | "now" | "clinic">,
  input: { token: string | undefined; handle: string; passcode: string },
): Promise<SummaryOutcome> {
  const owner = await viewer.session(input.token);
  const link = viewer.handles.get(input.handle, owner);
  if (!link) return refused("This link is no longer held. Scan it again.");
  const { clinic } = viewer;
  let identity: ClinicIdentity;
  try {
    identity = clinic.identity();
  } catch {
    return refused("The clinic key cannot be read on this server.");
  }

  const send = async (request: Outgoing): Promise<string> => {
    const response = await clinic.send(request.url, {
      method: request.method,
      headers: signedHeaders(identity, request, viewer.now()),
      body: request.body && new Uint8Array(request.body),
      redirect: "error",
      signal: AbortSignal.timeout(TIMEOUT_MILLIS),
    });
    const text = await capped(response);
    if (!response.ok) throw refusal(response, text);
    return text;
  };

  try {
    const needsPasscode = link.flag?.includes("P") ?? false;
    if (needsPasscode && !input.passcode) return refused("Enter the passcode.");
    onesaUrl(link.url, clinic.hosts);
    const vhl = WitVhl.getInstance();
    const request = vhl.buildManifestRequest({ url: link.url, key: link.key, flag: link.flag, exp: link.exp, v: 1 }, RECIPIENT, needsPasscode ? input.passcode : null, null);
    const manifest = await send({ method: "POST", url: onesaUrl(request.url, clinic.hosts), contentType: request.contentType, body: Buffer.from(request.body) });
    const listed = vhl.parseManifest(manifest);
    if (listed.length === 0 || listed.length > MAX_DOCUMENTS || listed.some((document) => !document.url)) return refused("Onesa returned no documents it could release.");

    const documents: { contentType: string; jwe: string }[] = [];
    for (const document of listed) {
      const jwe = await send({ method: "GET", url: onesaUrl(document.url ?? "", clinic.hosts) });
      documents.push({ contentType: document.contentType ?? "", jwe });
    }
    return { kind: "released", documents, key: link.key };
  } catch (error) {
    if (error instanceof Refusal) return refused(error.message);
    return refused("Onesa could not be reached.");
  }
}
