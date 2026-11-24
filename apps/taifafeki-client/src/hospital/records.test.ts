import type { Bundle, Immunization, Patient } from "@medplum/fhirtypes";
import { describe, expect, it, vi } from "vitest";
import { registerNewborn } from "./api";
import { ig, present, requiredPaths } from "../testing/ig";
import { doseDateProblem, registrationBundle, type Context, type Dose, type Newborn } from "./records";

const context: Context = { hospitalId: "org-1", practitionerId: "prac-1", locationId: "loc-1" };

const newborn: Newborn = {
  given: "Somsak",
  family: "Sengmany",
  gender: "male",
  birth: "2025-10-28T08:40",
  hospitalNumber: "CRH-HN-68-104377",
};

const hepB: Dose = {
  productCode: "a3K3X000006MiiXUAS",
  productDisplay: "Hepatitis B vaccine, paediatric, monovalent",
  occurredAt: "2025-10-28T09:15",
  lot: "XXR-4821",
};
const bcg: Dose = {
  productCode: "a3K3X000006MiisUAC",
  productDisplay: "BCG vaccine, freeze-dried",
  occurredAt: "2025-10-28T09:20",
  lot: "XXR-9012",
};

const now = new Date("2025-10-28T12:00");

function registered(): { patient: Patient; doses: Immunization[]; bundle: Bundle } {
  const bundle = registrationBundle(newborn, [hepB, bcg], context);
  const resources = (bundle.entry ?? []).map((entry) => entry.resource);
  return {
    bundle,
    patient: resources[0] as Patient,
    doses: resources.slice(1) as Immunization[],
  };
}

describe("registration", () => {
  it("theRegistrationValidatesAgainstTheProfiles", () => {
    const { patient, doses } = registered();
    const patientProfile = ig("StructureDefinition-TrustDemoTaifafekiPatient.json");
    const immunizationProfile = ig("StructureDefinition-TrustDemoTaifafekiImmunization.json");

    for (const path of requiredPaths("StructureDefinition-TrustDemoTaifafekiPatient.json")) {
      expect(present(patient, path.split(".")), `Patient.${path}`).toBe(true);
    }
    for (const dose of doses) {
      for (const path of requiredPaths("StructureDefinition-TrustDemoTaifafekiImmunization.json")) {
        expect(present(dose, path.split(".")), `Immunization.${path}`).toBe(true);
      }
      for (const path of ["status", "vaccineCode", "patient", "occurrenceDateTime"]) {
        expect(present(dose, path.split(".")), `Immunization.${path}`).toBe(true);
      }
      expect(dose.meta?.profile).toEqual([immunizationProfile.url]);
      expect(dose.occurrenceDateTime).toBeDefined();
    }
    expect(patient.meta?.profile).toEqual([patientProfile.url]);

    const example: Patient = ig("Patient-TrustDemoTaifafekiSomsak.json");
    expect(patient.identifier?.[0]?.system).toBe(example.identifier?.[0]?.system);
    expect(patient.managingOrganization?.reference).toBe("Organization/org-1");

    const include = ig("ValueSet-TrustDemoVaccineProducts.json").compose.include[0];
    const codes = include.concept.map((concept: { code: string }) => concept.code);
    for (const dose of doses) {
      const product = dose.vaccineCode?.coding?.find((coding) => coding.system === include.system);
      expect(codes).toContain(product?.code);
    }
  });

  it("carriesCvxWhereTheProductHasOne", () => {
    const { doses } = registered();
    const cvx = (dose?: Immunization) =>
      dose?.vaccineCode?.coding?.filter((coding) => coding.system === "http://hl7.org/fhir/sid/cvx");
    expect(cvx(doses[0])?.map((coding) => coding.code)).toEqual(["08"]);
    expect(cvx(doses[1])).toEqual([]);
  });

  it("writesPatientAndDosesInOneTenantTaggedTransaction", () => {
    const { bundle, patient, doses } = registered();
    expect(bundle.type).toBe("transaction");
    expect(bundle.entry?.[0]?.request?.ifNoneExist).toBe(
      "identifier=http://smart.who.int/trust-demo/sid/xxr-kesaro-hn|CRH-HN-68-104377",
    );
    expect(bundle.entry?.[0]?.fullUrl).toMatch(/^urn:uuid:/);
    expect(patient.id).toBeUndefined();
    for (const dose of doses) {
      expect(dose.patient?.reference).toBe(bundle.entry?.[0]?.fullUrl);
      expect(dose.status).toBe("completed");
      expect(dose.protocolApplied?.[0]?.doseNumberPositiveInt).toBe(1);
      expect(dose.performer?.[0]?.actor?.reference).toBe("Practitioner/prac-1");
      expect(dose.location?.reference).toBe("Location/loc-1");
    }
    for (const resource of [patient, ...doses]) {
      expect(resource.meta?.accounts).toEqual([{ reference: "Organization/org-1" }]);
    }
  });

  it("aSecondRegistrationWithTheSameHospitalNumberIsRefused", async () => {
    const executeBatch = vi.fn();
    const medplum = {
      searchOne: vi.fn().mockResolvedValue({ resourceType: "Patient", id: "p1" }),
      executeBatch,
    };
    await expect(registerNewborn(medplum, newborn, [hepB, bcg], context, now)).rejects.toThrow(
      /already registered/,
    );
    expect(executeBatch).not.toHaveBeenCalled();
  });

  it("registersWhenTheHospitalNumberIsNew", async () => {
    const executeBatch = vi.fn().mockResolvedValue({ resourceType: "Bundle", type: "transaction-response" });
    const medplum = { searchOne: vi.fn().mockResolvedValue(undefined), executeBatch };
    await registerNewborn(medplum, newborn, [hepB, bcg], context, now);
    expect(medplum.searchOne).toHaveBeenCalledWith("Patient", {
      identifier: "http://smart.who.int/trust-demo/sid/xxr-kesaro-hn|CRH-HN-68-104377",
    });
    expect(executeBatch).toHaveBeenCalledOnce();
  });

  it("requiresTheHospitalNumberAndEachLot", async () => {
    const medplum = { searchOne: vi.fn(), executeBatch: vi.fn() };
    await expect(
      registerNewborn(medplum, { ...newborn, hospitalNumber: " " }, [], context, now),
    ).rejects.toThrow(/hospital number/i);
    await expect(
      registerNewborn(medplum, newborn, [{ ...hepB, lot: "" }], context, now),
    ).rejects.toThrow(/lot/i);
    expect(medplum.executeBatch).not.toHaveBeenCalled();
  });

  it("aDoseCannotBeDatedBeforeBirthOrInTheFuture", async () => {
    expect(doseDateProblem("2025-10-28T08:00", newborn.birth, now)).toMatch(/before the birth/);
    expect(doseDateProblem("2025-10-28T12:01", newborn.birth, now)).toMatch(/future/);
    expect(doseDateProblem("2025-10-28T08:40", newborn.birth, now)).toBeUndefined();
    expect(doseDateProblem("2025-10-28T12:00", newborn.birth, now)).toBeUndefined();

    const medplum = { searchOne: vi.fn().mockResolvedValue(undefined), executeBatch: vi.fn() };
    const early = { ...hepB, occurredAt: "2025-10-28T07:00" };
    await expect(registerNewborn(medplum, newborn, [early], context, now)).rejects.toThrow(/before the birth/);
    expect(medplum.executeBatch).not.toHaveBeenCalled();
  });
});
