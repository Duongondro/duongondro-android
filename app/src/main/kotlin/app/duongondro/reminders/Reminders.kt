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
import androidx.core.content.edit
import androidx.core.net.toUri
import app.duongondro.MainActivity
import app.duongondro.R
import app.duongondro.core.Catalogue
import app.duongondro.core.Streak
import app.duongondro.core.UsualTime
import app.duongondro.core.civilDate
import app.duongondro.core.parseCivilDate
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
    private const val USUAL_CHANNEL = "usual-time"
    private const val EXTRA_BODY = "body"
    private const val EXTRA_DAY = "day"
    private const val EXTRA_KIND = "kind"
    internal const val DAY = EXTRA_DAY
    internal const val KIND = EXTRA_KIND
    internal const val USUAL = "usual"
    private const val EXTRA_TAG = "tag"
    internal const val TAG = EXTRA_TAG
    private const val USUAL_SCHEDULED = "usualScheduled"

    /**
     * The app's own language: AppCompat applies a per-app locale only to
     * activities before API 33, and these strings are built in receivers too.
     */
    private fun localized(context: Context): Context {
        val locales = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return context
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocales(android.os.LocaleList.forLanguageTags(locales.toLanguageTags()))
        return context.createConfigurationContext(config)
    }

    fun reschedule(context: Context, snapshot: Snapshot, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        scheduleUsualTime(context, alarms, snapshot, now, zone)
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
            val res = localized(context).resources
            val body = if (streak > 0) res.getQuantityString(R.plurals.reminder_streak_ends, streak, streak)
                       else res.getString(R.string.reminder_short_session)
            // Inexact, but allowed while idle so Doze cannot push it to another day;
            // the receiver drops it if that happens anyway.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fire.toEpochMilli(), intent(context, day, body))
        }
    }

    /**
     * "Shouldn't you be meditating?": an hour before the time each practice is
     * usually logged (the median of two weeks, UsualTime), today and tomorrow,
     * unless that practice is already done that day. Learned and scheduled on the
     * phone only; the alarms set last time are remembered so they can be replaced.
     */
    private fun scheduleUsualTime(context: Context, alarms: AlarmManager, snapshot: Snapshot, now: Instant, zone: ZoneId) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getStringSet(USUAL_SCHEDULED, emptySet()).orEmpty().forEach { key ->
            val (practice, day) = key.split(' ').takeIf { it.size == 2 } ?: return@forEach
            parseCivilDate(day)?.let { alarms.cancel(usualIntent(context, practice, it, null)) }
        }
        val scheduled = mutableSetOf<String>()
        if (snapshot.preferences.usualTimeNudge) {
            val today = civilDate(now, zone)
            val res = localized(context).resources
            for (p in snapshot.activePractices) {
                val sessions = snapshot.sessionsOf(p.id)
                val usual = UsualTime.minutes(sessions, now) ?: continue
                val minutes = (usual - 60 + 1440) % 1440
                for (day in listOf(today, today.plusDays(1))) {
                    if (sessions.any { it.day == day }) continue
                    val fire = day.atTime(minutes / 60, minutes % 60).atZone(zone).toInstant()
                    if (!fire.isAfter(now)) continue
                    val body = if (snapshot.preferences.discreetNotifications) res.getString(R.string.usual_body_discreet)
                               else res.getString(R.string.usual_body, practiceName(res, p.practice))
                    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fire.toEpochMilli(), usualIntent(context, p.id, day, body))
                    scheduled += "${p.id} $day"
                }
            }
        }
        prefs.edit { putStringSet(USUAL_SCHEDULED, scheduled) }
    }

    private fun practiceName(res: android.content.res.Resources, p: app.duongondro.core.Practice): String =
        if (p.isCustom) p.name else app.duongondro.ui.practiceNameRes(p.id)?.let(res::getString) ?: p.name

    /** One alarm per practice and day: the data URI tells them apart. */
    private fun usualIntent(context: Context, practice: String, day: LocalDate, body: String?): PendingIntent {
        val i = Intent(context, ReminderReceiver::class.java)
            .setData("duongondro-usual:$practice/$day".toUri())
            .putExtra(EXTRA_BODY, body).putExtra(EXTRA_DAY, day.toString()).putExtra(EXTRA_KIND, USUAL)
            .putExtra(EXTRA_TAG, "$practice $day")
        return PendingIntent.getBroadcast(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private const val PREFS = "reminders"

    private fun intent(context: Context, day: LocalDate, body: String?): PendingIntent {
        val i = Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_BODY, body).putExtra(EXTRA_DAY, day.toString())
        return PendingIntent.getBroadcast(context, day.toEpochDay().toInt(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Permission on 13+, and notifications not switched off for the app on any version. */
    fun canNotify(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * Posts a reminder. Usual-time nudges carry their practice and day as [tag],
     * so two practices' nudges stand side by side rather than replace each other;
     * the evening reminder is one notification, replaced.
     */
    internal fun post(context: Context, body: String, usual: Boolean = false, tag: String? = null) {
        if (!canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = if (usual) USUAL_CHANNEL else CHANNEL
        manager.createNotificationChannel(NotificationChannel(channel,
            localized(context).getString(if (usual) R.string.usual_time_nudge else R.string.reminder_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_flame)
            .setContentTitle(localized(context).getString(if (usual) R.string.usual_title else R.string.reminder_title))
            .setContentText(body)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(context).notify(tag, channel.hashCode(), n) } catch (_: SecurityException) { }
    }

    internal const val BODY = EXTRA_BODY
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // A reminder that Doze delivered on a later day is stale: its streak already ended.
        val day = intent.getStringExtra(Reminders.DAY)?.let { parseCivilDate(it) }
        if (day != null && day != civilDate(Instant.now(), ZoneId.systemDefault())) return
        val usual = intent.getStringExtra(Reminders.KIND) == Reminders.USUAL
        val body = intent.getStringExtra(Reminders.BODY) ?: context.getString(if (usual) R.string.usual_body_discreet else R.string.reminder_short_session)
        Reminders.post(context, body, usual, if (usual) intent.getStringExtra(Reminders.TAG) else null)
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
