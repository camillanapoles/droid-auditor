package com.camillanapoles.droidauditor.data.collect

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.camillanapoles.droidauditor.data.db.DataDao
import com.camillanapoles.droidauditor.data.db.FindingsDao
import com.camillanapoles.droidauditor.data.exec.RootAccess
import com.camillanapoles.droidauditor.data.exec.ShellExec
import com.camillanapoles.droidauditor.domain.Sources
import java.io.File

/**
 * Per-app cache sizes via root `du -sk` on /data/data/&lt;pkg&gt;/cache, batched
 * into a single shell loop to avoid spawning one su per package.
 * Skipped with an INFO note when root is not available.
 */
class StorageCachesCollector(
    private val rootAccess: RootAccess,
    private val shellExec: ShellExec,
    private val dataDao: DataDao,
    private val findingsDao: FindingsDao
) : Collector {

    override val id: String = CollectorIds.STORAGE

    override suspend fun collect(runId: Long, runDir: File) {
        if (!rootAccess.isRootAvailable()) {
            findingsDao.insert(
                runId, Sources.collector(id), "INFO",
                "Storage caches collector skipped: root not available", null, null
            )
            return
        }
        val packages = dataDao.packagesFor(runId)
            .filter { it.uid >= 10000 }
            .map { it.packageName }
            .take(MAX_PACKAGES)
        if (packages.isEmpty()) return

        val script = buildString {
            append("for p in ")
            for (pkg in packages) {
                append(ShellExec.quote(pkg))
                append(' ')
            }
            append("; do if [ -d /data/data/\$p/cache ]; then du -sk /data/data/\$p/cache 2>/dev/null; fi; done")
        }
        val result = shellExec.exec(script, asRoot = true)

        val rows = ArrayList<ContentValues>()
        for ((path, bytes) in parseDu(result.output)) {
            val pkg = Regex("/data/data/([^/]+)/").find(path)?.groupValues?.get(1)
            val cv = ContentValues()
            cv.put("run_id", runId)
            cv.put("path", path)
            cv.put("size_bytes", bytes)
            cv.put("package_name", pkg)
            cv.put("category", "app_cache")
            rows.add(cv)
        }
        dataDao.inTx { db ->
            for (cv in rows) {
                db.insertWithOnConflict("storage_entries", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    companion object {
        const val MAX_PACKAGES = 400
    }
}

/**
 * Fixed Termux cache paths measured with root du in a single invocation:
 * apt archives/cache, home .cache, .gradle/caches, .npm/_cacache, apt lists.
 */
class TermuxStorageCollector(
    private val rootAccess: RootAccess,
    private val shellExec: ShellExec,
    private val dataDao: DataDao
) : Collector {

    override val id: String = CollectorIds.TERMUX

    private val paths = listOf(
        "/data/data/com.termux/files/usr/var/cache/apt/archives",
        "/data/data/com.termux/files/usr/var/cache",
        "/data/data/com.termux/files/home/.cache",
        "/data/data/com.termux/files/home/.gradle/caches",
        "/data/data/com.termux/files/home/.npm/_cacache",
        "/data/data/com.termux/files/usr/var/lib/apt/lists"
    )

    override suspend fun collect(runId: Long, runDir: File) {
        if (!rootAccess.isRootAvailable()) return // already noted by storage_caches skip
        val script = buildString {
            append("for d in ")
            for (path in paths) {
                append(ShellExec.quote(path))
                append(' ')
            }
            append("; do if [ -e \"\$d\" ]; then du -sk \"\$d\" 2>/dev/null; fi; done")
        }
        val result = shellExec.exec(script, asRoot = true)

        val rows = ArrayList<ContentValues>()
        for ((path, bytes) in parseDu(result.output)) {
            val cv = ContentValues()
            cv.put("run_id", runId)
            cv.put("path", path)
            cv.put("size_bytes", bytes)
            cv.put("package_name", "com.termux")
            cv.put("category", "termux")
            rows.add(cv)
        }
        dataDao.inTx { db ->
            for (cv in rows) {
                db.insertWithOnConflict("storage_entries", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }
}

/** Parses `du -sk` output lines: "<kb> <path>". */
internal fun parseDu(output: String): List<Pair<String, Long>> {
    val out = ArrayList<Pair<String, Long>>()
    val lineRe = Regex("^\\s*(\\d+)\\s+(\\S+)")
    for (line in output.lineSequence()) {
        val match = lineRe.find(line) ?: continue
        val kb = match.groupValues[1].toLongOrNull() ?: continue
        out.add(match.groupValues[2] to kb * 1024L)
    }
    return out
}
