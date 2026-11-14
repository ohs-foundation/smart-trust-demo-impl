declare module "smart-trust-kotlin-hcert-core" {
  export class WitRegistry {
    constructor();
  }
  export const WitVerifier: {
    getInstance(): {
      readUnverified(hc1: string, reg: WitRegistry, limits: object): { entries: { payload: { val: unknown } }[] };
    };
  };
}
