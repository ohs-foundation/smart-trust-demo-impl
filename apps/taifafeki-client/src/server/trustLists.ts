import { WitTrust, WitTrustStore } from "smart-trust-kotlin-hcert-core";
import localSigner from "./signers/gdhcn-signer-did.json?raw";
import devSigner from "./signers/who-signing-did.json?raw";

export const LOCAL_SIGNER = localSigner;
export const DEV_SIGNER = devSigner;

const VHL_DOMAIN = "DEMO-VHL";
const CSCA_USAGE = "#CSCA";
const STALE_SECONDS = 24 * 3600;

export interface ListSource {
  url: string;
  signer: string;
}

export interface ListStatus {
  name: "local" | "dev";
  url: string;
  signer: string;
  fetchedAt?: number;
  entryCount: number;
  stale: boolean;
  lastFailure?: { at: number; reason: string };
}

export interface LinkStore {
  store: WitTrustStore;
  anchors: Set<string>;
}

interface Method {
  id: string;
  keyusage?: { code?: string };
  publicKeyJwk?: { x5c?: string[] };
}

interface Loaded {
  fetchedAt: number;
  createdAt: number;
  entryCount: number;
  store: WitTrustStore;
  anchors: Set<string>;
}

type Name = ListStatus["name"];

function domainOf(method: Method): string | undefined {
  const path = method.id.split("#")[0]?.split(":") ?? [];
  const at = path.indexOf("trustlist");
  return at < 0 ? undefined : path[at + 1];
}

export class TrustLists {
  private readonly loaded = new Map<Name, Loaded>();
  private readonly failures = new Map<Name, { at: number; reason: string }>();

  constructor(
    private readonly sources: { local: ListSource; dev: ListSource },
    private readonly fetchText: (url: string) => Promise<string>,
    private readonly now: () => number,
  ) {}

  async refresh(): Promise<void> {
    await Promise.all([this.refreshOne("local"), this.refreshOne("dev")]);
  }

  linkStore(): LinkStore | undefined {
    return this.loaded.get("local");
  }

  statuses(): ListStatus[] {
    return (["local", "dev"] as const).map((name) => {
      const loaded = this.loaded.get(name);
      return {
        name,
        url: this.sources[name].url,
        signer: (JSON.parse(this.sources[name].signer) as { id: string }).id,
        fetchedAt: loaded?.fetchedAt,
        entryCount: loaded?.entryCount ?? 0,
        stale: loaded !== undefined && this.now() - loaded.fetchedAt > STALE_SECONDS,
        lastFailure: this.failures.get(name),
      };
    });
  }

  private async refreshOne(name: Name): Promise<void> {
    const { url, signer } = this.sources[name];
    try {
      const text = await this.fetchText(url);
      WitTrust.getInstance().verifyDidProof(text, signer);
      const createdAt = Date.parse((JSON.parse(text) as { proof: { created: string } }).proof.created) / 1000;
      const held = this.loaded.get(name);
      if (!(createdAt >= (held?.createdAt ?? 0))) throw new Error("the list's proof is older than the one held");
      this.loaded.set(name, name === "local" ? this.linkList(text, createdAt) : this.wholeList(text, createdAt));
      this.failures.delete(name);
    } catch (error) {
      this.failures.set(name, { at: this.now(), reason: error instanceof Error ? error.message : String(error) });
    }
  }

  private linkList(text: string, createdAt: number): Loaded {
    const list = JSON.parse(text) as { verificationMethod: Method[] };
    const methods = list.verificationMethod.filter((method) => domainOf(method) === VHL_DOMAIN);
    const store = new WitTrustStore();
    const entryCount = store.addDidDocument(JSON.stringify({ ...list, verificationMethod: methods }));
    if (entryCount === 0) throw new Error(`the list has no ${VHL_DOMAIN} entries`);
    const anchors = new Set(methods.filter((method) => method.keyusage?.code === CSCA_USAGE).flatMap((method) => method.publicKeyJwk?.x5c?.slice(0, 1) ?? []));
    return { fetchedAt: this.now(), createdAt, entryCount, store, anchors };
  }

  private wholeList(text: string, createdAt: number): Loaded {
    const store = new WitTrustStore();
    return { fetchedAt: this.now(), createdAt, entryCount: store.addDidDocument(text), store, anchors: new Set() };
  }
}
