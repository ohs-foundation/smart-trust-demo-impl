const BASE45 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";
const MAX_BYTES = 64 * 1024;

export class UnreadableLink extends Error {}

function base45(text: string): Uint8Array {
  const out: number[] = [];
  for (let i = 0; i < text.length; i += 3) {
    const digits = [...text.slice(i, i + 3)].map((char) => BASE45.indexOf(char));
    if (digits.includes(-1) || digits.length === 1) throw new UnreadableLink("the link is not base45");
    const value = digits.reduceRight((sum, digit) => sum * 45 + digit, 0);
    if (digits.length === 3) {
      if (value > 0xffff) throw new UnreadableLink("the link is not base45");
      out.push(value >> 8, value & 0xff);
    } else {
      if (value > 0xff) throw new UnreadableLink("the link is not base45");
      out.push(value);
    }
  }
  return Uint8Array.from(out);
}

async function inflated(data: Uint8Array<ArrayBuffer>): Promise<Uint8Array> {
  const reader = new Response(data).body!.pipeThrough(new DecompressionStream("deflate")).getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > MAX_BYTES) throw new UnreadableLink("the link inflates past the limit");
    chunks.push(value);
  }
  const whole = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    whole.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return whole;
}

type Cbor = number | bigint | string | boolean | null | undefined | Uint8Array | Cbor[] | Map<Cbor, Cbor>;

const SIMPLE: Record<number, Cbor> = { 20: false, 21: true, 22: null, 23: undefined };

// Just enough CBOR for a COSE_Sign1 and its claims: definite lengths, no floats.
function cbor(bytes: Uint8Array): Cbor {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let at = 0;
  const unreadable = () => new UnreadableLink("the link is not CBOR this client reads");
  const take = (count: number) => {
    if (count > bytes.length - at) throw unreadable();
    const part = bytes.subarray(at, at + count);
    at += count;
    return part;
  };
  const length = (info: number): number => {
    if (info < 24) return info;
    const width = { 24: 1, 25: 2, 26: 4 }[info];
    if (!width) throw unreadable();
    const part = take(width);
    return part.reduce((sum, byte) => sum * 256 + byte, 0);
  };
  const item = (depth: number): Cbor => {
    if (depth > 16) throw unreadable();
    const initial = take(1)[0]!;
    const [major, info] = [initial >> 5, initial & 31];
    if (major === 7) {
      if (!(info in SIMPLE)) throw unreadable();
      return SIMPLE[info];
    }
    if (info === 27) {
      const big = view.getBigUint64(at);
      at += 8;
      if (major > 1) throw unreadable();
      return major === 0 ? big : -1n - big;
    }
    const n = length(info);
    switch (major) {
      case 0:
        return n;
      case 1:
        return -1 - n;
      case 2:
        return take(n).slice();
      case 3:
        return new TextDecoder("utf-8", { fatal: true }).decode(take(n));
      case 4:
        return Array.from({ length: n }, () => item(depth + 1));
      case 5: {
        const map = new Map<Cbor, Cbor>();
        for (let i = 0; i < n; i++) map.set(item(depth + 1), item(depth + 1));
        return map;
      }
      default:
        return item(depth + 1);
    }
  };
  return item(0);
}

const HEALTH_CERTIFICATES = -260;

// Reads the key out of a link the server has already verified; the signature is not checked here.
export async function keyOf(text: string): Promise<string> {
  const unreadable = new UnreadableLink("the link holds no key");
  const trimmed = text.trim();
  if (!trimmed.startsWith("HC1:")) throw unreadable;
  let cose: Cbor;
  try {
    cose = cbor(await inflated(base45(trimmed.slice(4)) as Uint8Array<ArrayBuffer>));
  } catch (error) {
    throw error instanceof UnreadableLink ? error : unreadable;
  }
  const payload = Array.isArray(cose) && cose[2] instanceof Uint8Array ? cbor(cose[2]) : undefined;
  const claims = payload instanceof Map ? payload.get(HEALTH_CERTIFICATES) : undefined;
  if (!(claims instanceof Map)) throw unreadable;
  for (const claim of claims.values()) {
    if (claim instanceof Map && typeof claim.get("url") === "string" && typeof claim.get("key") === "string") return claim.get("key") as string;
  }
  throw unreadable;
}
