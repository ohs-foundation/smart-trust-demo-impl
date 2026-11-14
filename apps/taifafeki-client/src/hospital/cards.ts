import type { Immunization } from "@medplum/fhirtypes";
import { PRODUCT_SYSTEM } from "./records";

export interface Card {
  doseId: string;
  hc1: string;
  holder: string;
  birth: string;
  product: string;
  date: string;
  lot: string;
  kid: string;
  issuer: string;
  alg: string;
  /** The participant whose trust-list entry holds [kid]. */
  signer?: string;
}

// The lot is never compared: the cards' lots need not match the entered ones.
export function compareDoses(cards: Card[], doses: Immunization[]): string[] {
  const open = doses.filter((dose) => dose.status === "completed").map((dose) => ({ product: productOf(dose), date: dateOf(dose) }));
  const warnings: string[] = [];
  for (const card of cards) {
    const sameProduct = open.filter((dose) => dose.product === card.product);
    const match = sameProduct.find((dose) => dose.date === card.date) ?? sameProduct[0];
    if (!match) {
      warnings.push(`The card for ${card.product} has no matching dose entered.`);
      continue;
    }
    open.splice(open.indexOf(match), 1);
    if (match.date !== card.date) {
      warnings.push(`${card.product}: the card says ${card.date}, the entered dose says ${match.date}.`);
    }
  }
  for (const dose of open) warnings.push(`The entered dose of ${dose.product} on ${dose.date} has no card.`);
  return warnings;
}

function productOf(dose: Immunization): string {
  return dose.vaccineCode?.coding?.find((coding) => coding.system === PRODUCT_SYSTEM)?.code ?? "unknown product";
}

function dateOf(dose: Immunization): string {
  return /^\d{4}-\d{2}-\d{2}/.exec(dose.occurrenceDateTime ?? "")?.[0] ?? "no date";
}
