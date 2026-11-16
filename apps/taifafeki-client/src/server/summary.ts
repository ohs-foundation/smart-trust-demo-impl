import { WitVhl } from "smart-trust-kotlin-hcert-core";
import { signedHeaders, type ClinicIdentity, type Outgoing } from "./signing";
import type { Viewer } from "./viewer";

const MAX_DOCUMENTS = 10;
const MAX_REPLY_BYTES = 5_000_000;
const TIMEOUT_MILLIS = 15_000;
const RECIPIENT = "Kesaro primary care clinic";
const DEMO_REASONS = "http://smart.who.int/trust-demo/CodeSystem/TrustDemoVhlRefusalReason";
const OHS_REASONS = "http://ohs.dev/fhir/trust-demo/CodeSystem/vhl-refusal-reason";
const ATTEMPTS_LEFT = "http://ohs.dev/fhir/trust-demo/StructureDefinition/passcode-attempts-left";

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
  | { kind: "refused"; message: string; status?: number; reason?: string; attemptsLeft?: number }
  | { kind: "released"; documents: { contentType: string; jwe: string }[] };

const refused = (message: string): SummaryOutcome => ({ kind: "refused", message });

class Refusal extends Error {
  constructor(
    message: string,
    readonly answer: { status?: number; reason?: string; attemptsLeft?: number } = {},
  ) {
    super(message);
  }
}

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

interface Issue {
  extension?: { url?: string; valueUnsignedInt?: unknown }[];
  details?: { coding?: { system?: string; code?: string }[] };
}

function answerOf(text: string): { reason?: string; attemptsLeft?: number } {
  try {
    const { issue } = JSON.parse(text) as { issue?: Issue[] };
    const codings = issue?.flatMap((it) => it.details?.coding ?? []) ?? [];
    const reason = codings.find((coding) => (coding.system === DEMO_REASONS || coding.system === OHS_REASONS) && coding.code && Object.hasOwn(REFUSALS, coding.code))?.code;
    if (!reason) return {};
    const left = issue?.flatMap((it) => it.extension ?? []).find((extension) => extension.url === ATTEMPTS_LEFT)?.valueUnsignedInt;
    return Number.isSafeInteger(left) && (left as number) >= 0 ? { reason, attemptsLeft: left as number } : { reason };
  } catch {
    return {};
  }
}

function refusal(response: Response, text: string): Refusal {
  const { status } = response;
  const answer = answerOf(text);
  const message = answer.reason && REFUSALS[answer.reason];
  if (message) return new Refusal(message, { status, ...answer });
  return new Refusal(status === 403 ? "Nothing to release." : `Onesa refused the request (HTTP ${status}).`, { status });
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
    // The request does not depend on the link's key, which is not held; the builder only wants some valid one.
    const request = vhl.buildManifestRequest({ url: link.url, key: vhl.newKey(), flag: link.flag, exp: link.exp, v: 1 }, RECIPIENT, needsPasscode ? input.passcode : null, null);
    const manifest = await send({ method: "POST", url: onesaUrl(request.url, clinic.hosts), contentType: request.contentType, body: Buffer.from(request.body) });
    const listed = vhl.parseManifest(manifest);
    if (listed.length === 0 || listed.length > MAX_DOCUMENTS || listed.some((document) => !document.url)) return refused("Onesa returned no documents it could release.");

    const documents: { contentType: string; jwe: string }[] = [];
    for (const document of listed) {
      const jwe = await send({ method: "GET", url: onesaUrl(document.url ?? "", clinic.hosts) });
      documents.push({ contentType: document.contentType ?? "", jwe });
    }
    return { kind: "released", documents };
  } catch (error) {
    if (error instanceof Refusal) return { ...refused(error.message), ...error.answer };
    return refused("Onesa could not be reached.");
  }
}
