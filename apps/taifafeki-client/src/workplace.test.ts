import type { Organization, PractitionerRole } from "@medplum/fhirtypes";
import { describe, expect, it } from "vitest";
import { workplaces } from "./workplace";

const IG_ID = "http://ohs.dev/fhir/trust-demo/ig-id";

function organization(id: string, igId: string): Organization {
  return {
    resourceType: "Organization",
    id,
    identifier: [{ system: IG_ID, value: igId }],
  };
}

function role(organizationId: string): PractitionerRole {
  return {
    resourceType: "PractitionerRole",
    organization: { reference: `Organization/${organizationId}` },
  };
}

const hospital = organization("h1", "TrustDemoKesaroHospital");
const clinic = organization("c1", "TrustDemoKesaroClinic");

describe("workplaces", () => {
  it("opens the hospital as the Hospital EMR & Print Station", () => {
    const [only] = workplaces([role("h1")], [hospital, clinic]);
    expect(only?.configuration).toBe("hospital");
    expect(only?.title).toBe("Hospital EMR & Print Station (J1)");
    expect(only?.organization.id).toBe("h1");
  });

  it("opens the clinic as the VHL Viewer", () => {
    const [only] = workplaces([role("c1")], [hospital, clinic]);
    expect(only?.configuration).toBe("clinic");
    expect(only?.title).toBe("VHL Viewer (J4.1)");
  });

  it("offers one workplace per role, in role order", () => {
    const found = workplaces([role("c1"), role("h1")], [hospital, clinic]);
    expect(found.map((w) => w.configuration)).toEqual(["clinic", "hospital"]);
  });

  it("offers nothing for an organisation with no configuration", () => {
    const other = organization("x1", "SomewhereElse");
    expect(workplaces([role("x1")], [other])).toEqual([]);
  });
});
