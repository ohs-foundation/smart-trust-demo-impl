import type { Immunization } from "@medplum/fhirtypes";
import { afterEach, describe, expect, it } from "vitest";
import { readCard } from "../server/readCard";
import { compareDoses } from "./cards";
import { BCG, HEPB } from "../testing/cards";
import { PRODUCT_SYSTEM, buildImmunization } from "./records";


function entered(code: string, occurrence: string, lot = "XXR-0000"): Immunization {
  return {
    resourceType: "Immunization",
    status: "completed",
    vaccineCode: { coding: [{ system: PRODUCT_SYSTEM, code }] },
    patient: { reference: "Patient/p1" },
    occurrenceDateTime: occurrence,
    lotNumber: lot,
  } as Immunization;
}

const cards = [readCard("somsak-hepb", HEPB), readCard("somsak-bcg", BCG)];
const hepB = entered("vp-hepb-peds-monovalent", "2026-10-17T12:00:00Z");
const bcg = entered("vp-bcg-freeze-dried", "2026-10-17T12:05:00Z");

describe("aDifferentDoseWarns", () => {
  it("reads the holder and the dose as the card says", () => {
    expect(cards[0]).toMatchObject({ holder: "Somsak SENGMANY", birth: "2026-10-17", product: "vp-hepb-peds-monovalent", date: "2026-10-17", lot: "XXR-4821" });
  });

  it("gives no warning when the entered doses match the cards, whatever the lots", () => {
    expect(compareDoses(cards, [hepB, entered("vp-bcg-freeze-dried", "2026-10-17T12:05:00Z", "ANY-LOT")])).toEqual([]);
  });

  it("compares the date a dose was given where it was given, whatever the browser's zone", () => {
    const justAfterMidnight = entered("vp-hepb-peds-monovalent", "2026-10-17T00:30:00+07:00");
    expect(compareDoses(cards, [justAfterMidnight, bcg])).toEqual([]);
  });

  it("warns when a date differs", () => {
    const warnings = compareDoses(cards, [hepB, entered("vp-bcg-freeze-dried", "2026-10-18T12:05:00Z")]);
    expect(warnings).toHaveLength(1);
    expect(warnings[0]).toContain("vp-bcg-freeze-dried");
    expect(warnings[0]).toContain("2026-10-17");
    expect(warnings[0]).toContain("2026-10-18");
  });

  it("warns when a card has no entered dose, and when an entered dose has no card", () => {
    expect(compareDoses(cards, [hepB])).toEqual([expect.stringContaining("vp-bcg-freeze-dried")]);
    const extra = entered("vp-opv", "2026-10-17T12:10:00Z");
    expect(compareDoses(cards, [hepB, bcg, extra])).toEqual([expect.stringContaining("vp-opv")]);
  });

  it("ignores doses that are not completed", () => {
    expect(compareDoses(cards, [hepB, bcg, { ...bcg, status: "entered-in-error" }])).toEqual([]);
  });
});

describe("aDoseIsRecordedWithTheBrowsersOffset", () => {
  const zone = process.env.TZ;
  afterEach(() => {
    process.env.TZ = zone;
  });

  it("gives no warning for an early-morning dose in a +07:00 zone", () => {
    process.env.TZ = "Asia/Bangkok";
    const dose = { productCode: "vp-hepb-peds-monovalent", productDisplay: "Hep B", occurredAt: "2026-10-17T01:00", lot: "XXR-4821" };
    const recorded = buildImmunization(dose, "Patient/p1", { hospitalId: "h", practitionerId: "p", locationId: "l" });
    expect(recorded.occurrenceDateTime).toBe("2026-10-17T01:00:00+07:00");
    expect(compareDoses(cards, [recorded, bcg])).toEqual([]);
  });
});
