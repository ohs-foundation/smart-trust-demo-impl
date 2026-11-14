import type { Organization, PractitionerRole } from "@medplum/fhirtypes";

export type Configuration = "hospital" | "clinic";

export interface Workplace {
  configuration: Configuration;
  title: string;
  organization: Organization;
}

export interface Remembered {
  organization: string;
  configuration: Configuration;
}

const APP_ROLE_SYSTEM = "http://ohs.dev/fhir/CodeSystem/app-role";

const CONFIGURATIONS: Record<string, { configuration: Configuration; title: string }> = {
  "facility-emr": { configuration: "hospital", title: "Hospital EMR & Print Station" },
  "vhl-viewer": { configuration: "clinic", title: "VHL Viewer" },
};

export function workplaces(roles: PractitionerRole[], organizations: Organization[]): Workplace[] {
  return roles.flatMap((role) => {
    const organization = organizations.find(
      (candidate) => `Organization/${candidate.id}` === role.organization?.reference,
    );
    const code = role.code?.flatMap((it) => it.coding ?? []).find((it) => it.system === APP_ROLE_SYSTEM && it.code && it.code in CONFIGURATIONS)?.code;
    const configuration = code ? CONFIGURATIONS[code] : undefined;
    return organization && configuration ? [{ ...configuration, organization }] : [];
  });
}

export function lastUsed(found: Workplace[], remembered: Remembered | undefined): Workplace | undefined {
  return found.find((it) => it.organization.id === remembered?.organization && it.configuration === remembered?.configuration);
}

const key = (profileId: string | undefined) => `taifafeki.workplace.${profileId}`;

export function recall(profileId: string | undefined): Remembered | undefined {
  try {
    const value: unknown = JSON.parse(localStorage.getItem(key(profileId)) ?? "null");
    if (typeof value !== "object" || value === null) return undefined;
    const { organization, configuration } = value as Partial<Remembered>;
    return typeof organization === "string" && (configuration === "hospital" || configuration === "clinic") ? { organization, configuration } : undefined;
  } catch {
    return undefined;
  }
}

export function remember(profileId: string | undefined, workplace: Remembered) {
  try {
    localStorage.setItem(key(profileId), JSON.stringify({ organization: workplace.organization, configuration: workplace.configuration }));
  } catch {
    return;
  }
}
