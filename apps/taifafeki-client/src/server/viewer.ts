import { randomUUID } from "node:crypto";
import { checkLink, type Link } from "./checkLink";
import type { Clinic } from "./summary";
import type { TrustLists } from "./trustLists";

const HANDLE_SECONDS = 600;
const HANDLES_PER_OWNER = 20;

export type LinkOutcome =
  | { kind: "notVerified"; step: string; reason: string; kid?: string }
  | { kind: "expired"; exp: number }
  | { kind: "fetch"; url: string; label?: string; exp: number; issuer: string; needsPasscode: boolean; handle: string };

// The link's key never leaves the server: the browser holds only this handle, which names the link for the caller who scanned it.
export class LinkHandles {
  private readonly held = new Map<string, { link: Link; owner: string; until: number }>();

  constructor(private readonly now: () => number) {}

  keep(link: Link, owner: string): string {
    const now = this.now();
    for (const [handle, entry] of this.held) if (entry.until <= now) this.held.delete(handle);
    const owned = [...this.held].filter(([, entry]) => entry.owner === owner);
    for (const [handle] of owned.slice(0, Math.max(0, owned.length - HANDLES_PER_OWNER + 1))) this.held.delete(handle);
    const handle = randomUUID();
    this.held.set(handle, { link, owner, until: Math.min(now + HANDLE_SECONDS, link.exp) });
    return handle;
  }

  get(handle: string, owner: string): Link | undefined {
    const entry = this.held.get(handle);
    return entry && entry.owner === owner && entry.until > this.now() ? entry.link : undefined;
  }
}

export interface Viewer {
  trust: TrustLists;
  handles: LinkHandles;
  clinic: Clinic;
  session: (token: string | undefined) => Promise<string>;
  now: () => number;
}

export async function scanLink(viewer: Viewer, input: { token: string | undefined; text: string }): Promise<LinkOutcome> {
  const owner = await viewer.session(input.token);
  const checked = checkLink(input.text.trim(), viewer.trust.linkStore(), viewer.now());
  if (checked.kind !== "fetch") return checked;
  const { link } = checked;
  return {
    kind: "fetch",
    url: link.url,
    label: link.label,
    exp: link.exp,
    issuer: checked.issuer,
    needsPasscode: link.flag?.includes("P") ?? false,
    handle: viewer.handles.keep(link, owner),
  };
}
