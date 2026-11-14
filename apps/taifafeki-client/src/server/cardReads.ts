import type { Card } from "../hospital/cards";
import type { Viewer } from "./viewer";

export interface CardText {
  doseId: string;
  hc1: string;
}

export interface CardRequest {
  token: string;
  hospitalNumber: string;
  texts: CardText[];
}

const MAX_HOSPITAL_NUMBER = 64;
const MAX_TEXTS = 50;

export function checkCardRequest(input: unknown): CardRequest {
  const { token, hospitalNumber, texts } = (input ?? {}) as Partial<CardRequest>;
  if (typeof token !== "string") throw new Error("A session token is required");
  if (typeof hospitalNumber !== "string" || !hospitalNumber.trim() || hospitalNumber.length > MAX_HOSPITAL_NUMBER) {
    throw new Error("A hospital number is required");
  }
  const sound = Array.isArray(texts) && texts.length <= MAX_TEXTS && texts.every((it) => typeof it?.doseId === "string" && typeof it.hc1 === "string");
  if (!sound) throw new Error("The card texts are not a bounded list");
  return { token, hospitalNumber, texts };
}

export async function readCardsFor(viewer: Pick<Viewer, "session">, read: (doseId: string, hc1: string) => Card, request: CardRequest): Promise<Card[]> {
  await viewer.session(request.token);
  return request.texts.map((card) => read(card.doseId, card.hc1));
}
