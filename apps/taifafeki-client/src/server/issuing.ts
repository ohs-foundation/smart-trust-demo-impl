export interface IssueRequest {
  token: string;
  immunizationId: string;
}

const RECORD_ID = /^[A-Za-z0-9][A-Za-z0-9\-.]{0,63}$/;

export function checkIssueRequest(input: unknown): IssueRequest {
  const { token, immunizationId } = (input ?? {}) as Partial<IssueRequest>;
  if (typeof token !== "string" || !token) throw new Error("A session token is required");
  if (typeof immunizationId !== "string" || !RECORD_ID.test(immunizationId)) throw new Error("A dose id is required");
  return { token, immunizationId };
}

// The issuer reads the records with this token, so Medplum's access policy decides who gets a card.
export async function issueCardFor(issuerUrl: string, request: IssueRequest, send: typeof fetch = fetch): Promise<string> {
  const response = await send(`${issuerUrl}/issuer/$issue`, {
    method: "POST",
    headers: { Authorization: `Bearer ${request.token}`, "Content-Type": "application/json" },
    body: JSON.stringify({ immunization: request.immunizationId }),
    signal: AbortSignal.timeout(15_000),
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`The card issuer refused (${response.status}): ${text}`);
  return text;
}
