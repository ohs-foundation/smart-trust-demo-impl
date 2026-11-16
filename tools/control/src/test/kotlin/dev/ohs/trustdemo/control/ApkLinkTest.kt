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

import com.github.ajalt.clikt.testing.CliktCommandTestResult
import com.github.ajalt.clikt.testing.test
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.net.UnknownHostException
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.readText
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val TOKEN = "ya29.never-printed"
private const val ACCOUNT = "trust-demo-server@my-trust-demo.iam.gserviceaccount.com"
private val SIGNATURE = byteArrayOf(1, 2, 3, -2, -1)
private val NOW = Instant.parse("2026-11-16T10:15:30.250Z")
private val SHORT_LINK = Regex("https://web\\.onesa\\.demo\\.example\\.org/app/([A-Za-z0-9_-]{22})")

/** The metadata server, Cloud Storage's object metadata and IAM Credentials' signBlob, faked. */
private class FakeGoogle(
  private val updated: Map<String, String> =
    mapOf("onesa-latest.apk" to "2026-11-16T08:30:12.345Z"),
  private val onGce: Boolean = true,
) {
  val signed = mutableListOf<String>()
  val bearers = mutableListOf<String?>()
  val pages = createTempDirectory()

  val engine = MockEngine { request ->
    val json = HttpHeaders.ContentType to listOf("application/json")
    when (request.url.host) {
      "metadata.google.internal" -> {
        if (!onGce) throw UnknownHostException(request.url.host)
        assertEquals("Google", request.headers["Metadata-Flavor"])
        val google = "Metadata-Flavor" to listOf("Google")
        when (request.url.encodedPath.substringAfterLast('/')) {
          "email" -> respond(ACCOUNT, headers = headersOf(google))
          "token" -> respond("""{"access_token":"$TOKEN"}""", headers = headersOf(google, json))
          else -> error("unexpected ${request.url}")
        }
      }
      "storage.googleapis.com" -> {
        bearers += request.headers[HttpHeaders.Authorization]
        val name = request.url.encodedPath.substringAfterLast("/o/")
        updated[name]?.let {
          respond("""{"name":"$name","updated":"$it"}""", headers = headersOf(json))
        } ?: respond("""{"error":{"code":404}}""", HttpStatusCode.NotFound, headersOf(json))
      }
      "iamcredentials.googleapis.com" -> {
        assertEquals(
          "/v1/projects/-/serviceAccounts/$ACCOUNT:signBlob",
          request.url.encodedPath.replace("%40", "@"),
        )
        bearers += request.headers[HttpHeaders.Authorization]
        val payload =
          Json.parseToJsonElement((request.body as TextContent).text)
            .jsonObject
            .getValue("payload")
            .jsonPrimitive
            .content
        signed += Base64.getDecoder().decode(payload).decodeToString()
        val blob = Base64.getEncoder().encodeToString(SIGNATURE)
        respond("""{"keyId":"k1","signedBlob":"$blob"}""", headers = headersOf(json))
      }
      else -> error("unexpected ${request.url}")
    }
  }

  fun run(args: String = ""): CliktCommandTestResult {
    val env =
      createTempFile().apply {
        writeText(
          "APK_BUCKET=test-bucket\nBASE_DOMAIN=demo.example.org\n" +
            "WEB_HOST=web.onesa.\${BASE_DOMAIN}\nAPP_LINK_DIR=$pages\n"
        )
      }
    return ApkLinkCommand({ engine }, { NOW }).test("$args --stack-env $env")
  }
}

private fun printed(output: String, label: String) =
  output.lines().first { it.startsWith(label) }.substringAfter(label).trim()

class ApkLinkTest {
  // googleapis/conformance-tests storage/v1/v4_signatures.json, "Simple GET".
  @Test
  fun aSimpleGetMatchesThePublishedV4Vector() {
    val link =
      SignedUrl(
        "test-bucket",
        "test-object",
        "test-iam-credentials@dummy-project-id.iam.gserviceaccount.com",
        Instant.parse("2019-02-01T09:00:00Z"),
        10,
      )
    assertEquals(
      "GET\n/test-bucket/test-object\nX-Goog-Algorithm=GOOG4-RSA-SHA256&X-Goog-Credential=test-iam-credentials%40dummy-project-id.iam.gserviceaccount.com%2F20190201%2Fauto%2Fstorage%2Fgoog4_request&X-Goog-Date=20190201T090000Z&X-Goog-Expires=10&X-Goog-SignedHeaders=host\nhost:storage.googleapis.com\n\nhost\nUNSIGNED-PAYLOAD",
      link.canonicalRequest,
    )
    assertEquals(
      "GOOG4-RSA-SHA256\n20190201T090000Z\n20190201/auto/storage/goog4_request\n00e2fb794ea93d7adb703edaebdd509821fcc7d4f1a79ac5c8d2b394df109320",
      link.stringToSign,
    )
    val signature =
      "95e6a13d43a1d1962e667f17397f2b80ac9bdd1669210d5e08e0135df9dff4e56113485dbe429ca2266487b9d1796ebdee2d7cf682a6ef3bb9fbb4c351686fba90d7b621cf1c4eb1fdf126460dd25fa0837dfdde0a9fd98662ce60844c458448fb2b352c203d9969cb74efa4bdb742287744a4f2308afa4af0e0773f55e32e92973619249214b97283b2daa14195244444e33f938138d1e5f561088ce8011f4986dda33a556412594db7c12fc40e1ff3f1bedeb7a42f5bcda0b9567f17f65855f65071fabb88ea12371877f3f77f10e1466fff6ff6973b74a933322ff0949ce357e20abe96c3dd5cfab42c9c83e740a4d32b9e11e146f0eb3404d2e975896f74"
    assertEquals(
      "https://storage.googleapis.com/test-bucket/test-object?X-Goog-Algorithm=GOOG4-RSA-SHA256&X-Goog-Credential=test-iam-credentials%40dummy-project-id.iam.gserviceaccount.com%2F20190201%2Fauto%2Fstorage%2Fgoog4_request&X-Goog-Date=20190201T090000Z&X-Goog-Expires=10&X-Goog-SignedHeaders=host&X-Goog-Signature=$signature",
      link.signed(HexFormat.of().parseHex(signature)),
    )
  }

  // The same file, "Forward Slashes should not be stripped": the object name's path encoding.
  @Test
  fun anObjectNameIsEncodedAsThePublishedV4Vector() {
    val link =
      SignedUrl(
        "test-bucket",
        "/path/with/slashes/under_score/amper&sand/file.ext",
        "test-iam-credentials@dummy-project-id.iam.gserviceaccount.com",
        Instant.parse("2019-02-01T09:00:00Z"),
        10,
      )
    assertEquals(
      "GOOG4-RSA-SHA256\n20190201T090000Z\n20190201/auto/storage/goog4_request\n63c601ecd6ccfec84f1113fc906609cbdf7651395f4300cecd96ddd2c35164f8",
      link.stringToSign,
    )
  }

  @Test
  fun theLinkIsSignedAsTheVmsAccountAndTheTokenIsNeverPrinted() {
    val google = FakeGoogle()
    val result = google.run()

    assertEquals(0, result.statusCode, result.output)
    assertFalse(TOKEN in result.output)
    assertTrue(google.bearers.isNotEmpty() && google.bearers.all { it == "Bearer $TOKEN" })
    assertContains(result.stdout, "onesa-latest.apk, updated 2026-11-16T08:30:12Z")
    assertContains(result.stdout, "Expires  2026-11-23T10:15:30Z")
    val url = printed(result.stdout, "Direct")
    assertTrue(url.startsWith("https://storage.googleapis.com/test-bucket/onesa-latest.apk?"), url)
    assertContains(url, "X-Goog-Date=20261116T101530Z&X-Goog-Expires=604800&")
    assertTrue(url.endsWith("&X-Goog-Signature=010203feff"), url)
    assertTrue(google.signed.single().startsWith("GOOG4-RSA-SHA256\n20261116T101530Z\n"))
    assertContains(result.stdout, "█")
  }

  @Test
  fun daysRunFromOneToSeven() {
    assertContains(FakeGoogle().run("--days 1").stdout, "X-Goog-Expires=86400&")
    listOf("0", "8").forEach {
      val result = FakeGoogle().run("--days $it")
      assertNotEquals(0, result.statusCode)
      assertContains(result.stderr, "--days")
    }
  }

  @Test
  fun aMissingBuildSaysCiMakesIt() {
    val result = FakeGoogle().run("--version abc1234")
    assertEquals(1, result.statusCode)
    assertContains(result.stderr, "no APK in the bucket yet: CI builds it on main, tags or by hand")
    assertContains(result.stderr, "onesa-abc1234.apk")
  }

  @Test
  fun offGoogleCloudItSaysToRunOnTheServer() {
    val result = FakeGoogle(onGce = false).run()
    assertEquals(1, result.statusCode)
    assertContains(result.stderr, "run it on the server: infra/gcp/server.sh trust-demo apk-link")
  }

  @Test
  fun theShortLinkPrintsBeforeTheSignedOneAndIsWhatTheQrHolds() {
    val result = FakeGoogle().run()
    val lines = result.stdout.lines()
    val link = printed(result.stdout, "Link")

    assertTrue(SHORT_LINK.matches(link), link)
    assertTrue(
      lines.indexOfFirst { it.startsWith("Link") } < lines.indexOfFirst { it.startsWith("Direct") }
    )
    assertEquals(link, decodeHalfBlocks(result.stdout.substringAfter("\n\n")))
  }

  @Test
  fun theShortLinksQrFitsALaptopTerminal() {
    val qr = FakeGoogle().run().stdout.substringAfter("\n\n").lines().filter { it.isNotEmpty() }
    assertTrue(qr.size <= 22, "${qr.size} rows")
    assertTrue(qr.all { it.length <= 41 }, "${qr.maxOf { it.length }} columns")
  }

  @Test
  fun theShortLinksPageForwardsToTheSignedLinkAndLoadsNothingElse() {
    val google = FakeGoogle()
    val result = google.run()
    val direct = printed(result.stdout, "Direct")
    val page =
      google.pages.resolve(SHORT_LINK.matchEntire(printed(result.stdout, "Link"))!!.groupValues[1])
    val html = page.readText()
    val href = direct.replace("&", "&amp;")

    assertContains(html, """<meta http-equiv="refresh" content="0; url=$href">""")
    assertContains(html, """<a href="$href">Download the Onesa app</a>""")
    assertContains(html, "23 Nov 2026, 10:15 UTC")
    assertEquals(
      listOf(href, href),
      Regex("""https?://[^"\s>]*""").findAll(html).map { it.value }.toList(),
    )
    listOf("src=", "<link", "<script", "url(").forEach { assertFalse(it in html, it) }
    assertEquals(Instant.parse("2026-11-23T10:15:30Z"), page.getLastModifiedTime().toInstant())
  }

  @Test
  fun aRunDeletesOnlyTheExpiredPages() {
    val google = FakeGoogle()
    fun page(name: String, expires: Instant) =
      google.pages.resolve(name).apply {
        writeText("page")
        setLastModifiedTime(FileTime.from(expires))
      }
    val expired = page("expiredAAAAAAAAAAAAAAA", NOW.minusSeconds(1))
    val live = page("liveAAAAAAAAAAAAAAAAAA", NOW.plusSeconds(60))
    val other = page("notes.txt", NOW.minusSeconds(86_400))

    google.run()

    assertFalse(expired.exists())
    assertTrue(live.exists() && other.exists())
  }
}
