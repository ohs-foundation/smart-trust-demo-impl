import type { MedplumClient } from "@medplum/core";
import type { DocumentReference, Immunization, Patient } from "@medplum/fhirtypes";
import { describe, expect, it, vi } from "vitest";
import { readCards } from "./api";
import { HOSPITAL_NUMBER_SYSTEM, PRODUCT_SYSTEM } from "./records";
import type { Card } from "./cards";

const patient = { resourceType: "Patient", id: "baby-9", identifier: [{ system: HOSPITAL_NUMBER_SYSTEM, value: "HN-9" }], meta: { accounts: [{ reference: "Organization/h1" }] } } as Patient;

function dose(id: string, product: string): Immunization {
  return { resourceType: "Immunization", id, status: "completed", vaccineCode: { coding: [{ system: PRODUCT_SYSTEM, code: product }] } } as Immunization;
}

function stored(doseId: string, hc1: string): DocumentReference {
  return { resourceType: "DocumentReference", status: "current", content: [{ attachment: { title: `${doseId}.hc1`, data: btoa(hc1) } }] } as DocumentReference;
}

function setup(existing: DocumentReference[]) {
  const medplum = {
    searchResources: vi.fn(async () => existing),
    createResourceIfNoneExist: vi.fn(async (resource: DocumentReference) => resource),
  };
  const issue = vi.fn(async (id: string, _token: string) => `HC1:${id}`);
  const read = vi.fn(async (texts: { doseId: string; hc1: string }[]) => texts.map((it) => ({ doseId: it.doseId, hc1: it.hc1 }) as Card));
  return { medplum: medplum as unknown as Pick<MedplumClient, "searchResources" | "createResourceIfNoneExist">, spies: medplum, issue, read };
}

const doses = [dose("d1", "a3K3X000006MiiXUAS"), dose("d2", "a3K3X000006MiisUAC")];

describe("cardsAreIssuedAtPrint", () => {
  it("issues and stores a card per dose for a newborn who has none", async () => {
    const { medplum, spies, issue, read } = setup([]);
    const cards = await readCards(medplum, patient, doses, "user-token", { issue, read });
    expect(issue.mock.calls.map(([id, token]) => [id, token])).toEqual([["d1", "user-token"], ["d2", "user-token"]]);
    expect(spies.createResourceIfNoneExist).toHaveBeenCalledTimes(2);
    const [record, query] = spies.createResourceIfNoneExist.mock.calls[0] as unknown as [DocumentReference, string];
    expect(record.identifier).toEqual([
      { system: HOSPITAL_NUMBER_SYSTEM, value: "HN-9" },
      { system: "http://ohs.dev/fhir/trust-demo/ig-id", value: "d1-card" },
    ]);
    expect(record.meta?.accounts).toEqual([{ reference: "Organization/h1" }]);
    expect(record.content?.[0]?.attachment).toMatchObject({ title: "d1.hc1", data: btoa("HC1:d1") });
    expect(query).toBe("identifier=http://ohs.dev/fhir/trust-demo/ig-id|d1-card");
    expect(cards.map((card) => card.doseId)).toEqual(["d1", "d2"]);
  });

  it("calls the issuer only for doses without a card", async () => {
    const { medplum, issue, read } = setup([stored("d1", "HC1:old")]);
    const cards = await readCards(medplum, patient, doses, "t", { issue, read });
    expect(issue.mock.calls.map(([id]) => id)).toEqual(["d2"]);
    expect(cards.map((card) => card.hc1)).toEqual(["HC1:old", "HC1:d2"]);
  });

  it("does not call the issuer when every dose has a card or is not completed", async () => {
    const { medplum, issue, read } = setup([stored("d1", "HC1:a"), stored("d2", "HC1:b")]);
    await readCards(medplum, patient, [...doses, { ...dose("d3", "a3K3X000006MijBUAS"), status: "entered-in-error" }], "t", { issue, read });
    expect(issue).not.toHaveBeenCalled();
  });
});
