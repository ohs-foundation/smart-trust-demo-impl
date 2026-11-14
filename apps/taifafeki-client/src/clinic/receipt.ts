import type { MedplumClient } from "@medplum/core";
import type { Bundle, Provenance, ProvenanceEntity, Reference } from "@medplum/fhirtypes";
import { readSummary } from "./summary";

const IG_ID_SYSTEM = "http://ohs.dev/fhir/trust-demo/ig-id";
const RECEIPT_PROFILE = "http://smart.who.int/trust-demo/StructureDefinition/TrustDemoTaifafekiVhlReceipt";
const VIEWER_DEVICE = "TrustDemoClinicViewer";

export interface ReceiptParts {
  bundle: Bundle;
  organizationId: string;
  practitionerId: string;
  deviceId: string;
  now: Date;
}

// The Bundle is Onesa's, not Taifafeki's, so the receipt names it by identifier; the Consent is Onesa's too and is cited by the absolute fullUrl the IPS gives it.
export function buildReceipt({ bundle, organizationId, practitionerId, deviceId, now }: ReceiptParts): Provenance {
  const consent = bundle.entry?.find((entry) => entry.resource?.resourceType === "Consent")?.fullUrl;
  const target: Reference = bundle.identifier ? { type: "Bundle", identifier: bundle.identifier } : { type: "Bundle", display: "Patient summary from Onesa" };
  const entity: ProvenanceEntity[] = [
    { role: "source", what: { display: `VHL for ${readSummary(bundle).patient.name}` } },
    { role: "source", what: consent?.startsWith("http") ? { reference: consent } : { display: "Consent the summary was shared under" } },
  ];
  return {
    resourceType: "Provenance",
    meta: { profile: [RECEIPT_PROFILE], accounts: [{ reference: `Organization/${organizationId}` }] },
    target: [target],
    recorded: now.toISOString(),
    activity: { text: "Verified the VHL (HCERT) offline, fetched the manifest (ITI-YY5), decrypted the IPS (JWE)" },
    agent: [
      { who: { reference: `Device/${deviceId}` } },
      { who: { reference: `Practitioner/${practitionerId}` } },
      { who: { display: "Holder of the VHL" }, type: { text: "Holder: showed the VHL QR and said the passcode" } },
    ],
    entity,
  };
}

export async function writeReceipt(
  medplum: Pick<MedplumClient, "searchOne" | "createResource">,
  parts: Omit<ReceiptParts, "deviceId">,
): Promise<Provenance> {
  const device = await medplum.searchOne("Device", { identifier: `${IG_ID_SYSTEM}|${VIEWER_DEVICE}` });
  if (!device?.id) throw new Error("The clinic's viewer device is not set up on Medplum.");
  return medplum.createResource(buildReceipt({ ...parts, deviceId: device.id }));
}
