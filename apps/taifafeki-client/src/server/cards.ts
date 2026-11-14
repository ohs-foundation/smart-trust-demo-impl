import { createServerFn } from "@tanstack/react-start";

export interface CardText {
  doseId: string;
  hc1: string;
}

// hcert-core needs node:zlib, which the browser bundle does not have.
export const readCardTexts = createServerFn({ method: "POST" })
  .validator((input: CardText[]) => input)
  .handler(async ({ data }) => {
    const { readCard } = await import("./readCard");
    return data.map((card) => readCard(card.doseId, card.hc1));
  });
