import type { Organization, PractitionerRole } from "@medplum/fhirtypes";

export const organization = (id: string): Organization => ({ resourceType: "Organization", id, name: id });
export const role = (id: string, code: string): PractitionerRole => ({
  resourceType: "PractitionerRole",
  organization: { reference: `Organization/${id}` },
  code: [{ coding: [{ system: "http://ohs.dev/fhir/CodeSystem/app-role", code }] }],
});

// Anan works at both organisations, as in the IG.
export async function searchAnansWorkplaces(type: string): Promise<(Organization | PractitionerRole)[]> {
  if (type === "PractitionerRole") return [role("hospital-org", "facility-emr"), role("clinic-org", "vhl-viewer")];
  if (type === "Organization") return [organization("hospital-org"), organization("clinic-org")];
  return [];
}
