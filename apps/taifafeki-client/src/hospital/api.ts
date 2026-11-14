import type { MedplumClient } from "@medplum/core";
import type { Bundle, DocumentReference, Immunization, Location, Organization, Patient, Practitioner } from "@medplum/fhirtypes";
import {
  HOSPITAL_NUMBER_SYSTEM,
  PRODUCTS_VALUE_SET,
  dosesBundle,
  doseDateProblem,
  registrationBundle,
  BIRTH_TIME,
  type Context,
  type Dose,
  type Newborn,
  type TimedPatient,
} from "./records";
import { readCardTexts, type CardText } from "../server/cards";
import type { Card } from "./cards";

const IG_ID_SYSTEM = "http://ohs.dev/fhir/trust-demo/ig-id";
const HOSPITAL_LOCATION = "TrustDemoKesaroHospitalLoc";

export interface Product {
  code: string;
  display: string;
}

export interface Chart {
  patient: Patient;
  doses: Immunization[];
}

export async function readContext(
  medplum: Pick<MedplumClient, "readResource" | "searchOne">,
  hospitalId: string,
  practitionerId: string,
): Promise<Context & { hospital: Organization; practitioner: Practitioner; location: Location }> {
  const [hospital, practitioner, location] = await Promise.all([
    medplum.readResource("Organization", hospitalId),
    medplum.readResource("Practitioner", practitionerId),
    medplum.searchOne("Location", { identifier: `${IG_ID_SYSTEM}|${HOSPITAL_LOCATION}` }),
  ]);
  if (!location?.id) throw new Error("The hospital's location is not set up on Medplum.");
  return { hospitalId, practitionerId, locationId: location.id, hospital, practitioner, location };
}

export async function readProducts(medplum: Pick<MedplumClient, "valueSetExpand">): Promise<Product[]> {
  const expansion = await medplum.valueSetExpand({ url: PRODUCTS_VALUE_SET });
  return (expansion.expansion?.contains ?? []).flatMap((entry) =>
    entry.code ? [{ code: entry.code, display: entry.display ?? entry.code }] : [],
  );
}

export async function readNewborns(
  medplum: Pick<MedplumClient, "searchResources">,
  hospitalId: string,
): Promise<Patient[]> {
  return medplum.searchResources("Patient", {
    organization: `Organization/${hospitalId}`,
    _sort: "-_lastUpdated",
    _count: "50",
  });
}

export async function readChart(
  medplum: Pick<MedplumClient, "readResource" | "searchResources">,
  patientId: string,
): Promise<Chart> {
  const [patient, doses] = await Promise.all([
    medplum.readResource("Patient", patientId),
    medplum.searchResources("Immunization", { patient: `Patient/${patientId}`, _sort: "date" }),
  ]);
  return { patient, doses };
}

export async function registerNewborn(
  medplum: Pick<MedplumClient, "searchOne" | "executeBatch">,
  newborn: Newborn,
  doses: Dose[],
  context: Context,
  now: Date = new Date(),
): Promise<Bundle> {
  const hospitalNumber = newborn.hospitalNumber.trim();
  if (!hospitalNumber) throw new Error("Enter the hospital number.");
  if (!newborn.given.trim() || !newborn.family.trim()) throw new Error("Enter the baby's given and family name.");
  if (new Date(newborn.birth) > now) throw new Error("The birth cannot be in the future.");
  checkDoses(doses, newborn.birth, now);
  const existing = await medplum.searchOne("Patient", {
    identifier: `${HOSPITAL_NUMBER_SYSTEM}|${hospitalNumber}`,
  });
  if (existing) throw new Error(`Hospital number ${hospitalNumber} is already registered.`);
  return medplum.executeBatch(registrationBundle(newborn, doses, context));
}

export async function addDoses(
  medplum: Pick<MedplumClient, "executeBatch">,
  patient: TimedPatient,
  doses: Dose[],
  context: Context,
  now: Date = new Date(),
): Promise<Bundle> {
  const born = patient.birthDate ? birthInstant(patient) : undefined;
  checkDoses(doses, born ?? "", now);
  return medplum.executeBatch(dosesBundle(patient.id ?? "", doses, context));
}

function checkDoses(doses: Dose[], birth: string, now: Date) {
  for (const dose of doses) {
    if (!dose.lot.trim()) throw new Error(`Enter the lot number for ${dose.productDisplay}.`);
    const problem = doseDateProblem(dose.occurredAt, birth, now);
    if (problem) throw new Error(`${dose.productDisplay}: ${problem}`);
  }
}

function birthInstant(patient: TimedPatient): string {
  const time = patient._birthDate?.extension?.find((it) => it.url === BIRTH_TIME);
  return time?.valueDateTime ?? `${patient.birthDate}T00:00`;
}

export async function readCards(
  medplum: Pick<MedplumClient, "searchResources">,
  hospitalNumber: string,
  token: string,
  read: (texts: CardText[]) => Promise<Card[]> = (texts) => readCardTexts({ data: { token, hospitalNumber, texts } }),
): Promise<Card[]> {
  if (!hospitalNumber.trim()) return [];
  const found = await medplum.searchResources("DocumentReference", {
    identifier: `${HOSPITAL_NUMBER_SYSTEM}|${hospitalNumber}`,
  });
  const cards = await read(found.flatMap(textOf));
  return cards.sort((a, b) => a.doseId.localeCompare(b.doseId));
}

function textOf(reference: DocumentReference): CardText[] {
  const attachment = reference.content?.[0]?.attachment;
  if (!attachment?.data || !attachment.title?.endsWith(".hc1")) return [];
  return [{ doseId: attachment.title.slice(0, -".hc1".length), hc1: atob(attachment.data) }];
}
