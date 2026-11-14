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
private const val ANCHOR_PATH = "trustlist:DEMO-VHL:XXR:CSCA#"
private const val ANCHOR_USAGE = "#CSCA"
private val RETRY = 30.seconds

/** The signed trust list and the document that carries its signer's key, as fetched. */
class TrustListDocuments(val list: String, val signer: String)

/** Where the trust list comes from. */
fun interface TrustListSource {
  suspend fun fetch(): TrustListDocuments
}

/** Fetches the list and its signer's DID document over HTTP. */
class HttpTrustListSource(
  private val client: HttpClient,
  private val listUrl: String,
  private val signerUrl: String,
) : TrustListSource {
  override suspend fun fetch() = TrustListDocuments(get(listUrl), get(signerUrl))

  private suspend fun get(url: String): String {
    val response = client.get(url)
    check(response.status.isSuccess()) { "$url answered ${response.status.value}" }
    return response.bodyAsText()
  }
}

/** The certificate a clinic's certificate must chain to, or null when none is trusted yet. */
fun interface ClinicAnchor {
  fun current(): X509Certificate?
}

/**
 * The XXR clinic anchor, read from the signed trust list: the entry `DEMO-VHL:XXR:CSCA` whose
 * keyusage is `#CSCA`. A list that fails its proof or lacks that entry leaves the last good anchor.
 */
class ClinicTrustList(private val source: TrustListSource, private val trustDomains: String) :
  ClinicAnchor {
  @Volatile private var anchor: X509Certificate? = null

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
      val documents = source.fetch()
      anchor = verifiedAnchor(documents)
      log.info("trust list loaded")
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.warn("trust list not refreshed: {}", e.message)
    }
  }

  private fun verifiedAnchor(documents: TrustListDocuments): X509Certificate {
    val store = Hcert.trust.newTrustStore()
    check(store.loadTrustDomains(trustDomains) is HcertResult.Ok) { "trust domains do not load" }
    when (val added = store.addVerifiedDidDocument(documents.list, documents.signer)) {
      is HcertResult.Ok -> Unit
      is HcertResult.Err -> error("the list is not accepted: ${added.error}")
    }
    val methods = Json.parseToJsonElement(documents.list).jsonObject.getValue("verificationMethod")
    val entry =
      methods.jsonArray.map { it.jsonObject }.singleOrNull { it.isAnchor() }
        ?: error("the list has no single $ANCHOR_PATH entry")
    val der = entry.getValue("publicKeyJwk").jsonObject.getValue("x5c").jsonArray.first()
    return CertificateFactory.getInstance("X.509")
      .generateCertificate(
        ByteArrayInputStream(Base64.getDecoder().decode(der.jsonPrimitive.content))
      ) as X509Certificate
  }

  private fun JsonObject.isAnchor(): Boolean =
    getValue("id").jsonPrimitive.content.contains(ANCHOR_PATH) &&
      getValue("keyusage").jsonObject.getValue("code").jsonPrimitive.content == ANCHOR_USAGE
}
