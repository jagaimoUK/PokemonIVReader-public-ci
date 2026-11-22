package com.example.pokemonivreader

import android.app.Application
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.work.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

class ReaderApplication:Application() {
    override fun onCreate() {
        super.onCreate()
        VisualCloudBackup.install(this)
        runCatching { ReaderIndividualSync.schedule(this) }
    }
}

internal object VisualCloudBackup {
    const val ORIGIN="https://pokemoniv-production.up.railway.app"
    private const val ALIAS="visual-backup-credential-v1"
    private val ioLock=Any()
    private fun file(context:Context)=File(context.noBackupFilesDir,"visual-cloud-connection-v1")
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    private fun load(context:Context):JSONObject? = try {
        val f=file(context);if(!f.isFile)null else {
            val envelope=JSONObject(f.readText());val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(envelope.getString("iv"),Base64.NO_WRAP)))
            JSONObject(String(cipher.doFinal(Base64.decode(envelope.getString("body"),Base64.NO_WRAP)),Charsets.UTF_8))
        }
    } catch(_:Exception) { null }
    private fun save(context:Context,value:JSONObject) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
        val envelope=JSONObject().put("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP))
            .put("body",Base64.encodeToString(cipher.doFinal(value.toString().toByteArray()),Base64.NO_WRAP))
        val target=file(context);val temporary=File(target.parentFile,target.name+".tmp")
        temporary.writeText(envelope.toString())
        java.nio.file.Files.move(temporary.toPath(),target.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
    fun connected(context:Context)=file(context).isFile
    fun status(context:Context)=context.getSharedPreferences("visual_cloud_state",Context.MODE_PRIVATE)
        .getString("status","未接続。見本はこの端末内に保存されています").orEmpty()
    private fun report(context:Context,message:String) {
        context.getSharedPreferences("visual_cloud_state",Context.MODE_PRIVATE).edit().putString("status",message).apply()
    }
    fun install(context:Context) {
        VisualFormMemory.onReferencesChanged={schedule(it)}
        val constraints=Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("visual-cloud-periodic",ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<VisualBackupWorker>(15,TimeUnit.MINUTES).setConstraints(constraints).build())
        schedule(context)
    }
    fun schedule(context:Context) {
        if(!connected(context))return
        val constraints=Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()
        WorkManager.getInstance(context).enqueueUniqueWork("visual-cloud-save",ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<VisualBackupWorker>().setInitialDelay(15,TimeUnit.SECONDS)
                .setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())
    }
    private class RemoteError(val status:Int,message:String):Exception(message)
    private fun call(connection:JSONObject,operation:String,args:JSONObject=JSONObject()):JSONObject {
        val url=URL("$ORIGIN/visual-backup/v1/$operation")
        val http=url.openConnection() as HttpURLConnection
        try {
            http.requestMethod="POST";http.instanceFollowRedirects=false;http.connectTimeout=15000;http.readTimeout=90000
            http.setRequestProperty("Content-Type","application/json")
            http.setRequestProperty("Authorization","Bearer "+connection.getString("token"))
            val body=args.toString().toByteArray();http.doOutput=true;http.setFixedLengthStreamingMode(body.size)
            http.outputStream.use { it.write(body) }
            val status=http.responseCode
            val stream=if(status in 200..299)http.inputStream else http.errorStream
            val raw=stream?.use { input -> ByteArrayOutputStream().use { output ->
                val buffer=ByteArray(8192)
                while(true) { val n=input.read(buffer);if(n<0)break;require(output.size()+n<24*1024*1024);output.write(buffer,0,n) }
                output.toString("UTF-8")
            } }.orEmpty()
            val result=try { JSONObject(raw) } catch(_:Exception) { JSONObject() }
            if(status !in 200..299)throw RemoteError(status,result.optString("error","保存先に接続できません"))
            return result
        } finally { http.disconnect() }
    }
    fun connect(context:Context,code:String):String = synchronized(ioLock) {
        val token=code.trim().removePrefix("pivbackup1.")
        require(Regex("vb1\\.[0-9]{10,12}\\.[A-Za-z0-9_-]{24}\\.[0-9a-f]{64}").matches(token)) { "バックアップ用の接続コードを貼り付けてください" }
        val connection=JSONObject().put("token",token)
        val remote=call(connection,"status")
        connection.put("etag",remote.optString("etag")).put("active",!remote.optBoolean("exists"))
        save(context,connection)
        val message=if(remote.optBoolean("exists")) "接続しました。クラウドに${remote.optInt("count")}件あります。先に復元してください" else "接続しました。Wi-Fiなどの定額回線で自動バックアップします"
        report(context,message);schedule(context);message
    }
    fun upload(context:Context,manual:Boolean=false):String = synchronized(ioLock) {
        val connection=load(context) ?: return@synchronized "接続コードの設定が必要です"
        if(!connection.optBoolean("active") && !manual)return@synchronized status(context)
        try {
            if(manual) {
                val remote=call(connection,"status");connection.put("etag",remote.optString("etag"))
            }
            val archive=VisualFormMemory.store(context).exportBackup();val hash=VisualMemoryBackup.digest(archive)
            if(!manual && connection.optString("lastHash")==hash)return@synchronized status(context)
            val result=call(connection,"upload",JSONObject().put("archive",Base64.encodeToString(archive,Base64.NO_WRAP))
                .put("expectedEtag",connection.optString("etag")))
            require(result.optString("sha256")==hash) { "保存したデータを確認できません" }
            connection.put("etag",result.getString("etag")).put("lastHash",hash).put("active",true)
            save(context,connection)
            val message="${result.optInt("count")}件をクラウドに保存しました（${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}）"
            report(context,message);message
        } catch(e:Exception) {
            if(e is RemoteError && e.status in setOf(401,409)) { connection.put("active",false);save(context,connection) }
            report(context,"バックアップ未完了：${e.message ?: "接続エラー"}。端末内の見本は残っています")
            throw e
        }
    }
    fun restore(context:Context):String = synchronized(ioLock) {
        val connection=load(context) ?: error("先に接続コードを設定してください")
        val result=call(connection,"download");val archive=Base64.decode(result.getString("archive"),Base64.NO_WRAP)
        require(VisualMemoryBackup.digest(archive)==result.getString("sha256")) { "バックアップを確認できません" }
        val added=VisualFormMemory.store(context).restoreBackup(archive)
        connection.put("etag",result.getString("etag")).put("active",true).remove("lastHash")
        save(context,connection);val message="見本を${added}件追加して復元しました。端末の見本も保持しています"
        report(context,message);schedule(context);message
    }
    fun disconnect(context:Context):String = synchronized(ioLock) {
        file(context).delete()
        WorkManager.getInstance(context).cancelUniqueWork("visual-cloud-save")
        val message="自動バックアップを停止しました。端末とクラウドの見本は残っています"
        report(context,message);message
    }
}

class VisualBackupWorker(context:Context,params:WorkerParameters):Worker(context,params) {
    override fun doWork():Result = try {
        VisualCloudBackup.upload(applicationContext);Result.success()
    } catch(_:Exception) { if(runAttemptCount<4)Result.retry() else Result.failure() }
}
