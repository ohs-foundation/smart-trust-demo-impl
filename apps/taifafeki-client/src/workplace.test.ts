import type { Organization } from "@medplum/fhirtypes";
import { describe, expect, it } from "vitest";
import { organization, role } from "./testing/workplaces";
import { workplaces } from "./workplace";

const organizations: Organization[] = [organization("h1"), organization("c1"), organization("x1")];

describe("theConfigurationComesFromTheRoleCode", () => {
  it("opens facility-emr as the Hospital EMR & Print Station at the role's organisation", () => {
    const [only] = workplaces([role("x1", "facility-emr")], organizations);
    expect(only?.configuration).toBe("hospital");
    expect(only?.title).toBe("Hospital EMR & Print Station");
    expect(only?.organization.id).toBe("x1");
  });

  it("opens vhl-viewer as the VHL Viewer", () => {
    const [only] = workplaces([role("c1", "vhl-viewer")], organizations);
    expect(only?.configuration).toBe("clinic");
    expect(only?.title).toBe("VHL Viewer");
  });

  it("offers one workplace per role, in role order", () => {
    const found = workplaces([role("c1", "vhl-viewer"), role("h1", "facility-emr")], organizations);
    expect(found.map((w) => w.configuration)).toEqual(["clinic", "hospital"]);
  });

  it("ignores roles without a known code", () => {
    expect(workplaces([role("h1", "chw"), { resourceType: "PractitionerRole", organization: { reference: "Organization/h1" } }], organizations)).toEqual([]);
  });
});
