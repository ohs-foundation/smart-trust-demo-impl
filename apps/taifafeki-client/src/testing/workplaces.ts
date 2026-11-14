import type { Organization, PractitionerRole } from "@medplum/fhirtypes";

const IG_ID = "http://ohs.dev/fhir/trust-demo/ig-id";

const organization = (id: string, igId: string): Organization => ({ resourceType: "Organization", id, name: id, identifier: [{ system: IG_ID, value: igId }] });
const role = (id: string): PractitionerRole => ({ resourceType: "PractitionerRole", organization: { reference: `Organization/${id}` } });

// Anan works at both organisations, as in the IG.
export async function searchAnansWorkplaces(type: string): Promise<(Organization | PractitionerRole)[]> {
  if (type === "PractitionerRole") return [role("hospital-org"), role("clinic-org")];
  if (type === "Organization") return [organization("hospital-org", "TrustDemoKesaroHospital"), organization("clinic-org", "TrustDemoKesaroClinic")];
  return [];
}
