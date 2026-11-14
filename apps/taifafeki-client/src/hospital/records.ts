import type { Bundle, BundleEntry, Coding, Element, Immunization, Patient } from "@medplum/fhirtypes";

export const HOSPITAL_NUMBER_SYSTEM = "http://smart.who.int/trust-demo/sid/xxr-kesaro-hn";
export const PRODUCT_SYSTEM = "http://smart.who.int/trust-demo/CodeSystem/TrustDemoVaccineProduct";
export const PRODUCTS_VALUE_SET = "http://smart.who.int/trust-demo/ValueSet/TrustDemoVaccineProducts";
const PROFILES = "http://smart.who.int/trust-demo/StructureDefinition";
const PATIENT_PROFILE = `${PROFILES}/TrustDemoTaifafekiPatient`;
const IMMUNIZATION_PROFILE = `${PROFILES}/TrustDemoTaifafekiImmunization`;
const CVX_SYSTEM = "http://hl7.org/fhir/sid/cvx";
export const BIRTH_TIME = "http://hl7.org/fhir/StructureDefinition/patient-birthTime";

const CVX_BY_PRODUCT: Record<string, Coding> = {
  "vp-hepb-peds-monovalent": { system: CVX_SYSTEM, code: "08", display: "Hep B, adolescent or pediatric" },
};

export type TimedPatient = Patient & { _birthDate?: Element };

export interface Context {
  hospitalId: string;
  practitionerId: string;
  locationId: string;
}

export interface Newborn {
  given: string;
  family: string;
  gender: NonNullable<Patient["gender"]>;
  /** `datetime-local` value, read in the browser's zone. */
  birth: string;
  hospitalNumber: string;
}

export interface Dose {
  productCode: string;
  productDisplay: string;
  /** `datetime-local` value, read in the browser's zone. */
  occurredAt: string;
  lot: string;
}

export function hospitalNumberOf(patient: Patient): string {
  return patient.identifier?.find((it) => it.system === HOSPITAL_NUMBER_SYSTEM)?.value ?? "";
}

export function doseDateProblem(occurredAt: string, birth: string, now: Date): string | undefined {
  const at = new Date(occurredAt);
  if (Number.isNaN(at.getTime())) return "Enter the date and time of the dose.";
  if (at > now) return "A dose cannot be dated in the future.";
  if (at < new Date(birth)) return "A dose cannot be dated before the birth.";
  return undefined;
}

export function buildPatient(newborn: Newborn, context: Context): TimedPatient {
  return {
    resourceType: "Patient",
    meta: { profile: [PATIENT_PROFILE], accounts: [organization(context)] },
    identifier: [{ system: HOSPITAL_NUMBER_SYSTEM, value: newborn.hospitalNumber.trim() }],
    name: [{ use: "official", family: newborn.family.trim(), given: [newborn.given.trim()] }],
    gender: newborn.gender,
    birthDate: newborn.birth.slice(0, 10),
    _birthDate: {
      extension: [{ url: BIRTH_TIME, valueDateTime: new Date(newborn.birth).toISOString() }],
    },
    managingOrganization: organization(context),
  };
}

export function buildImmunization(dose: Dose, patient: string, context: Context): Immunization {
  const cvx = CVX_BY_PRODUCT[dose.productCode];
  return {
    resourceType: "Immunization",
    meta: { profile: [IMMUNIZATION_PROFILE], accounts: [organization(context)] },
    status: "completed",
    vaccineCode: {
      coding: [
        { system: PRODUCT_SYSTEM, code: dose.productCode, display: dose.productDisplay },
        ...(cvx ? [cvx] : []),
      ],
    },
    patient: { reference: patient },
    occurrenceDateTime: new Date(dose.occurredAt).toISOString(),
    primarySource: true,
    location: { reference: `Location/${context.locationId}` },
    performer: [{ actor: { reference: `Practitioner/${context.practitionerId}` } }],
    lotNumber: dose.lot.trim(),
    protocolApplied: [{ doseNumberPositiveInt: 1 }],
  };
}

export function registrationBundle(newborn: Newborn, doses: Dose[], context: Context): Bundle {
  const fullUrl = `urn:uuid:${crypto.randomUUID()}`;
  const patient: BundleEntry = {
    fullUrl,
    resource: buildPatient(newborn, context),
    request: {
      method: "POST",
      url: "Patient",
      ifNoneExist: `identifier=${HOSPITAL_NUMBER_SYSTEM}|${newborn.hospitalNumber.trim()}`,
    },
  };
  return { resourceType: "Bundle", type: "transaction", entry: [patient, ...doseEntries(doses, fullUrl, context)] };
}

export function dosesBundle(patientId: string, doses: Dose[], context: Context): Bundle {
  return {
    resourceType: "Bundle",
    type: "transaction",
    entry: doseEntries(doses, `Patient/${patientId}`, context),
  };
}

function doseEntries(doses: Dose[], patient: string, context: Context): BundleEntry[] {
  return doses.map((dose) => ({
    resource: buildImmunization(dose, patient, context),
    request: { method: "POST", url: "Immunization" },
  }));
}

function organization(context: Context) {
  return { reference: `Organization/${context.hospitalId}` };
}
