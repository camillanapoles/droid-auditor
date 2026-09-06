package com.camillanapoles.droidauditor.data.collect

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ContentValues
import android.content.Context
import android.os.Process
import com.camillanapoles.droidauditor.data.db.DataDao
import com.camillanapoles.droidauditor.data.db.FindingsDao
import com.camillanapoles.droidauditor.data.db.SettingsDao
import com.camillanapoles.droidauditor.domain.Sources
import java.io.File

/**
 * UsageStatsManager.queryEvents over the configured lookback window when
 * PACKAGE_USAGE_STATS is granted via AppOps; otherwise records an INFO note so
 * the optimizer falls back to install/update-time heuristics.
 */
class UsageCollector(
    private val context: Context,
    private val dataDao: DataDao,
    private val settingsDao: SettingsDao,
    private val findingsDao: FindingsDao
) : Collector {

    override val id: String = CollectorIds.USAGE

    override suspend fun collect(runId: Long, runDir: File) {
        if (!hasAccess(context)) {
            findingsDao.insert(
                runId, Sources.collector(id), "INFO",
                "Usage stats access not granted - idle detection falls back to install-time heuristic",
                null, null
            )
            return
        }
        val lookbackDays = settingsDao.getLong("usage_lookback_days", 30L)
        val now = System.currentTimeMillis()
        val begin = now - lookbackDays * 24L * 60L * 60L * 1000L

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(begin, now)
        val event = UsageEvents.Event()
        val rows = ArrayList<ContentValues>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (rows.size >= MAX_EVENTS) break
            val type = when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> "MOVE_TO_FOREGROUND"
                UsageEvents.Event.MOVE_TO_BACKGROUND -> "MOVE_TO_BACKGROUND"
                UsageEvents.Event.ACTIVITY_RESUMED -> "ACTIVITY_RESUMED"
                UsageEvents.Event.ACTIVITY_PAUSED -> "ACTIVITY_PAUSED"
                UsageEvents.Event.ACTIVITY_STOPPED -> "ACTIVITY_STOPPED"
                else -> continue
            }
            val cv = ContentValues()
            cv.put("run_id", runId)
            cv.put("ts", event.timeStamp)
            cv.put("package_name", event.packageName ?: continue)
            cv.put("event_type", type)
            cv.put("extra", "")
            rows.add(cv)
        }
        dataDao.inTx { db ->
            for (cv in rows) {
                db.insert("usage_events", null, cv)
            }
        }
    }

    companion object {
        const val MAX_EVENTS = 5000

        @Suppress("DEPRECATION")
        fun hasAccess(context: Context): Boolean {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
