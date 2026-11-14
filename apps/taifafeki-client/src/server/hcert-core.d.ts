declare module "smart-trust-kotlin-hcert-core" {
  interface VhlPayload {
    url: string;
    key: string;
    exp?: bigint;
    flag?: string;
    label?: string;
    v?: number;
  }
  interface Failure {
    step: string;
    reason: string;
    detail: string;
  }
  interface Verified {
    iss: string;
    exp: bigint;
    entries: { payload: { tag: string; val: VhlPayload }; keyUsageAuthorised: boolean }[];
    dsc: { der: Uint8Array; participant?: string };
  }
  interface IssueRequest {
    payloads: ({ tag: "vhl"; val: VhlPayload } | { tag: "dvc-min"; val: object })[];
    iss: string;
    validity: { iat: bigint; exp: bigint };
    dscDer: Uint8Array;
    alg: "es256";
    allowDevelopmentSubclaims: boolean;
  }
  interface SignatureRequest {
    method: string;
    authority: string;
    path: string;
    contentType?: string;
    body?: Uint8Array;
    keyid: string;
    alg: string;
    created: number;
  }
  export const WitVhl: {
    getInstance(): {
      buildManifestRequest(link: Omit<VhlPayload, "exp"> & { exp?: number }, recipient: string, passcode: string | null, embeddedLengthMax: number | null): { url: string; contentType: string; body: string };
      parseManifest(bundleJson: string): { url?: string; reference?: string; contentType?: string; title?: string }[];
      newKey(): string;
      encrypt(content: Uint8Array, contentType: string, key: string): string;
      decrypt(jwe: string, key: string): { contentType?: string; content: Uint8Array };
      buildSignatureBase(request: SignatureRequest): { contentDigest?: string; signatureInput: string; base: Uint8Array };
      signatureHeader(signature: Uint8Array, alg: string): string;
    };
  };
  export class WitRegistry {
    constructor();
  }
  export class WitTrustStore {
    constructor();
    candidates(kid: Uint8Array): { participant?: string }[];
    addDidDocument(json: string): number;
    addVerifiedDidDocument(json: string, signer: string): number;
    size(): number;
  }
  export const WitTrust: {
    getInstance(): { verifyDidProof(document: string, signer: string): void };
  };
  export const WitVerifier: {
    getInstance(): {
      readUnverified(hc1: string, reg: WitRegistry, limits: object): { kid?: Uint8Array; iss?: string; iat?: bigint; entries: { payload: { val: unknown } }[] };
      verify(
        hc1: string,
        store: WitTrustStore,
        reg: WitRegistry,
        now: bigint,
        options: { limits: object; clockSkewSeconds: number },
      ): { tag: "verified"; val: Verified } | { tag: "failed"; val: Failure };
    };
  };
  export const WitCodec: {
    getInstance(): { fromHc1(hc1: string, limits: object): Uint8Array };
  };
  export const WitCose: {
    getInstance(): { parseSign1(cose: Uint8Array, limits: object): { alg: string } };
  };
  export class WitPendingSignature {
    toBeSigned(): Uint8Array;
  }
  export const WitIssuer: {
    getInstance(): {
      prepare(request: IssueRequest, reg: WitRegistry): WitPendingSignature;
      finish(pending: WitPendingSignature, signature: Uint8Array): { hc1: string };
    };
  };
}
