package com.camillanapoles.droidauditor.data.db

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.camillanapoles.droidauditor.domain.ActivityRow
import com.camillanapoles.droidauditor.domain.IdleCandidate
import com.camillanapoles.droidauditor.domain.LaunchEvent
import com.camillanapoles.droidauditor.domain.PackageRow
import com.camillanapoles.droidauditor.domain.PermissionRow
import com.camillanapoles.droidauditor.domain.PkgCount
import com.camillanapoles.droidauditor.domain.ProcessRow
import com.camillanapoles.droidauditor.domain.ServiceRow
import com.camillanapoles.droidauditor.domain.StorageRow
import com.camillanapoles.droidauditor.domain.TimelineRow

/**
 * All collected audit data tables: packages, permissions, processes, services,
 * activities, launch/usage/timeline events and storage entries.
 */
class DataDao(private val dbHelper: DbHelper) {

    /** Runs [block] inside one transaction on the shared writable database. */
    fun <T> inTx(block: (SQLiteDatabase) -> T): T {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            val result = block(db)
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    // ---------- packages / permissions ----------

    fun packagesFor(runId: Long): List<PackageRow> {
        val out = ArrayList<PackageRow>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT package_name, uid, version_name, version_code, first_install_time, " +
                "last_update_time, installer_pkg, apk_path, is_system, is_enabled, target_sdk, permission_count " +
                "FROM packages WHERE run_id = ? ORDER BY package_name",
            arrayOf(runId.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(mapPackage(c))
        }
        return out
    }

    fun searchPackages(runId: Long, query: String, includeSystem: Boolean, limit: Int = 500): List<PackageRow> {
        val pattern = "%$query%"
        val out = ArrayList<PackageRow>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT package_name, uid, version_name, version_code, first_install_time, " +
                "last_update_time, installer_pkg, apk_path, is_system, is_enabled, target_sdk, permission_count " +
                "FROM packages WHERE run_id = ? AND package_name LIKE ? AND (? = 1 OR is_system = 0) " +
                "ORDER BY package_name LIMIT $limit",
            arrayOf(runId.toString(), pattern, if (includeSystem) "1" else "0")
        ).use { c ->
            while (c.moveToNext()) out.add(mapPackage(c))
        }
        return out
    }

    fun packageNamesFor(runId: Long): List<String> {
        val out = ArrayList<String>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT package_name FROM packages WHERE run_id = ?", arrayOf(runId.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0) ?: continue)
        }
        return out
    }


    fun packageByName(runId: Long, pkg: String): PackageRow? {
        dbHelper.readableDatabase.rawQuery(
            "SELECT package_name, uid, version_name, version_code, first_install_time, " +
                "last_update_time, installer_pkg, apk_path, is_system, is_enabled, target_sdk, permission_count " +
                "FROM packages WHERE run_id = ? AND package_name = ?",
            arrayOf(runId.toString(), pkg)
        ).use { c ->
            if (c.moveToFirst()) return mapPackage(c)
        }
        return null
    }
    fun uidToPackageMap(runId: Long): Map<Int, String> {
        val map = HashMap<Int, String>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT uid, package_name FROM packages WHERE run_id = ? ORDER BY package_name",
            arrayOf(runId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val uid = c.getInt(0)
                val pkg = c.getString(1) ?: continue
                if (!map.containsKey(uid)) map[uid] = pkg
            }
        }
        return map
    }

    fun permissionsFor(runId: Long, pkg: String): List<PermissionRow> {
        val out = ArrayList<PermissionRow>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT permission_name, granted, protection_level FROM permissions " +
                "WHERE run_id = ? AND package_name = ? ORDER BY permission_name",
            arrayOf(runId.toString(), pkg)
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    PermissionRow(
                        permissionName = c.getString(0) ?: "",
                        granted = c.getInt(1) != 0,
                        protectionLevel = c.getString(2) ?: "unknown"
                    )
                )
            }
        }
        return out
    }

    fun permissionRowCount(runId: Long): Int =
        simpleCount("SELECT COUNT(*) FROM permissions WHERE run_id = ?", runId.toString())

    fun permissionNamesWithUnknownProtection(runId: Long): List<String> {
        val out = ArrayList<String>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT DISTINCT permission_name FROM permissions WHERE run_id = ? " +
                "AND (protection_level = 'unknown' OR protection_level IS NULL OR protection_level = '')",
            arrayOf(runId.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0) ?: continue)
        }
        return out
    }

    fun dangerousPermissionNames(runId: Long, limit: Int = 1000): List<String> {
        val out = ArrayList<String>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT DISTINCT permission_name FROM permissions WHERE run_id = ? " +
                "AND (protection_level LIKE 'dangerous%' OR protection_level LIKE 'signature%') LIMIT $limit",
            arrayOf(runId.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0) ?: continue)
        }
        return out
    }

    fun grantedDangerousPerms(runId: Long, limit: Int): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT package_name, permission_name FROM permissions WHERE run_id = ? " +
                "AND granted = 1 AND protection_level LIKE 'dangerous%' LIMIT $limit",
            arrayOf(runId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val pkg = c.getString(0) ?: continue
                val perm = c.getString(1) ?: continue
                out.add(pkg to perm)
            }
        }
        return out
    }

    fun grantedDangerousCounts(runId: Long, limit: Int): List<PkgCount> =
        pkgCounts(
            "SELECT p.package_name, COUNT(*) AS c FROM permissions pe " +
                "JOIN packages p ON p.run_id = pe.run_id AND p.package_name = pe.package_name " +
                "WHERE pe.run_id = ? AND pe.granted = 1 AND pe.protection_level LIKE 'dangerous%' " +
                "AND p.is_system = 0 GROUP BY p.package_name ORDER BY c DESC, p.package_name LIMIT $limit",
            runId
        )

    fun bootCompletedGranted(runId: Long, limit: Int): List<PkgCount> =
        pkgCounts(
            "SELECT p.package_name, COUNT(*) AS c FROM permissions pe " +
                "JOIN packages p ON p.run_id = pe.run_id AND p.package_name = pe.package_name " +
                "WHERE pe.run_id = ? AND pe.permission_name = 'android.permission.BOOT_COMPLETED' " +
                "AND pe.granted = 1 AND p.is_system = 0 GROUP BY p.package_name ORDER BY p.package_name LIMIT $limit",
            runId
        )

    private fun pkgCounts(sql: String, runId: Long): List<PkgCount> {
        val out = ArrayList<PkgCount>()
        dbHelper.readableDatabase.rawQuery(sql, arrayOf(runId.toString())).use { c ->
            while (c.moveToNext()) out.add(PkgCount(c.getString(0) ?: continue, c.getInt(1)))
        }
        return out
    }

    // ---------- processes ----------

    fun processesFor(runId: Long, limit: Int = 2000): List<ProcessRow> {
        val out = ArrayList<ProcessRow>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT pid, ppid, uid, name, rss_kb, state, oom_adj, package_name, is_cached, has_services, has_foreground " +
                "FROM processes WHERE run_id = ? ORDER BY name LIMIT $limit",
            arrayOf(runId.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(mapProcess(c))
        }
        return out
    }

    fun processesForPackage(runId: Long, pkg: String): List<ProcessRow> {
        val out = ArrayList<ProcessRow>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT pid, ppid, uid, name, rss_kb, state, oom_adj, package_name, is_cached, has_services, has_foreground " +
                "FROM processes WHERE run_id = ? AND package_name = ? ORDER BY pid",
            arrayOf(runId.toString(), pkg)
        ).use { c ->
            while (c.moveToNext()) out.add(mapProcess(c))
        }
        return out
    }

    /** Fills has_services / has_foreground once services + activities are collected. */
    fun updateProcessFlags(runId: Long) {
        val db = dbHelper.writableDatabase
        db.execSQL(
            "UPDATE processes SET has_services = " +
                "CASE WHEN EXISTS(SELECT 1 FROM services s WHERE s.run_id = processes.run_id " +
                "AND (s.package_name = processes.package_name OR s.process_name = processes.name)) " +
                "THEN 1 ELSE 0 END WHERE run_id = ?",
            arrayOf(runId.toString())
        )
        db.execSQL(
            "UPDATE processes SET has_foreground = " +
                "CASE WHEN EXISTS(SELECT 1 FROM activities a WHERE a.run_id = processes.run_id " +
                "AND a.is_resumed = 1 AND a.package_name = processes.package_name) " +
                "THEN 1 ELSE 0 END WHERE run_id = ?",
            arrayOf(runId.toString())
        )
    }

    // ---------- services / activities ----------

    fun servicesFor(runId: Long, limit: Int = 2000): List<ServiceRow> {
        val out = ArrayList<ServiceRow>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT package_name, service_class, process_name, client_packages, started_by " +
                "FROM services WHERE run_id = ? ORDER BY package_name, service_class LIMIT $limit",
            arrayOf(runId.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(mapService(c))
        }
        return out
    }

    fun servicesForPackage(runId: Long, pkg: String): List<ServiceRow> {
        val out = ArrayList<ServiceRow>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT package_name, service_class, process_name, client_packages, started_by " +
                "FROM services WHERE run_id = ? AND package_name = ? ORDER BY service_class",
            arrayOf(runId.toString(), pkg)
        ).use { c ->
            while (c.moveToNext()) out.add(mapService(c))
        }
        return out
    }

    fun activitiesFor(runId: Long, limit: Int = 2000): List<ActivityRow> {
        val out = ArrayList<ActivityRow>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT package_name, activity_class, is_resumed FROM activities " +
                "WHERE run_id = ? ORDER BY is_resumed DESC, package_name LIMIT $limit",
            arrayOf(runId.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(mapActivity(c))
        }
        return out
    }

    fun activitiesForPackage(runId: Long, pkg: String): List<ActivityRow> {
        val out = ArrayList<ActivityRow>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT package_name, activity_class, is_resumed FROM activities " +
                "WHERE run_id = ? AND package_name = ? ORDER BY activity_class",
            arrayOf(runId.toString(), pkg)
        ).use { c ->
            while (c.moveToNext()) out.add(mapActivity(c))
        }
        return out
    }

    // ---------- launch / usage / timeline / storage ----------

    fun insertLaunchEvent(
        runId: Long, ts: Long, fromPackage: String?, toPackage: String?, activity: String?, evidence: String?
    ): Long {
        val cv = ContentValues()
        cv.put("run_id", runId)
        cv.put("ts", ts)
        cv.put("from_package", fromPackage)
        cv.put("to_package", toPackage)
        cv.put("activity", activity)
        cv.put("evidence", evidence)
        return dbHelper.writableDatabase.insert("launch_events", null, cv)
    }

    fun launchEventsFor(runId: Long, limit: Int): List<LaunchEvent> {
        val out = ArrayList<LaunchEvent>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT ts, from_package, to_package, activity, evidence FROM launch_events " +
                "WHERE run_id = ? ORDER BY ts DESC LIMIT $limit",
            arrayOf(runId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    LaunchEvent(
                        ts = c.getLong(0),
                        fromPackage = c.getString(1),
                        toPackage = c.getString(2),
                        activity = c.getString(3),
                        evidence = c.getString(4)
                    )
                )
            }
        }
        return out
    }

    fun timelineFor(runId: Long, limit: Int = 5000): List<TimelineRow> {
        val out = ArrayList<TimelineRow>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT package_name, first_install_time, last_update_time, installer_pkg " +
                "FROM install_timeline WHERE run_id = ? ORDER BY first_install_time, package_name LIMIT $limit",
            arrayOf(runId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    TimelineRow(
                        packageName = c.getString(0) ?: continue,
                        firstInstallTime = c.getLong(1),
                        lastUpdateTime = c.getLong(2),
                        installerPkg = c.getString(3)
                    )
                )
            }
        }
        return out
    }

    fun usageMaxTsByPackage(runId: Long): Map<String, Long> {
        val map = HashMap<String, Long>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT package_name, MAX(ts) FROM usage_events WHERE run_id = ? GROUP BY package_name",
            arrayOf(runId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val pkg = c.getString(0) ?: continue
                map[pkg] = c.getLong(1)
            }
        }
        return map
    }

    fun usageHasRows(runId: Long): Boolean =
        dbHelper.readableDatabase.rawQuery(
            "SELECT EXISTS(SELECT 1 FROM usage_events WHERE run_id = ?)", arrayOf(runId.toString())
        ).use { c -> c.moveToFirst() && c.getInt(0) != 0 }

    fun lastUsageForPackage(runId: Long, pkg: String): Long? {
        dbHelper.readableDatabase.rawQuery(
            "SELECT MAX(ts) FROM usage_events WHERE run_id = ? AND package_name = ?",
            arrayOf(runId.toString(), pkg)
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return null
    }

    fun storageFor(runId: Long): List<StorageRow> {
        val out = ArrayList<StorageRow>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT path, size_bytes, package_name, category FROM storage_entries " +
                "WHERE run_id = ? ORDER BY size_bytes DESC",
            arrayOf(runId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    StorageRow(
                        path = c.getString(0) ?: continue,
                        sizeBytes = c.getLong(1),
                        packageName = c.getString(2),
                        category = c.getString(3) ?: ""
                    )
                )
            }
        }
        return out
    }

    fun termuxTotalBytes(runId: Long): Long {
        dbHelper.readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(size_bytes), 0) FROM storage_entries WHERE run_id = ? AND category = 'termux'",
            arrayOf(runId.toString())
        ).use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return 0L
    }

    /** Replaces the crash_events of one run in a single transaction. */
    fun replaceCrashEvents(runId: Long, rows: List<com.camillanapoles.droidauditor.domain.CrashEventRow>) {
        inTx { db ->
            db.delete("crash_events", "run_id = ?", arrayOf(runId.toString()))
            for (row in rows) {
                val cv = ContentValues()
                cv.put("run_id", runId)
                cv.put("ts", row.ts)
                cv.put("package_name", row.packageName)
                cv.put("kind", row.kind)
                cv.put("summary", row.summary)
                cv.put("diagnosis_title", row.diagnosisTitle)
                cv.put("cause", row.cause)
                cv.put("resolution", row.resolution)
                cv.put("command", row.command)
                cv.put("requires_root", if (row.requiresRoot) 1 else 0)
                cv.put("raw_path", row.rawPath)
                db.insert("crash_events", null, cv)
            }
        }
    }

    /** Latest-first crash events of a run. */
    fun crashEventsFor(runId: Long, limit: Int = 200): List<com.camillanapoles.droidauditor.domain.CrashEventRow> {
        val out = ArrayList<com.camillanapoles.droidauditor.domain.CrashEventRow>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT ts, package_name, kind, summary, diagnosis_title, cause, resolution, command, requires_root, raw_path " +
                "FROM crash_events WHERE run_id = ? ORDER BY ts DESC LIMIT $limit",
            arrayOf(runId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    com.camillanapoles.droidauditor.domain.CrashEventRow(
                        ts = c.getLong(0),
                        packageName = c.getString(1) ?: continue,
                        kind = c.getString(2) ?: "CRASH",
                        summary = c.getString(3) ?: "",
                        diagnosisTitle = c.getString(4) ?: "",
                        cause = c.getString(5) ?: "",
                        resolution = c.getString(6) ?: "",
                        command = c.getString(7),
                        requiresRoot = c.getInt(8) != 0,
                        rawPath = c.getString(9)
                    )
                )
            }
        }
        return out
    }

    /** Non-system packages with a live process, no resumed activity and no services. */
    fun runningIdleCandidates(runId: Long): List<IdleCandidate> {
        val out = ArrayList<IdleCandidate>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT DISTINCT p.package_name, p.last_update_time FROM processes pr " +
                "JOIN packages p ON p.run_id = pr.run_id AND p.package_name = pr.package_name " +
                "WHERE pr.run_id = ? AND pr.package_name IS NOT NULL AND pr.has_services = 0 " +
                "AND pr.has_foreground = 0 AND p.is_system = 0 ORDER BY p.package_name",
            arrayOf(runId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(IdleCandidate(c.getString(0) ?: continue, c.getLong(1)))
            }
        }
        return out
    }

    fun countPackages(runId: Long): Int =
        simpleCount("SELECT COUNT(*) FROM packages WHERE run_id = ?", runId.toString())

    private fun simpleCount(sql: String, vararg args: String): Int {
        dbHelper.readableDatabase.rawQuery(sql, args).use { c ->
            if (c.moveToFirst()) return c.getInt(0)
        }
        return 0
    }

    private fun mapPackage(c: Cursor): PackageRow = PackageRow(
        packageName = c.getString(0) ?: "",
        uid = c.getInt(1),
        versionName = c.getString(2) ?: "",
        versionCode = c.getLong(3),
        firstInstallTime = c.getLong(4),
        lastUpdateTime = c.getLong(5),
        installerPkg = c.getString(6),
        apkPath = c.getString(7) ?: "",
        isSystem = c.getInt(8) != 0,
        isEnabled = c.getInt(9) != 0,
        targetSdk = c.getInt(10),
        permissionCount = c.getInt(11)
    )

    private fun mapProcess(c: Cursor): ProcessRow = ProcessRow(
        pid = c.getInt(0),
        ppid = c.getInt(1),
        uid = c.getInt(2),
        name = c.getString(3) ?: "",
        rssKb = c.getLong(4),
        state = c.getString(5) ?: "",
        oomAdj = if (c.isNull(6)) null else c.getInt(6),
        packageName = c.getString(7),
        isCached = c.getInt(8) != 0,
        hasServices = c.getInt(9) != 0,
        hasForeground = c.getInt(10) != 0
    )

    private fun mapService(c: Cursor): ServiceRow = ServiceRow(
        packageName = c.getString(0) ?: "",
        serviceClass = c.getString(1) ?: "",
        processName = c.getString(2),
        clientPackages = c.getString(3),
        startedBy = c.getString(4)
    )

    private fun mapActivity(c: Cursor): ActivityRow = ActivityRow(
        packageName = c.getString(0) ?: "",
        activityClass = c.getString(1) ?: "",
        isResumed = c.getInt(2) != 0
    )
}
