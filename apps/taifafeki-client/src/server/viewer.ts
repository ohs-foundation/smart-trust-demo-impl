import { randomUUID } from "node:crypto";
import { checkLink, type Link } from "./checkLink";
import type { TrustLists } from "./trustLists";

const HANDLE_SECONDS = 600;

export type LinkOutcome =
  | { kind: "notVerified"; step: string; reason: string }
  | { kind: "expired"; exp: number }
  | { kind: "fetch"; url: string; label?: string; exp: number; needsPasscode: boolean; handle: string };

// The link's key never leaves the server: the browser holds only this handle, which names the link for the caller who scanned it.
export class LinkHandles {
  private readonly held = new Map<string, { link: Link; owner: string; until: number }>();

  constructor(private readonly now: () => number) {}

  keep(link: Pick<Link, "url" | "key" | "flag"> & Partial<Link>, owner: string): string {
    const now = this.now();
    for (const [handle, entry] of this.held) if (entry.until <= now) this.held.delete(handle);
    const handle = randomUUID();
    this.held.set(handle, { link: link as Link, owner, until: now + HANDLE_SECONDS });
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
    needsPasscode: link.flag?.includes("P") ?? false,
    handle: viewer.handles.keep(link, owner),
  };
}
