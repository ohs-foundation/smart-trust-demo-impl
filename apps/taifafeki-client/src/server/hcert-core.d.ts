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
    exp: bigint;
    entries: { payload: { tag: string; val: VhlPayload }; keyUsageAuthorised: boolean }[];
    dsc: { der: Uint8Array };
  }
  interface IssueRequest {
    payloads: { tag: "vhl"; val: VhlPayload }[];
    iss: string;
    validity: { iat: bigint; exp: bigint };
    dscDer: Uint8Array;
    alg: "es256";
    allowDevelopmentSubclaims: boolean;
  }
  export class WitRegistry {
    constructor();
  }
  export class WitTrustStore {
    constructor();
    addDidDocument(json: string): number;
    addVerifiedDidDocument(json: string, signer: string): number;
    size(): number;
  }
  export const WitTrust: {
    getInstance(): { verifyDidProof(document: string, signer: string): void };
  };
  export const WitVerifier: {
    getInstance(): {
      readUnverified(hc1: string, reg: WitRegistry, limits: object): { iat?: bigint; entries: { payload: { val: unknown } }[] };
      verify(
        hc1: string,
        store: WitTrustStore,
        reg: WitRegistry,
        now: bigint,
        options: { limits: object; clockSkewSeconds: number },
      ): { tag: "verified"; val: Verified } | { tag: "failed"; val: Failure };
    };
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
