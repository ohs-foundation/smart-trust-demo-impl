# trustkit test kit

TEST ONLY. Fictional stand-in keys and certificates for the trust demo, never production keys. They are committed on purpose (no secret scanner is configured in this repo).

| File | What it is |
| --- | --- |
| `XXR_CARD`, `XXK_LINK`, `XXR_CLINIC_ANCHOR`, `UNKNOWN_LINK` `.key.pem` / `.crt.pem` | EC P-256 key (PKCS#8) and self-signed certificate per stand-in; the anchor is a CA; `UNKNOWN_LINK` is never listed |
| `GDHCN_SIGNER.key.pem` / `.crt.pem` | the stand-in GDHCN list signer |
| `gdhcn-signer-did.json` | the signer's did:web document (`did:web:trust.gdhcn.example:signing`), the `signer` argument of `addVerifiedDidDocument` |
| `local-test-list.json` | the signed trust list: XXR card (`DEMO-DVC`), XXK link and XXR clinic anchor (`DEMO-VHL`) |

Regenerate with `./gradlew :libs:trustkit:generateTestKit`. Existing keys and certificates are kept, so the list is re-signed from the same keys; delete a key and certificate pair to rotate it. The proof's `created` and `nonce` are fixed, but ECDSA signatures are randomised, so a re-signed list differs in its `jws`.

Golden vectors in `libs/trustkit/src/test/resources/golden/` are copied from WorldHealthOrganization/smart-trust-tools at `d94e21d` (`vectors/gdhcn-dev-trustlist/`): `XXR-DSC-did-2026-10-23.json`, `XXR-DSC-did.doc.nq`, `XXR-DSC-did.proof-options.nq`, `WHO-signing-did.json`.

`who/` holds the GDHCN DEV root list (`root-did.json`) and its signer document, copied from WorldHealthOrganization/smart-trust-tools at `d94e21d` (see `who/SOURCE.yaml`).
