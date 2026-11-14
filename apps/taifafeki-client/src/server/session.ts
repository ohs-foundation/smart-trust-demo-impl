export class NoSession extends Error {
  constructor() {
    super("Sign in again");
  }
}

type Fetch = (url: string, init: RequestInit) => Promise<Response>;

export async function requireSession(medplumUrl: string, token: string | undefined, fetchJson: Fetch = fetch): Promise<string> {
  if (!token) throw new NoSession();
  const response = await fetchJson(`${medplumUrl}/auth/me`, { headers: { Authorization: `Bearer ${token}` } });
  if (!response.ok) throw new NoSession();
  const profile = ((await response.json()) as { profile?: { resourceType?: string; id?: string } }).profile;
  if (profile?.resourceType !== "Practitioner" || !profile.id) throw new NoSession();
  return `Practitioner/${profile.id}`;
}
