/*
 * Copyright 2026 Open Health Stack Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.ohs.trustdemo.trustkit

import java.security.PrivateKey
import java.security.Signature
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Signs a GDHCN trust list DID document with a JsonWebSignature2020 proof that hcert-core verifies.
 *
 * The N-Quads and JCS functions are copied from hcert-core's internal `DidProof`
 * (WorldHealthOrganization/smart-trust-tools d94e21d, Apache-2.0, hcert-core/.../core/DidProof.kt),
 * which does not expose them. Differences: failures are IllegalArgumentException, JSON is parsed
 * with kotlinx.serialization, and Platform.sha256 became MessageDigest.
 */
object DidProofSigner {
  private const val SEC = "https://w3id.org/security#"
  private const val RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"
  private const val RDF_JSON = "http://www.w3.org/1999/02/22-rdf-syntax-ns#JSON"
  private const val XSD_DATETIME = "http://www.w3.org/2001/XMLSchema#dateTime"
  private const val DC_CREATED = "http://purl.org/dc/terms/created"

  private const val DID_V1 = "https://www.w3.org/ns/did/v1"
  private const val JWS_2020_V1 = "https://w3id.org/security/suites/jws-2020/v1"
  private const val TNG_CONTEXT = "https://smart.who.int/trust/tng-context/"

  private val TNG_ENVIRONMENTS =
    mapOf("v1.jsonld" to "", "v1-UAT.jsonld" to "-UAT", "v1-DEV.jsonld" to "-DEV")

  private val VALUE_SETS =
    linkedMapOf("domain" to "Domains", "participant" to "Participants", "keyusage" to "KeyUsage")

  private val DOC_KEYS = setOf("@context", "id", "controller", "verificationMethod")
  private val VM_KEYS = setOf("id", "type", "controller", "publicKeyJwk") + VALUE_SETS.keys
  private val PROOF_KEYS =
    setOf(
      "type",
      "created",
      "nonce",
      "proofPurpose",
      "verificationMethod",
      "domain",
      "challenge",
      "jws",
    )

  private const val JWS_HEADER = """{"b64":false,"crit":["b64"],"alg":"ES256"}"""

  private val pretty = Json { prettyPrint = true }

  /**
   * [document] with a proof by [signerKey], whose public half the signer DID document publishes as
   * [verificationMethod].
   */
  fun sign(
    document: String,
    signerKey: PrivateKey,
    verificationMethod: String,
    created: Instant,
    nonce: String,
  ): String {
    val doc = parse(document)
    val proofOptions =
      linkedMapOf<String, Any?>(
        "type" to "JsonWebSignature2020",
        "created" to created.truncatedTo(ChronoUnit.SECONDS).toString(),
        "nonce" to nonce,
        "proofPurpose" to "assertionMethod",
        "verificationMethod" to verificationMethod,
      )
    val header = b64url(JWS_HEADER.encodeToByteArray())
    val signingInput =
      header.encodeToByteArray() +
        '.'.code.toByte() +
        TestKeys.sha256(proofOptionsNQuads(proofOptions).encodeToByteArray()) +
        TestKeys.sha256(documentNQuads(doc).encodeToByteArray())
    val signature =
      Signature.getInstance("SHA256withECDSAinP1363Format").run {
        initSign(signerKey)
        update(signingInput)
        sign()
      }
    val proof =
      proofOptions.mapValues { JsonPrimitive(it.value as String) } +
        ("jws" to JsonPrimitive("$header..${b64url(signature)}"))
    val signed =
      JsonObject(Json.parseToJsonElement(document).jsonObject + ("proof" to JsonObject(proof)))
    return pretty.encodeToString(JsonElement.serializer(), signed)
  }

  internal fun parse(json: String): Map<String, Any?> =
    toValue(Json.parseToJsonElement(json)) as Map<String, Any?>

  private fun toValue(e: JsonElement): Any? =
    when (e) {
      is JsonObject -> e.mapValues { toValue(it.value) }
      is JsonArray -> e.map { toValue(it) }
      JsonNull -> null
      is JsonPrimitive ->
        if (e.isString) e.content else e.content.toBooleanStrictOrNull() ?: fail("number in JSON")
    }

  private fun b64url(bytes: ByteArray) =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

  private fun fail(message: String): Nothing = throw IllegalArgumentException(message)

  // ---- canonical N-Quads (copied) -------------------------------------------

  /** URDNA2015 N-Quads of a trust list DID document without its proof. */
  internal fun documentNQuads(doc: Map<*, *>): String {
    exactKeys(doc, DOC_KEYS, DOC_KEYS, "DID document")
    val ctx = doc["@context"] as? List<*> ?: fail("@context is not an array")
    val tng =
      (ctx.getOrNull(2) as? String)
        ?.takeIf { it.startsWith(TNG_CONTEXT) }
        ?.removePrefix(TNG_CONTEXT)
    val env = TNG_ENVIRONMENTS[tng]
    if (ctx.size != 3 || ctx[0] != DID_V1 || ctx[1] != JWS_2020_V1 || env == null)
      fail("@context is not [did/v1, jws-2020/v1, tng-context v1, v1-UAT or v1-DEV]")
    val q = mutableListOf<String>()
    val id = did(doc["id"], "id")
    q += quad(id, SEC + "controller", did(doc["controller"], "controller"))
    val methods =
      doc["verificationMethod"] as? List<*> ?: fail("verificationMethod is not an array")
    for (m in methods) {
      if (m is String) {
        q += quad(id, SEC + "verificationMethod", did(m, "verificationMethod"))
        continue
      }
      val vm =
        m as? Map<*, *> ?: fail("verificationMethod entry is neither a DID URL nor an object")
      exactKeys(
        vm,
        VM_KEYS,
        setOf("id", "type", "controller", "publicKeyJwk"),
        "verificationMethod",
      )
      if (vm["type"] != "JsonWebKey2020") fail("verificationMethod type is not JsonWebKey2020")
      val v = did(vm["id"], "verificationMethod id")
      q += quad(id, SEC + "verificationMethod", v)
      q += quad(v, RDF_TYPE, iri(SEC + "JsonWebKey2020"))
      q += quad(v, SEC + "controller", did(vm["controller"], "verificationMethod controller"))
      val jwk = vm["publicKeyJwk"] as? Map<*, *> ?: fail("publicKeyJwk is not an object")
      q += quad(v, SEC + "publicKeyJwk", literal(jcs(jwk)) + "^^" + iri(RDF_JSON))
      for ((term, name) in VALUE_SETS) {
        if (term !in vm) continue
        val set = vm[term] as? Map<*, *> ?: fail("$term is not an object")
        exactKeys(set, setOf("code"), setOf("code"), term)
        val code = set["code"] as? String ?: fail("$term code is not a string")
        if (!code.startsWith("#") || code.length < 2 || code.indexOf('#', 1) >= 0)
          fail("$term code is not a #fragment")
        val base = "https://smart.who.int/trust/ValueSet-$name$env.jsonld"
        q += quad(v, base, iri(base + code))
      }
    }
    return canonical(q)
  }

  /** URDNA2015 N-Quads of the proof options: the proof without jws, in the security v3 context. */
  internal fun proofOptionsNQuads(proof: Map<*, *>): String {
    exactKeys(
      proof,
      PROOF_KEYS - "jws",
      setOf("type", "created", "proofPurpose", "verificationMethod"),
      "proof",
    )
    if (proof["type"] != "JsonWebSignature2020") fail("proof type is not JsonWebSignature2020")
    if (proof["proofPurpose"] != "assertionMethod") fail("proofPurpose is not assertionMethod")
    val b = "_:c14n0"
    val q =
      mutableListOf(
        "$b ${iri(RDF_TYPE)} ${iri(SEC + "JsonWebSignature2020")}",
        "$b ${iri(DC_CREATED)} ${literal(string(proof["created"], "created"))}^^${iri(XSD_DATETIME)}",
        "$b ${iri(SEC + "proofPurpose")} ${iri(SEC + "assertionMethod")}",
        "$b ${iri(SEC + "verificationMethod")} ${did(proof["verificationMethod"], "proof verificationMethod")}",
      )
    for (term in listOf("nonce", "domain", "challenge")) {
      if (term in proof) q += "$b ${iri(SEC + term)} ${literal(string(proof[term], term))}"
    }
    return canonical(q)
  }

  private fun exactKeys(m: Map<*, *>, allowed: Set<String>, required: Set<String>, what: String) {
    val extra = m.keys.filter { it !in allowed }
    if (extra.isNotEmpty())
      fail("$what has a member the profile does not model: ${extra.joinToString()}")
    val missing = required.filter { it !in m }
    if (missing.isNotEmpty()) fail("$what lacks ${missing.joinToString()}")
  }

  private fun string(v: Any?, what: String): String = v as? String ?: fail("$what is not a string")

  private fun quad(s: String, p: String, o: String) = "$s ${iri(p)} $o"

  private fun canonical(quads: List<String>): String {
    val lines = quads.map { "$it .\n" }.distinct()
    if (lines.any { l -> l.any { it.isSurrogate() } })
      fail("N-Quads with a code point above U+FFFF")
    return lines.sorted().joinToString("")
  }

  private fun did(v: Any?, what: String): String {
    val s = v as? String ?: fail("$what is not a string")
    if (!s.startsWith("did:")) fail("$what is not a DID")
    return iri(s)
  }

  private fun iri(s: String): String {
    if (s.any { it <= ' ' || it >= '\u007f' || it in "<>\"{}|^`\\" })
      fail("not a printable ASCII IRI: $s")
    return "<$s>"
  }

  private fun literal(s: String): String {
    checkLiteral(s)
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
  }

  private fun checkLiteral(s: String) {
    if (s.any { it < ' ' || it == '\u007f' } || hasLoneSurrogate(s))
      fail("literal with a control character, DEL or lone surrogate")
  }

  private fun hasLoneSurrogate(s: String): Boolean {
    var i = 0
    while (i < s.length) {
      val c = s[i]
      if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) i += 2
      else if (c.isSurrogate()) return true else i++
    }
    return false
  }

  /** RFC 8785 for a JWK whose members are strings or arrays of strings. */
  private fun jcs(jwk: Map<*, *>): String =
    jwk.keys
      .map { it as String }
      .sorted()
      .joinToString(",", "{", "}") { k ->
        jcsString(k) +
          ":" +
          when (val v = jwk[k]) {
            is String -> jcsString(v)
            is List<*> ->
              v.joinToString(",", "[", "]") {
                jcsString(it as? String ?: fail("publicKeyJwk $k holds a non-string"))
              }
            else -> fail("publicKeyJwk $k is neither a string nor an array of strings")
          }
      }

  private fun jcsString(s: String): String {
    checkLiteral(s)
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
  }
}
