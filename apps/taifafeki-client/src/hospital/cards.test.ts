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
const hepB = entered("a3K3X000006MiiXUAS", "2026-10-17T12:00:00Z");
const bcg = entered("a3K3X000006MiisUAC", "2026-10-17T12:05:00Z");

describe("aDifferentDoseWarns", () => {
  it("reads the holder and the dose as the card says", () => {
    expect(cards[0]).toMatchObject({ holder: "Somsak SENGMANY", birth: "2026-10-17", product: "a3K3X000006MiiXUAS", date: "2026-10-17", lot: "XXR-4821" });
  });

  it("gives no warning when the entered doses match the cards, whatever the lots", () => {
    expect(compareDoses(cards, [hepB, entered("a3K3X000006MiisUAC", "2026-10-17T12:05:00Z", "ANY-LOT")])).toEqual([]);
  });

  it("compares the date a dose was given where it was given, whatever the browser's zone", () => {
    const justAfterMidnight = entered("a3K3X000006MiiXUAS", "2026-10-17T00:30:00+07:00");
    expect(compareDoses(cards, [justAfterMidnight, bcg])).toEqual([]);
  });

  it("warns when a date differs", () => {
    const warnings = compareDoses(cards, [hepB, entered("a3K3X000006MiisUAC", "2026-10-18T12:05:00Z")]);
    expect(warnings).toHaveLength(1);
    expect(warnings[0]).toContain("a3K3X000006MiisUAC");
    expect(warnings[0]).toContain("2026-10-17");
    expect(warnings[0]).toContain("2026-10-18");
  });

  it("warns when a card has no entered dose, and when an entered dose has no card", () => {
    expect(compareDoses(cards, [hepB])).toEqual([expect.stringContaining("a3K3X000006MiisUAC")]);
    const extra = entered("a3K3X000006MijBUAS", "2026-10-17T12:10:00Z");
    expect(compareDoses(cards, [hepB, bcg, extra])).toEqual([expect.stringContaining("a3K3X000006MijBUAS")]);
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
    const dose = { productCode: "a3K3X000006MiiXUAS", productDisplay: "Hep B", occurredAt: "2026-10-17T01:00", lot: "XXR-4821" };
    const recorded = buildImmunization(dose, "Patient/p1", { hospitalId: "h", practitionerId: "p", locationId: "l" });
    expect(recorded.occurrenceDateTime).toBe("2026-10-17T01:00:00+07:00");
    expect(compareDoses(cards, [recorded, bcg])).toEqual([]);
  });
});
