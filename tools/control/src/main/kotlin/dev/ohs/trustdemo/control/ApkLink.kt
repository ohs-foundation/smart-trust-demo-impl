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

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import java.io.IOException
import java.nio.channels.UnresolvedAddressException
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.HexFormat
import java.util.Locale
import kotlin.io.path.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val STORAGE_HOST = "storage.googleapis.com"
private const val ALGORITHM = "GOOG4-RSA-SHA256"
private const val METADATA =
  "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default"
private const val MAX_DAYS = 7
private const val SECONDS_PER_DAY = 86_400L
private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
private const val DEFAULT_APP_LINK_DIR = "/var/lib/trust-demo/app-links"
private const val TOKEN_BYTES = 16
private val TOKEN = Regex("[A-Za-z0-9_-]{22}")
private val UNTIL =
  DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC)

/**
 * A GET link to one Cloud Storage object that works for anyone until it expires: a V4 query-string
 * signature, path-style on storage.googleapis.com, made as [account]. The caller signs
 * [stringToSign] with that account's key and passes the signature to [signed].
 */
class SignedUrl(
  bucket: String,
  objectName: String,
  account: String,
  signedAt: Instant,
  seconds: Long,
) {
  private val stamp = STAMP.format(signedAt)
  private val scope = "${stamp.take(8)}/auto/storage/goog4_request"
  private val path = "/$bucket/" + objectName.split('/').joinToString("/", transform = ::encode)
  private val query =
    listOf(
        "X-Goog-Algorithm" to ALGORITHM,
        "X-Goog-Credential" to "$account/$scope",
        "X-Goog-Date" to stamp,
        "X-Goog-Expires" to "$seconds",
        "X-Goog-SignedHeaders" to "host",
      )
      .joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }

  val expires: Instant = signedAt.plusSeconds(seconds)

  val canonicalRequest =
    listOf("GET", path, query, "host:$STORAGE_HOST", "", "host", "UNSIGNED-PAYLOAD")
      .joinToString("\n")

  val stringToSign =
    listOf(ALGORITHM, stamp, scope, HexFormat.of().formatHex(sha256(canonicalRequest)))
      .joinToString("\n")

  fun signed(signature: ByteArray) =
    "https://$STORAGE_HOST$path?$query&X-Goog-Signature=${HexFormat.of().formatHex(signature)}"
}

private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())

private val UNRESERVED = (('A'..'Z') + ('a'..'z') + ('0'..'9') + "-._~".toList()).toSet()

/** RFC 3986 percent-encoding of the UTF-8 bytes: everything but the unreserved characters. */
private fun encode(text: String) =
  text.toByteArray().joinToString("") { byte ->
    val code = byte.toInt() and 0xFF
    if (code.toChar() in UNRESERVED) "${code.toChar()}" else "%%%02X".format(code)
  }

/**
 * Cloud Storage and IAM Credentials as the VM's own service account. Its token comes from the GCE
 * metadata server and stays in here: no message or output ever carries it.
 */
class GoogleCloud(engine: HttpClientEngine) {
  private val client = HttpClient(engine)
  private var token: String? = null

  suspend fun account(): String = metadata("email").trim()

  /** When [name] in [bucket] was last written, or null if there is no such object. */
  suspend fun updated(bucket: String, name: String): Instant? {
    val url = "https://$STORAGE_HOST/storage/v1/b/$bucket/o/${encode(name)}"
    val response = client.get(url) { bearerAuth(bearer()) }
    if (response.status == HttpStatusCode.NotFound) return null
    if (!response.status.isSuccess()) refused(HttpMethod.Get, url, response.status)
    return Instant.parse(field(response.bodyAsText(), "updated"))
  }

  /** Signs [text] with [account]'s Google-held key; the VM may sign only as itself. */
  suspend fun signBlob(account: String, text: String): ByteArray {
    val url =
      "https://iamcredentials.googleapis.com/v1/projects/-/serviceAccounts/$account:signBlob"
    val payload = Base64.getEncoder().encodeToString(text.toByteArray())
    val response =
      client.post(url) {
        bearerAuth(bearer())
        setBody(
          TextContent(
            buildJsonObject { put("payload", payload) }.toString(),
            ContentType.Application.Json,
          )
        )
      }
    if (!response.status.isSuccess()) refused(HttpMethod.Post, url, response.status)
    return Base64.getDecoder().decode(field(response.bodyAsText(), "signedBlob"))
  }

  private suspend fun bearer(): String =
    token ?: field(metadata("token"), "access_token").also { token = it }

  private suspend fun metadata(path: String): String {
    val response =
      try {
        client.get("$METADATA/$path") { header("Metadata-Flavor", "Google") }
      } catch (_: IOException) {
        throw offGce()
      } catch (_: UnresolvedAddressException) {
        throw offGce()
      }
    if (response.headers["Metadata-Flavor"] != "Google") throw offGce()
    if (!response.status.isSuccess()) refused(HttpMethod.Get, "$METADATA/$path", response.status)
    return response.bodyAsText()
  }

  private fun offGce() =
    CliktError(
      "No GCE metadata server here, and the link is signed as the VM's account: " +
        "run it on the server: infra/gcp/server.sh trust-demo apk-link"
    )

  private fun field(json: String, name: String) =
    Json.parseToJsonElement(json).jsonObject.getValue(name).jsonPrimitive.content
}

/**
 * The short links' pages, one file per random token in [dir], which nginx serves at `/app/<token>`.
 * A page's modification time is its expiry, so a later run deletes it once passed.
 */
class AppLinks(private val dir: Path, private val random: SecureRandom = SecureRandom()) {
  fun purge(now: Instant) =
    dir
      .listDirectoryEntries()
      .filter { TOKEN.matches(it.name) && it.getLastModifiedTime().toInstant() <= now }
      .forEach { it.deleteIfExists() }

  /** Writes the page forwarding to [url] until [expires] and returns its token. */
  fun add(url: String, expires: Instant): String {
    val token =
      Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes))
    dir.resolve(token).apply {
      writeText(page(url, expires))
      setPosixFilePermissions(PosixFilePermissions.fromString("rw-r--r--"))
      setLastModifiedTime(FileTime.from(expires))
    }
    return token
  }

  private fun page(url: String, expires: Instant): String {
    val href = url.replace("&", "&amp;").replace("\"", "&quot;")
    return """
      |<!doctype html>
      |<html lang="en">
      |<meta charset="utf-8">
      |<meta name="viewport" content="width=device-width, initial-scale=1">
      |<meta http-equiv="refresh" content="0; url=$href">
      |<title>Onesa app</title>
      |<p><a href="$href">Download the Onesa app</a></p>
      |<p>This link works until ${UNTIL.format(expires)}.</p>
      |"""
      .trimMargin()
  }
}

class ApkLinkCommand(
  private val engine: () -> HttpClientEngine = ::serverEngine,
  private val now: () -> Instant = Instant::now,
) : StackCommand("apk-link") {
  private val days by
    option("--days", help = "How many days the link works, 1 to 7")
      .int()
      .restrictTo(1..MAX_DAYS)
      .default(MAX_DAYS)
  private val version by
    option("--version", help = "A build's short commit SHA or tag; default: the latest build")

  override fun help(context: Context) =
    "Print an expiring short download link and QR for the debug APK in the private bucket."

  override fun run() = runBlocking {
    val env = StackEnv.load(stackEnv)
    val bucket =
      env.optional("APK_BUCKET")?.takeIf { it.isNotBlank() }
        ?: throw CliktError(
          "$stackEnv has no APK_BUCKET: set it to the apk_bucket output of infra/gcp"
        )
    val webHost =
      env.optional("WEB_HOST")?.takeIf { it.isNotBlank() }
        ?: throw CliktError("$stackEnv has no WEB_HOST: the short link is served from it")
    val pages =
      Path(env.optional("APP_LINK_DIR")?.takeIf { it.isNotBlank() } ?: DEFAULT_APP_LINK_DIR)
    if (!pages.isDirectory())
      throw CliktError("$pages does not exist: infra/gcp/server.sh setup creates it")
    val name = "onesa-${version ?: "latest"}.apk"
    val google = GoogleCloud(engine())
    val account = google.account()
    val updated =
      google.updated(bucket, name)
        ?: throw CliktError(
          "no APK in the bucket yet: CI builds it on main, tags or by hand (looked for $name)"
        )
    val link =
      SignedUrl(
        bucket,
        name,
        account,
        now().truncatedTo(ChronoUnit.SECONDS),
        days * SECONDS_PER_DAY,
      )
    val url = link.signed(google.signBlob(account, link.stringToSign))
    val links = AppLinks(pages)
    links.purge(now())
    val short = "https://$webHost/app/${links.add(url, link.expires)}"
    echo("Build    $name, updated ${updated.truncatedTo(ChronoUnit.SECONDS)}")
    echo("Expires  ${link.expires}")
    echo("Link     $short")
    echo("Direct   $url")
    echo()
    echo(TerminalQr.render(short))
  }
}
