package com.example.pokemonivreader

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

internal object ReaderIndividualSync {
    private const val ALIAS = "reader-individual-sync-v1"
    private val connection = Regex("""is1\.[0-9]{10,12}\.([A-Za-z0-9_-]{1,100})\.[A-Za-z0-9_-]{24}\.[0-9a-f]{64}""")
    class RemoteError(val status: Int) : IOException()

    fun instanceId(code: String): String =
        requireNotNull(connection.matchEntire(code)) { "個体同期用の接続コードを貼り付けてください" }.groupValues[1]

    fun schedule(context: Context) {
        val store = ReaderIndividualSyncStore(context)
        if (store.records().none { it.has("pending") && !it.optBoolean("blocked") }) return
        val work = OneTimeWorkRequestBuilder<ReaderIndividualSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ALIAS, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
    }

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    fun seal(code: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
            Base64.encodeToString(cipher.doFinal(code.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    fun open(envelope: String): String {
        val (iv, body) = envelope.split('.', limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return cipher.doFinal(Base64.decode(body, Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    fun fetch(code: String, id: String): JSONObject {
        val snapshot = call(code, JSONObject().put("action", "individual_get").put("instance_id", id))
        ReaderIndividualSyncStore.verifySnapshot(id, snapshot)
        return snapshot
    }

    fun call(code: String, request: JSONObject): JSONObject {
        require(request.getString("instance_id") == instanceId(code))
        val http = URL(ReaderWebEndpoints.ORIGIN + "/app-api/reader-sync").openConnection() as HttpsURLConnection
        try {
            http.requestMethod = "POST"
            http.instanceFollowRedirects = false
            http.connectTimeout = 10_000
            http.readTimeout = 25_000
            http.setRequestProperty("Content-Type", "application/json")
            http.setRequestProperty("Authorization", "Bearer $code")
            val body = request.toString().toByteArray(Charsets.UTF_8)
            http.doOutput = true
            http.setFixedLengthStreamingMode(body.size)
            http.outputStream.use { it.write(body) }
            if (http.responseCode != 200) throw RemoteError(http.responseCode)
            val raw = http.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 16_384)
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
            return JSONObject(raw)
        } finally {
            http.disconnect()
        }
    }

    /** A lost or malformed response preserves the exact request for a retry. */
    fun flush(store: ReaderIndividualSyncStore, send: (JSONObject, JSONObject) -> JSONObject): Boolean {
        var retry = false
        for (record in store.records()) {
            val request = record.optJSONObject("pending") ?: continue
            if (record.optBoolean("blocked")) continue
            val id = request.getString("instance_id")
            try {
                store.acknowledge(id, request, send(record, request))
            } catch (error: RemoteError) {
                if (error.status in listOf(400, 401, 403, 404, 409)) {
                    store.markBlocked(id, request.getString("request_id"))
                } else {
                    retry = true
                }
            } catch (_: Exception) {
                retry = true
            }
        }
        return retry
    }
}

class ReaderIndividualSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        val store = ReaderIndividualSyncStore(applicationContext)
        val retry = ReaderIndividualSync.flush(store) { record, request ->
            val code = try {
                ReaderIndividualSync.open(record.getString("credential"))
            } catch (_: Exception) {
                throw ReaderIndividualSync.RemoteError(401)
            }
            ReaderIndividualSync.call(code, request)
        }
        if (retry) Result.retry() else Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}
