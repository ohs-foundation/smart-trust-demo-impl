# VHL client contract

What a clinic's server must do to open a family's Verifiable Health Link (VHL) from Onesa. The
Taifafeki facility client's server functions follow it; `spikes/rfc9421/send.mjs` is a working
example. Every step uses hcert-core (`io.github.litlfred:hcert-core` 0.1.0; on Node its Kotlin/JS
build, `WitVhl.getInstance()`), so the client and the Onesa VHL service compute the same bytes.

| Step | Client does | Onesa answers |
| --- | --- | --- |
| 1. Read the link | Decode the QR text and **verify** the link's HCERT against the trust lists (`DEMO-VHL`), then check its `exp` | — |
| 2. Ask for the manifest (ITI-YY5) | `buildManifestRequest(link, recipient, passcode, null)`: `POST` to the link's `url`, JSON body | the manifest, or a refusal |
| 3. Sign it | RFC 9421, as below | 401 if the signature or certificate fails |
| 4. Read the manifest | `parseManifest(bundleJson)` → document URLs and content types | — |
| 5. Fetch each document (ITI-68) | Signed `GET` of the document URL | the JWE (`application/jose`), or a refusal |
| 6. Decrypt | `decrypt(jwe, link.key)` → the IPS Bundle, held in memory only | — |

## Signing (steps 2 and 5)

- Covered components, built by `buildSignatureBase`: `@method`, `@path` (with the query string,
  exactly as sent), `@authority` (the URL's host **and port**, e.g. `vhl.onesa.<host>:8443`), and
  for the `POST` also `content-type` and `content-digest`. The `GET` sends neither header.
- Parameters: `created` (Unix seconds, within 300 s of Onesa's clock), `keyid` (base64 of the first
  8 bytes of SHA-256 of the clinic certificate's DER), `alg` `ecdsa-p256-sha256`.
- Headers: `Content-Type`, `Content-Digest`, `Signature-Input` (the base's `signatureInput`),
  `Signature` (`signatureHeader(rawSignature, alg)`, ES256 r‖s), and `Client-Cert` (RFC 9440,
  `:<base64 DER>:`).
- The clinic certificate must chain to the XXR clinic anchor on the trust list
  (`DEMO-VHL:XXR:CSCA`). The demo issues it at `trust-demo reset` into
  `stack/taifafeki/secrets/clinic-viewer.{crt,key}`.

## Transport

The VHL endpoint is HTTPS only, on `VHL_TLS_PORT` (8443), with a local TEST ONLY CA created by
`make stack-up` in `stack/tls/`. Trust it with `NODE_EXTRA_CA_CERTS=stack/tls/ca.crt`.

## Refusals

A refusal is an `OperationOutcome`; `issue.details.coding` carries the reason when the release rule
decided it:

| Code | System | Meaning |
| --- | --- | --- |
| `withdrawn` | `http://smart.who.int/trust-demo/CodeSystem/TrustDemoVhlRefusalReason` | the family withdrew this share |
| `expired` | same | the consent period has ended |
| `wrong-passcode` | same | the passcode does not match |
| `attempts-exhausted` | `http://ohs.dev/fhir/trust-demo/CodeSystem/vhl-refusal-reason` | three wrong passcodes; the link is locked |

A 403 without a reason means there is nothing to release (an unknown link, or a document fetched
without a fresh release). Documents are served only to the same certificate, within 10 minutes of
the manifest's release, while the consent is still active. Every decided request is audited on
Onesa (`TrustDemoOnesaVhlAccess`).
