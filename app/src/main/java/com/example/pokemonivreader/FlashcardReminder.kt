package com.example.pokemonivreader

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

internal object FlashcardReminder {
    private const val WORK = "flashcard-review-v1"
    const val CHANNEL = "flashcard_review"
    fun preferences(context: Context) = context.getSharedPreferences("flashcard_review_v1", Context.MODE_PRIVATE)

    fun nextNotification(due: Long, hour: Int, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val date = Instant.ofEpochMilli(maxOf(due, now)).atZone(zone).toLocalDate()
        var target = date.atTime(hour, 0).atZone(zone)
        if (target.toInstant().toEpochMilli() < maxOf(due, now)) target = target.plusDays(1)
        return target.toInstant().toEpochMilli()
    }

    fun update(context: Context, enabled: Boolean, due: Long, hour: Int) {
        val prefs = preferences(context)
        // Avoid replacing WorkManager work on every redraw/home navigation.
        val changed = prefs.getBoolean("enabled", false) != enabled || prefs.getLong("due", 0) != due || prefs.getInt("hour", 9) != hour
        prefs.edit().putBoolean("enabled", enabled).putLong("due", due).putInt("hour", hour).apply()
        if (!enabled || due <= 0) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(1601)
            return
        }
        if (!changed) return
        val delay = (nextNotification(due, hour, System.currentTimeMillis()) - System.currentTimeMillis()).coerceAtLeast(0)
        val request = PeriodicWorkRequestBuilder<FlashcardReviewWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request)
    }
}

class FlashcardReviewWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val context = applicationContext
        val prefs = FlashcardReminder.preferences(context)
        val now = System.currentTimeMillis()
        val today = LocalDate.now().toString()
        if (!prefs.getBoolean("enabled", false) || prefs.getLong("due", 0) <= 0 || prefs.getLong("due", 0) > now || prefs.getString("lastNotified", "") == today) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(FlashcardReminder.CHANNEL, "技カウントの復習", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 1601, Intent(context, FlashcardActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, FlashcardReminder.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("技カウントの復習")
            .setContentText("復習するカードがあります。タップしてはじめましょう。")
            .setContentIntent(open).setAutoCancel(true).build()
        manager.notify(1601, notification)
        prefs.edit().putString("lastNotified", today).apply()
        return Result.success()
    }
}
