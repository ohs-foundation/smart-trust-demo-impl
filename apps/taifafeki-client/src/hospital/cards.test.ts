import type { Immunization } from "@medplum/fhirtypes";
import { describe, expect, it } from "vitest";
import { readCard } from "../server/readCard";
import { compareDoses } from "./cards";
import { PRODUCT_SYSTEM } from "./records";

const HEPB = "HC1:NCFOXN%TSMAHN-H9M4QZQHPSVLEB3H-D47X5JU9V21HJI NRYVN:X9P TLV5AN9I6TSQL H2STU/YU8QHZSMSLG84WY F20W8L2WOATK2-GAUVPIY1VS1NQ1GSUGLIEG43PICH5SP420TI30:NNW 2./GMA5*CBVZ0I:CK.G5QN35TZJJM/S9 8O-OK+5:MNV6SY/O3%89-3$+OAGJTFHOC9OX0VYCVNNOGLJE99W0SC5BRNAOVZNVF48DDGJ4LR.4S739NSE+L$M66NP%1I.4PJT3Y:5I6OK98LYHO697BBSOPN3FRPFJ8BR/M00KT075LKU1O1E8HRN3-9WMH1VT.-S+MNWHEJLDIU1.A1SH8-.6*32EFC";
const BCG = "HC1:NCFOXN%TSMAHN-H9M4QZQHPSVLEB3HOI47X5JU9V21HJI NR%4F:X9P TC/TAN9I6TSQL H2STU/YU8QHZSMSLG84WY F20W8L2WOATK2-GAUVPIY1VS1NQ1GSUGLIEG43PICH5SP420TI30LQN5$0%*8MA5*CBVZ0I:CK.G5QN35TZJJM/S9 8O-OK+5:MNV6SY/O3%89-3$+OAGJTFHOC9$W0VYCYONTNPLPND 8EILPKLZ957JJWC5ZD5+B9TZ0:577/S6.C$ZI285JDB3*UI%EE+14VS*JL6XBYMD2SNLGS+CC-AJCO9*93FXVQL6TZR2$VJE1+O48KFLMJ+3L-C1R6VGLEJFWN%S04H050FVOE1";

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
