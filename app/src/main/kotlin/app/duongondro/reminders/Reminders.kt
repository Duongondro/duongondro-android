package app.duongondro.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.duongondro.MainActivity
import app.duongondro.R
import app.duongondro.core.Streak
import app.duongondro.core.civilDate
import app.duongondro.core.headline
import app.duongondro.model.Snapshot
import app.duongondro.model.SqliteStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The private streak-at-risk reminder: a local notification at the user's
 * evening time on days nothing has been logged yet. Scheduled on the phone, so
 * the server learns nothing (design: Social › Nudges). Two alarms at most:
 * today's and tomorrow's, replaced whenever the data changes.
 */
object Reminders {
    private const val CHANNEL = "streak-at-risk"
    private const val EXTRA_BODY = "body"

    fun reschedule(context: Context, snapshot: Snapshot, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val today = civilDate(now, zone)
        val days = listOf(today, today.plusDays(1))
        days.forEach { alarms.cancel(intent(context, it, null)) }
        val minutes = snapshot.preferences.reminderMinutes ?: return
        if (snapshot.activePractices.isEmpty()) return
        val practisedToday = snapshot.sessions.any { it.day == today }
        for (day in days) {
            if (day == today && practisedToday) continue
            // Built from wall-clock components, so a DST change that day does not shift it.
            val fire = day.atTime(minutes / 60, minutes % 60).atZone(zone).toInstant()
            if (!fire.isAfter(now)) continue
            // The streak as it will stand then: tomorrow's is alive only if today gets logged.
            val streak = Streak.headline(snapshot.sessions, snapshot.seeds, fire, zone).current
            val body = if (streak > 0) {
                context.resources.getQuantityString(R.plurals.reminder_streak_ends, streak, streak)
            } else context.getString(R.string.reminder_short_session)
            alarms.set(AlarmManager.RTC_WAKEUP, fire.toEpochMilli(), intent(context, day, body))
        }
    }

    private fun intent(context: Context, day: LocalDate, body: String?): PendingIntent {
        val i = Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_BODY, body)
        return PendingIntent.getBroadcast(context, day.toEpochDay().toInt(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    internal fun post(context: Context, body: String) {
        if (!canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_flame)
            .setContentTitle(context.getString(R.string.reminder_title))
            .setContentText(body)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(context).notify(CHANNEL.hashCode(), n) } catch (_: SecurityException) { }
    }

    internal const val BODY = EXTRA_BODY
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.post(context, intent.getStringExtra(Reminders.BODY) ?: context.getString(R.string.reminder_short_session))
    }
}

/** Alarms do not survive a reboot, and a changed clock or zone moves "today". */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val store = SqliteStore(context)
                store.load()
                Reminders.reschedule(context, store.snapshot.value)
                store.close()
            } finally {
                done.finish()
            }
        }
    }
}
