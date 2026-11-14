import type { Bundle, CodeableConcept, Composition, CompositionSection, HumanName, Immunization, Patient, Practitioner, Reference, Resource } from "@medplum/fhirtypes";

const MAX_DOCUMENT_BYTES = 64 * 1024 * 1024;
const KEY_PATTERN = /^[A-Za-z0-9_-]{43}$/;
const BASE64URL = /^[A-Za-z0-9_-]*$/;
const PRODUCT_SYSTEM = "http://smart.who.int/trust-demo/CodeSystem/TrustDemoVaccineProduct";
const SCHEDULE_SYSTEM = "http://smart.who.int/trust-demo/CodeSystem/TrustDemoOnesaEpiSchedule";
const CVX_SYSTEM = "http://hl7.org/fhir/sid/cvx";
const LOINC = "http://loinc.org";
const ALLERGIES_SECTION = "48765-2";
const PROBLEMS_SECTION = "11450-4";

export class Unreadable extends Error {}

export interface SummaryView {
  patient: { name: string; birthDate: string; sex: string; contacts: string[] };
  immunizations: {
    vaccine: string;
    date: string;
    dose: string;
    performer: string;
    product: string;
    schedule: string;
    cvx: string;
    lot: string;
    location: string;
    primarySource?: boolean;
  }[];
  allergies: string[];
  problems: string[];
  date: string;
  source: string;
}

function decoded(part: string): Uint8Array<ArrayBuffer> {
  if (!BASE64URL.test(part)) throw new Unreadable("a JWE part is not base64url");
  try {
    return Uint8Array.from(atob(part.replaceAll("-", "+").replaceAll("_", "/")), (char) => char.charCodeAt(0));
  } catch {
    throw new Unreadable("a JWE part is not base64url");
  }
}

async function inflated(data: Uint8Array<ArrayBuffer>): Promise<Uint8Array> {
  const reader = new Response(data).body!.pipeThrough(new DecompressionStream("deflate-raw")).getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > MAX_DOCUMENT_BYTES) throw new Unreadable("the document inflates past the limit");
    chunks.push(value);
  }
  const whole = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    whole.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return whole;
}

// JWE compact, dir / A256GCM as hcert-core's WitVhl produces it; the protected header's base64url text is the AAD.
async function decrypt(jwe: string, key: string): Promise<unknown> {
  if (!KEY_PATTERN.test(key) || decoded(key).length !== 32) throw new Unreadable("the key is not 32 base64url bytes");
  const parts = jwe.trim().split(".");
  const [protectedHeader, encryptedKey, iv, text, tag] = parts;
  if (parts.length !== 5 || !protectedHeader || encryptedKey !== "" || !iv || !text || !tag) throw new Unreadable("not a JWE compact serialization with a direct key");
  let header: { alg?: unknown; enc?: unknown; zip?: unknown; crit?: unknown };
  try {
    header = JSON.parse(new TextDecoder().decode(decoded(protectedHeader)));
  } catch {
    throw new Unreadable("the protected header is not base64url JSON");
  }
  if (header.crit !== undefined) throw new Unreadable("a critical header is not understood");
  if (header.alg !== "dir" || header.enc !== "A256GCM") throw new Unreadable("only dir / A256GCM is accepted");
  if (header.zip !== undefined && header.zip !== "DEF") throw new Unreadable("only zip DEF is accepted");
  const [nonce, body, mac] = [decoded(iv), decoded(text), decoded(tag)];
  if (nonce.length !== 12 || mac.length !== 16) throw new Unreadable("A256GCM needs a 96-bit IV and a 128-bit tag");
  const sealed = new Uint8Array(body.length + mac.length);
  sealed.set(body);
  sealed.set(mac, body.length);
  let plain: Uint8Array;
  try {
    const secret = await crypto.subtle.importKey("raw", decoded(key), "AES-GCM", false, ["decrypt"]);
    const opened = await crypto.subtle.decrypt({ name: "AES-GCM", iv: nonce, additionalData: new TextEncoder().encode(protectedHeader), tagLength: 128 }, secret, sealed);
    plain = new Uint8Array(opened);
    if (header.zip === "DEF") plain = await inflated(plain as Uint8Array<ArrayBuffer>);
  } catch (error) {
    if (error instanceof Unreadable) throw error;
    throw new Unreadable("the tag does not verify or the content does not inflate");
  }
  try {
    return JSON.parse(new TextDecoder().decode(plain));
  } catch {
    throw new Unreadable("the document is not JSON");
  }
}

export async function openSummary(documents: { contentType: string; jwe: string }[], key: string): Promise<Bundle> {
  for (const document of documents) {
    const content = (await decrypt(document.jwe, key)) as Partial<Bundle> | null;
    if (content?.resourceType === "Bundle" && content.type === "document") return content as Bundle;
  }
  throw new Unreadable("no IPS document in the summary");
}

const nameOf = (name: HumanName | undefined) => name?.text ?? [...(name?.given ?? []), name?.family].filter(Boolean).join(" ");

function resourcesOf<T extends { resourceType: string }>(bundle: Bundle, type: T["resourceType"]): T[] {
  return (bundle.entry ?? []).flatMap((entry) => (entry.resource?.resourceType === type ? [entry.resource as unknown as T] : []));
}

function doseOf(immunization: Immunization): string {
  const applied = immunization.protocolApplied?.[0];
  return String(applied?.doseNumberPositiveInt ?? applied?.doseNumberString ?? "");
}

const textOf = (concept: CodeableConcept | undefined) => concept?.text ?? concept?.coding?.[0]?.display ?? concept?.coding?.[0]?.code ?? "";
const sentence = (text: string) => text.charAt(0).toUpperCase() + text.slice(1);

// A reference resolves only to an entry of this summary: by its fullUrl, or by type and id.
function resolve(bundle: Bundle, reference: Reference | undefined): Resource | undefined {
  const target = reference?.reference;
  if (!target) return undefined;
  return bundle.entry?.find((entry) => entry.fullUrl === target || (entry.resource && `${entry.resource.resourceType}/${entry.resource.id}` === target))?.resource;
}

function performerOf(bundle: Bundle, immunization: Immunization): string {
  const actor = immunization.performer?.[0]?.actor;
  const practitioner = resolve(bundle, actor);
  return (practitioner?.resourceType === "Practitioner" ? nameOf((practitioner as Practitioner).name?.[0]) : "") || actor?.display || "";
}

function sectionOf(bundle: Bundle, composition: Composition | undefined, code: string): string[] {
  const section: CompositionSection | undefined = composition?.section?.find((it) => it.code?.coding?.some((coding) => coding.system === LOINC && coding.code === code));
  if (!section) return [];
  const entries = (section.entry ?? []).flatMap((reference) => {
    const resource = resolve(bundle, reference) as { code?: CodeableConcept } | undefined;
    return resource ? [textOf(resource.code)] : [];
  });
  if (entries.length > 0) return entries;
  return section.emptyReason ? [textOf(section.emptyReason)] : [];
}

export function readSummary(bundle: Bundle): SummaryView {
  const patient = resourcesOf<Patient>(bundle, "Patient")[0];
  const composition = resourcesOf<Composition>(bundle, "Composition")[0];
  return {
    patient: {
      name: nameOf(patient?.name?.[0]),
      birthDate: patient?.birthDate ?? "",
      sex: patient?.gender ?? "",
      contacts: (patient?.contact ?? []).map((contact) => [sentence(textOf(contact.relationship?.[0])), nameOf(contact.name)].filter(Boolean).join(": ")),
    },
    immunizations: resourcesOf<Immunization>(bundle, "Immunization").map((immunization) => {
      const codings = immunization.vaccineCode.coding ?? [];
      const code = (system: string) => codings.find((coding) => coding.system === system)?.code ?? "";
      return {
        vaccine: textOf(immunization.vaccineCode),
        date: immunization.occurrenceDateTime ?? "",
        dose: doseOf(immunization),
        performer: performerOf(bundle, immunization),
        product: code(PRODUCT_SYSTEM),
        schedule: code(SCHEDULE_SYSTEM),
        cvx: code(CVX_SYSTEM),
        lot: immunization.lotNumber ?? "",
        location: immunization.location?.display ?? "",
        primarySource: immunization.primarySource,
      };
    }),
    allergies: sectionOf(bundle, composition, ALLERGIES_SECTION),
    problems: sectionOf(bundle, composition, PROBLEMS_SECTION),
    date: bundle.timestamp ?? composition?.date ?? "",
    source: composition?.author?.[0]?.display ?? "",
  };
}
