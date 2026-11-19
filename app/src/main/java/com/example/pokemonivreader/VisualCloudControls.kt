package com.example.pokemonivreader

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun VisualCloudControls(onChanged:(String)->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var open by remember { mutableStateOf(false) };var busy by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") };var message by remember { mutableStateOf(VisualCloudBackup.status(context)) }
    var confirmUpload by remember { mutableStateOf(false) };var confirmRestore by remember { mutableStateOf(false) }
    fun run(action:()->String) {
        busy=true
        scope.launch { try { message=withContext(Dispatchers.IO) { action() };onChanged(message) }
            catch(e:Exception){message=e.message ?: "接続できませんでした";onChanged(message)} finally {busy=false} }
    }
    TextButton(onClick={message=VisualCloudBackup.status(context);open=true}) { Text("見本のクラウド保存") }
    if(open)AlertDialog(onDismissRequest={if(!busy)open=false},title={Text("見本のクラウド保存")},
        text={Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("正解付き見本の画像と特徴だけを非公開でバックアップします。CP・個体値・読み取り履歴は送信しません。接続後はWi-Fiなどの定額回線で自動保存します。")
            Text(message,style=MaterialTheme.typography.bodySmall)
            if(!VisualCloudBackup.connected(context)) {
                TextButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(VisualCloudBackup.ORIGIN+"/reader-backup")))},enabled=!busy){Text("接続コードを取得")}
                OutlinedTextField(value=code,onValueChange={code=it},label={Text("バックアップ用接続コード")},enabled=!busy)
                Row {
                    TextButton(onClick={val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        code=clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()},enabled=!busy){Text("貼り付け")}
                    TextButton(onClick={val value=code;run { VisualCloudBackup.connect(context,value) };code=""},enabled=!busy && code.isNotBlank()){Text("接続する")}
                }
            } else {
                TextButton(onClick={confirmUpload=true},enabled=!busy){Text("今すぐバックアップ")}
                TextButton(onClick={confirmRestore=true},enabled=!busy){Text("クラウドから見本を復元")}
                TextButton(onClick={run { VisualCloudBackup.disconnect(context) }},enabled=!busy){Text("接続を解除")}
            }
            if(busy)Text("処理しています…")
        }},confirmButton={TextButton(onClick={open=false},enabled=!busy){Text("閉じる")}})
    if(confirmUpload)AlertDialog(onDismissRequest={confirmUpload=false},title={Text("クラウドへ保存")},
        text={Text("クラウドの保存内容を、この端末の正解付き見本で置き換えます。別端末の見本を引き継ぐ場合は先に復元してください。")},
        confirmButton={TextButton(onClick={confirmUpload=false;run { VisualCloudBackup.upload(context,true) }}){Text("この端末の見本を保存")}},
        dismissButton={TextButton(onClick={confirmUpload=false}){Text("キャンセル")}})
    if(confirmRestore)AlertDialog(onDismissRequest={confirmRestore=false},title={Text("見本を復元")},
        text={Text("クラウドの見本を端末の見本に追加します。似た見本はまとめ、各姿24件・全体512件の上限内で手動の見本を優先します。")},
        confirmButton={TextButton(onClick={confirmRestore=false;run { VisualCloudBackup.restore(context) }}){Text("復元する")}},
        dismissButton={TextButton(onClick={confirmRestore=false}){Text("キャンセル")}})
}
