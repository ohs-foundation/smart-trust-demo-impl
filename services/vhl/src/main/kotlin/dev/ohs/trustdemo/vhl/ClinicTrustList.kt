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
package dev.ohs.trustdemo.vhl

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import who.smart.trust.HcertResult
import who.smart.trust.core.Hcert

private val log = LoggerFactory.getLogger(ClinicTrustList::class.java)
private val ANCHOR = listOf("trustlist", "DEMO-VHL", "XXR", "CSCA")
private const val ANCHOR_USAGE = "#CSCA"
private val RETRY = 30.seconds

/** Where the signed trust list comes from. */
fun interface TrustListSource {
  suspend fun fetch(): String
}

/** Fetches the list over HTTP. */
class HttpTrustListSource(private val client: HttpClient, private val listUrl: String) :
  TrustListSource {
  override suspend fun fetch(): String {
    val response = client.get(listUrl)
    check(response.status.isSuccess()) { "$listUrl answered ${response.status.value}" }
    return response.bodyAsText()
  }
}

/** The certificate a clinic's certificate must chain to, or null when none is trusted yet. */
fun interface ClinicAnchor {
  fun current(): X509Certificate?
}

/**
 * The XXR clinic anchor, read from the signed trust list: the entry `DEMO-VHL:XXR:CSCA` whose
 * keyusage is `#CSCA`. The list must be signed by [signer], the pinned DID document of the list's
 * publisher, which is never fetched. A list that fails its proof or lacks that entry leaves the
 * last good anchor.
 */
class ClinicTrustList(
  private val source: TrustListSource,
  private val signer: String,
  private val trustDomains: String,
) : ClinicAnchor {
  @Volatile private var anchor: X509Certificate? = null

  init {
    val id =
      Json.parseToJsonElement(signer).jsonObject.getValue("verificationMethod").jsonArray.map {
        it.jsonObject.getValue("id").jsonPrimitive.content
      }
    log.info("trust list signer pinned: {}", id)
  }

  override fun current(): X509Certificate? = anchor

  /** Loads the list now, then again every [interval]; retries sooner until the first success. */
  suspend fun keepFresh(interval: Duration = 1.hours) {
    while (true) {
      refresh()
      delay(if (anchor == null) RETRY else interval)
    }
  }

  suspend fun refresh() {
    try {
      anchor = verifiedAnchor(source.fetch())
      log.info("trust list loaded")
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.warn("trust list not refreshed: {}", e.message)
    }
  }

  private fun verifiedAnchor(list: String): X509Certificate {
    val store = Hcert.trust.newTrustStore()
    check(store.loadTrustDomains(trustDomains) is HcertResult.Ok) { "trust domains do not load" }
    when (val added = store.addVerifiedDidDocument(list, signer)) {
      is HcertResult.Ok -> Unit
      is HcertResult.Err -> error("the list is not accepted: ${added.error}")
    }
    val methods = Json.parseToJsonElement(list).jsonObject.getValue("verificationMethod")
    val entry =
      methods.jsonArray.map { it.jsonObject }.singleOrNull { it.isAnchor() }
        ?: error("the list has no single ${ANCHOR.joinToString(":")} entry")
    val der = entry.getValue("publicKeyJwk").jsonObject.getValue("x5c").jsonArray.first()
    return CertificateFactory.getInstance("X.509")
      .generateCertificate(
        ByteArrayInputStream(Base64.getDecoder().decode(der.jsonPrimitive.content))
      ) as X509Certificate
  }

  private fun JsonObject.isAnchor(): Boolean =
    getValue("id").jsonPrimitive.content.substringBefore('#').split(':').takeLast(ANCHOR.size) ==
      ANCHOR &&
      getValue("keyusage").jsonObject.getValue("code").jsonPrimitive.content == ANCHOR_USAGE
}
