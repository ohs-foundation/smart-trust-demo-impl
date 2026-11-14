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
package dev.ohs.trustdemo.control

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import dev.ohs.trustdemo.trustkit.StandIn
import dev.ohs.trustdemo.trustkit.TestKeys
import java.security.Signature
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.io.path.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import who.smart.trust.HcertResult
import who.smart.trust.core.Hcert
import who.smart.trust.issuer.IssueRequest
import who.smart.trust.issuer.Validity
import who.smart.trust.payloads.Payload
import who.smart.trust.types.CoseAlgorithm
import who.smart.trust.vhl.VhlPayload

private const val LINK_ISSUER = "XK"
private const val LINK_DAYS = 14L
private const val MANIFEST =
  "https://portal.onesa.example/fhir/List/_search?_id=TrustDemoManifest&code=folder&status=current"

private val WHO_CARDS = mapOf("who-hepb" to "hepb1", "who-bcg" to "bcg")
private val SOMSAK_CARDS =
  mapOf("somsak-hepb" to "TrustDemoJ1HepB", "somsak-bcg" to "TrustDemoJ1Bcg")
internal val CARDS = SOMSAK_CARDS.keys + WHO_CARDS.keys + "unknown-key-link"

class QrCommand : CliktCommand(name = "qr") {
  private val card by argument(help = "Which test card").choice(*CARDS.toTypedArray())
  private val text by option("--text", help = "Print the card's text, for pasting").flag()
  private val stackEnv by
    option("--stack-env", help = "The stack's .env file")
      .path(mustExist = true)
      .default(Path("stack/.env"))

  override fun help(context: Context) = "Print a test QR in the terminal."

  override fun run() {
    val hc1 = cardText()
    echo(if (text) hc1 else TerminalQr.render(hc1))
  }

  private fun cardText(): String =
    when (card) {
      in SOMSAK_CARDS -> somsak(SOMSAK_CARDS.getValue(card))
      in WHO_CARDS -> whoCard(WHO_CARDS.getValue(card))
      else -> unknownKeyLink()
    }

  private fun somsak(doseId: String): String = runBlocking {
    val env = StackEnv.load(stackEnv)
    val kesaro =
      MedplumAdmin(
        StackUrls.from(env, stackEnv).medplum,
        env.require("MEDPLUM_SUPER_ADMIN_CLIENT_ID"),
        env.require("MEDPLUM_SUPER_ADMIN_CLIENT_SECRET"),
        serverEngine(),
      )
    kesaro
      .searchResources("DocumentReference", mapOf("identifier" to HOSPITAL_NUMBER))
      .firstOrNull { it.cardAttachment()?.text("title") == "$doseId.hc1" }
      ?.cardText() ?: throw UsageError("no $doseId card on Medplum; run trust-demo reset")
  }

  private fun whoCard(name: String): String =
    Json.parseToJsonElement(
        checkNotNull(QrCommand::class.java.getResource("/who/dvc-qr-example-XXR.json")).readText()
      )
      .jsonObject
      .getValue("certificates")
      .jsonObject
      .getValue(name)
      .jsonObject
      .getValue("hc1")
      .jsonPrimitive
      .content

  private fun unknownKeyLink(): String {
    val issuedAt = Instant.now()
    val link = VhlPayload(url = MANIFEST, key = Hcert.vhl.newKey(), flag = "P", v = 1)
    val request =
      IssueRequest(
        payloads = listOf(Payload.Vhl(link)),
        iss = LINK_ISSUER,
        validity =
          Validity(
            iat = issuedAt.epochSecond,
            exp = issuedAt.plus(LINK_DAYS, ChronoUnit.DAYS).epochSecond,
          ),
        dscDer = TestKeys.certificate(StandIn.UNKNOWN_LINK).encoded,
        alg = CoseAlgorithm.ES256,
        allowDevelopmentSubclaims = true,
      )
    val pending = ok(Hcert.issuer.prepare(request, Hcert.subclaims.newRegistry()))
    val signature =
      Signature.getInstance("SHA256withECDSA").run {
        initSign(TestKeys.privateKey(StandIn.UNKNOWN_LINK))
        update(pending.toBeSigned())
        sign()
      }
    return ok(Hcert.issuer.finish(pending, signature)).hc1
  }

  private fun <T, E> ok(result: HcertResult<T, E>): T =
    when (result) {
      is HcertResult.Ok -> result.value
      is HcertResult.Err -> error("link issuance failed: ${result.error}")
    }
}
