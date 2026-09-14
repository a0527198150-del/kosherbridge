package com.example.kosherbridge.bluetooth

import android.content.Context
import android.os.Build
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Speaks ADB to this player's own daemon over the loopback address.
 *
 * The daemon treats us like any other ADB client, so commands run as `shell`
 * (uid 2000) - the identity that holds BLUETOOTH_PRIVILEGED and
 * WRITE_SECURE_SETTINGS. That is the same identity Shizuku hands out, obtained
 * without Shizuku, without a PC and without root.
 *
 * The only address ever dialled is 127.0.0.1. Nothing here reaches a network.
 */
@RequiresApi(Build.VERSION_CODES.R)
internal class LoopbackAdbShell(private val context: Context) : AdbShell {

  private val tag = "LoopbackAdb"
  private val loopback = "127.0.0.1"

  @Volatile private var paired = false
  @Volatile private var manager: Manager? = null

  init {
    // The daemon remembers the pairing, not us - but our key pair has to
    // survive, or a paired device is a stranger again on the next launch. This
    // marker is what tells the UI whether to ask for a code at all.
    paired = pairedMarker().exists()
  }

  override val supportedHere: Boolean get() = true

  override val state: AdbShell.State
    get() = when {
      manager?.isConnected == true -> AdbShell.State.CONNECTED
      paired -> AdbShell.State.PAIRED
      else -> AdbShell.State.NEEDS_PAIRING
    }

  override suspend fun pair(port: Int, code: String): String = withContext(Dispatchers.IO) {
    val digits = code.filter { it.isDigit() }
    if (digits.length != 6) {
      return@withContext "קוד ההתאמה הוא בן שש ספרות - התקבלו ${digits.length}"
    }
    if (port !in 1024..65535) return@withContext "מספר היציאה אינו תקין"
    runCatching { instance().pair(loopback, port, digits) }.fold(
      onSuccess = {
        paired = true
        runCatching { pairedMarker().writeText(port.toString()) }
        "ההתאמה הצליחה. עכשיו אפשר להתחבר"
      },
      onFailure = { error ->
        Log.w(tag, "pairing failed", error)
        // The commonest mistake here has the least helpful native error, so it
        // is named rather than left to be guessed at.
        "ההתאמה נכשלה: ${error.message ?: "שגיאה לא ידועה"}. ודא שחלון 'התאמת מכשיר " +
          "עם קוד התאמה' עדיין פתוח, ושהיציאה הועתקה מאותו חלון - היא שונה " +
          "מהיציאה שמוצגת במסך ניפוי הבאגים האלחוטי עצמו"
      },
    )
  }

  override suspend fun connect(port: Int?): String = withContext(Dispatchers.IO) {
    val mgr = runCatching { instance() }.getOrElse {
      return@withContext "לא ניתן להכין את מפתח ה-ADB: ${it.message ?: "שגיאה"}"
    }
    if (mgr.isConnected) return@withContext "כבר מחובר"
    runCatching {
      // The connect port is randomised on every boot, so discovery is the
      // normal path; a typed port is the fallback for players whose mDNS is
      // broken, which is common on the cheap ones.
      if (port != null) mgr.connect(loopback, port) else mgr.autoConnect(context, CONNECT_TIMEOUT_MS)
    }.fold(
      onSuccess = {
        if (mgr.isConnected) {
          "מחובר ל-ADB המקומי - ערוץ ההרשאות פעיל"
        } else {
          "החיבור לא הושלם. ודא שניפוי באגים אלחוטי דלוק, ונסה להזין יציאה ידנית"
        }
      },
      onFailure = { error ->
        Log.w(tag, "connect failed", error)
        if (error is AdbPairingRequiredException) {
          // Our key is not (or no longer) trusted by the daemon. Saying
          // "connect failed" here would send the user round the same loop for
          // ever; the honest answer is that the pairing has to be redone.
          paired = false
          runCatching { pairedMarker().delete() }
          "צריך להתאים קודם: הזן את קוד ההתאמה בן שש הספרות"
        } else {
          "החיבור נכשל: ${error.message ?: "שגיאה לא ידועה"}"
        }
      },
    )
  }

  override suspend fun exec(command: String): String = withContext(Dispatchers.IO) {
    val mgr = manager ?: return@withContext ""
    if (!mgr.isConnected) return@withContext ""
    runCatching { mgr.openStream("shell:$command").use { it.readAll() } }
      .getOrElse {
        Log.w(tag, "exec failed: $command", it)
        ""
      }
  }

  override suspend fun runDetached(command: String): Boolean = withContext(Dispatchers.IO) {
    val mgr = manager ?: return@withContext false
    if (!mgr.isConnected) return@withContext false
    runCatching {
      // Closing the stream straight away is right here and only here: the
      // command backgrounds itself, so reading to EOF would block until the
      // spawned process exits - which is never, that being the whole point.
      mgr.openStream("shell:$command").close()
      true
    }.getOrElse {
      Log.w(tag, "detached command failed", it)
      false
    }
  }

  override fun disconnect() {
    runCatching { manager?.disconnect() }
  }

  // ------------------------------------------------------------------ internals

  private fun pairedMarker() = File(context.filesDir, "adb_paired")

  private fun AdbStream.readAll(): String {
    val out = StringBuilder()
    val buffer = ByteArray(4096)
    openInputStream().use { input ->
      while (true) {
        val read = runCatching { input.read(buffer) }.getOrDefault(-1)
        if (read <= 0) break
        out.append(String(buffer, 0, read, Charsets.UTF_8))
      }
    }
    return out.toString().trim()
  }

  @Synchronized
  private fun instance(): Manager = manager ?: Manager(context).also {
    it.setApi(Build.VERSION.SDK_INT)
    it.setHostAddress(loopback)
    it.setTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    manager = it
  }

  /**
   * The library's connection manager, wired to a key pair kept in this app's
   * private storage.
   *
   * The key IS the identity the daemon trusts once paired. Losing it means
   * pairing again, so it is written once and reused, and it never leaves the
   * app's private directory.
   */
  private class Manager(context: Context) : AbsAdbConnectionManager() {

    private val key: PrivateKey
    private val cert: Certificate

    init {
      val keyFile = File(context.filesDir, KEY_FILE)
      val certFile = File(context.filesDir, CERT_FILE)
      val loaded = runCatching {
        val k = KeyFactory.getInstance("RSA")
          .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
        val c = certFile.inputStream().use {
          CertificateFactory.getInstance("X.509").generateCertificate(it)
        }
        k to c
      }.getOrNull()

      if (loaded != null) {
        key = loaded.first
        cert = loaded.second
      } else {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048, SecureRandom.getInstance("SHA1PRNG"))
        val pair = generator.generateKeyPair()
        key = pair.private
        cert = selfSigned(pair.public, pair.private)
        runCatching {
          keyFile.writeBytes(key.encoded)
          certFile.writeText(pem(cert.encoded))
        }
      }
    }

    override fun getPrivateKey(): PrivateKey = key

    override fun getCertificate(): Certificate = cert

    override fun getDeviceName(): String = "KosherBridge"

    private companion object {
      const val KEY_FILE = "adb_private.key"
      const val CERT_FILE = "adb_cert.pem"

      fun pem(der: ByteArray): String = buildString {
        append("-----BEGIN CERTIFICATE-----\n")
        append(Base64.encodeToString(der, Base64.NO_WRAP).chunked(64).joinToString("\n"))
        append("\n-----END CERTIFICATE-----\n")
      }

      /**
       * A self-signed certificate wrapping our public key - what the ADB
       * pairing protocol exchanges. Android's bundled crypto exposes no public
       * API for building one, which is the only reason sun-security-android is
       * a dependency of this flavour.
       */
      fun selfSigned(publicKey: PublicKey, privateKey: PrivateKey): Certificate {
        val algorithm = "SHA512withRSA"
        val notBefore = Date()
        // Ten years. The certificate is this install's identity to the daemon,
        // and an expiry would quietly turn a working channel into a stranger.
        val notAfter = Date(notBefore.time + 3650L * 24 * 60 * 60 * 1000)
        val name = X500Name("CN=KosherBridge")
        val extensions = CertificateExtensions().apply {
          set(
            "SubjectKeyIdentifier",
            SubjectKeyIdentifierExtension(KeyIdentifier(publicKey).identifier),
          )
          set("PrivateKeyUsage", PrivateKeyUsageExtension(notBefore, notAfter))
        }
        val info = X509CertInfo().apply {
          set("version", CertificateVersion(2))
          set("serialNumber", CertificateSerialNumber(SecureRandom().nextInt() and Int.MAX_VALUE))
          set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithm)))
          set("subject", CertificateSubjectName(name))
          set("key", CertificateX509Key(publicKey))
          set("validity", CertificateValidity(notBefore, notAfter))
          set("issuer", CertificateIssuerName(name))
          set("extensions", extensions)
        }
        return X509CertImpl(info).apply { sign(privateKey, algorithm) }
      }
    }
  }

  private companion object {
    const val CONNECT_TIMEOUT_MS = 10_000L
  }
}
