import type { Organization, PractitionerRole } from "@medplum/fhirtypes";

export type Configuration = "hospital" | "clinic";

export interface Workplace {
  configuration: Configuration;
  title: string;
  organization: Organization;
}

const IG_ID_SYSTEM = "http://ohs.dev/fhir/trust-demo/ig-id";

const CONFIGURATIONS: Record<string, { configuration: Configuration; title: string }> = {
  TrustDemoKesaroHospital: { configuration: "hospital", title: "Hospital EMR & Print Station" },
  TrustDemoKesaroClinic: { configuration: "clinic", title: "VHL Viewer" },
};

export function workplaces(roles: PractitionerRole[], organizations: Organization[]): Workplace[] {
  return roles.flatMap((role) => {
    const organization = organizations.find(
      (candidate) => `Organization/${candidate.id}` === role.organization?.reference,
    );
    const igId = organization?.identifier?.find((it) => it.system === IG_ID_SYSTEM)?.value;
    const configuration = igId ? CONFIGURATIONS[igId] : undefined;
    return organization && configuration ? [{ ...configuration, organization }] : [];
  });
}
