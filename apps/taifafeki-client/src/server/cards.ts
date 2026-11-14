import { createServerFn } from "@tanstack/react-start";
import { checkCardRequest, type CardRequest, type CardText } from "./cardReads";

export type { CardText };

// hcert-core needs node:zlib, which the browser bundle does not have.
export const readCardTexts = createServerFn({ method: "POST" })
  .validator((input: CardRequest) => checkCardRequest(input))
  .handler(async ({ data }) => {
    const { readCard } = await import("./readCard");
    const { runtime } = await import("./runtime");
    const { readCardsFor } = await import("./cardReads");
    return readCardsFor(runtime(), readCard, data);
  });
