import { createServerFn } from "@tanstack/react-start";
import { checkIssueRequest, type IssueRequest } from "./issuing";

export const issueCard = createServerFn({ method: "POST" })
  .validator((input: IssueRequest) => checkIssueRequest(input))
  .handler(async ({ data }) => {
    const { issueCardFor } = await import("./issuing");
    const issuerUrl = process.env.ISSUER_URL;
    if (!issuerUrl) throw new Error("ISSUER_URL is not set");
    return issueCardFor(issuerUrl, data);
  });
