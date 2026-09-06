package com.camillanapoles.droidauditor.data.collect

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.camillanapoles.droidauditor.data.db.DataDao
import com.camillanapoles.droidauditor.data.db.FindingsDao
import com.camillanapoles.droidauditor.data.exec.ShellExec
import com.camillanapoles.droidauditor.domain.Sources
import java.io.File

/**
 * Foreground app: ResumedActivity lines from `dumpsys activity activities` plus
 * mCurrentFocus/mFocusedApp from `dumpsys window`. Marks activities(is_resumed=1)
 * and emits an INFO finding "Foreground: X".
 */
class FocusCollector(
    private val shellExec: ShellExec,
    private val dataDao: DataDao,
    private val findingsDao: FindingsDao
) : Collector {

    override val id: String = CollectorIds.FOCUS

    override suspend fun collect(runId: Long, runDir: File) {
        val activities = shellExec.exec("dumpsys activity activities", asRoot = false).output
        val window = shellExec.exec("dumpsys window", asRoot = false).output

        val found = LinkedHashMap<String, String>() // package -> activity class
        for (line in activities.lineSequence()) {
            if (!line.contains("ResumedActivity")) continue
            extractComponent(line)?.let { (pkg, cls) -> if (!found.containsKey(pkg)) found[pkg] = cls }
        }
        for (line in window.lineSequence()) {
            if (!line.contains("mCurrentFocus") && !line.contains("mFocusedApp")) continue
            extractComponent(line)?.let { (pkg, cls) -> if (!found.containsKey(pkg)) found[pkg] = cls }
        }

        if (found.isEmpty()) {
            findingsDao.insert(
                runId, Sources.collector(id), "INFO",
                "Foreground: no resumed activity detected", null, null
            )
            return
        }

        dataDao.inTx { db ->
            for ((pkg, cls) in found) {
                val cv = ContentValues()
                cv.put("run_id", runId)
                cv.put("package_name", pkg)
                cv.put("activity_class", cls)
                cv.put("is_resumed", 1)
                db.insertWithOnConflict("activities", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }

        val top = found.entries.first()
        findingsDao.insert(
            runId, Sources.collector(id), "INFO",
            "Foreground: ${top.key}/${top.value}", null, top.key
        )
    }

    /** Extracts a `package/class` component from a dumpsys line, null when absent. */
    private fun extractComponent(line: String): Pair<String, String>? {
        val match = Regex("([a-zA-Z][\\w.]*(?:\\.[\\w]+)+)/([\\w.$]+)").find(line) ?: return null
        val pkg = match.groupValues[1]
        val cls = ParseUtil.expandComponent(pkg, match.groupValues[2])
        return pkg to cls
    }
}
