package com.camillanapoles.droidauditor.data.db

import android.content.ContentValues
import android.database.Cursor
import com.camillanapoles.droidauditor.domain.OutputRow
import com.camillanapoles.droidauditor.domain.RunRow

/** audit_runs + script_outputs rows. */
class RunDao(private val dbHelper: DbHelper) {

    fun insertRun(
        rootGranted: Boolean,
        deviceModel: String?,
        androidVersion: String?,
        buildFingerprint: String?
    ): Long {
        val cv = ContentValues()
        cv.put("started_at", System.currentTimeMillis())
        cv.put("root_granted", if (rootGranted) 1 else 0)
        cv.put("device_model", deviceModel)
        cv.put("android_version", androidVersion)
        cv.put("build_fingerprint", buildFingerprint)
        cv.put("scripts_ok", 0)
        cv.put("scripts_fail", 0)
        return dbHelper.writableDatabase.insert("audit_runs", null, cv)
    }

    fun finishRun(id: Long, okCount: Int, failCount: Int) {
        val cv = ContentValues()
        cv.put("finished_at", System.currentTimeMillis())
        cv.put("scripts_ok", okCount)
        cv.put("scripts_fail", failCount)
        dbHelper.writableDatabase.update("audit_runs", cv, "id = ?", arrayOf(id.toString()))
    }

    fun lastRun(): RunRow? = runs(1).firstOrNull()

    fun runs(limit: Int): List<RunRow> {
        val out = ArrayList<RunRow>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT id, started_at, finished_at, root_granted, device_model, android_version, " +
                "build_fingerprint, scripts_ok, scripts_fail FROM audit_runs ORDER BY id DESC LIMIT $limit",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    RunRow(
                        id = c.getLong(0),
                        startedAt = c.getLong(1),
                        finishedAt = if (c.isNull(2)) null else c.getLong(2),
                        rootGranted = c.getInt(3) != 0,
                        deviceModel = c.getString(4),
                        androidVersion = c.getString(5),
                        buildFingerprint = c.getString(6),
                        scriptsOk = c.getInt(7),
                        scriptsFail = c.getInt(8)
                    )
                )
            }
        }
        return out
    }

    fun insertOutput(
        runId: Long,
        commandId: Long?,
        exitCode: Int,
        stdoutPath: String?,
        stdoutLen: Long,
        durationMs: Long,
        startedAt: Long
    ): Long {
        val cv = ContentValues()
        cv.put("run_id", runId)
        cv.put("command_id", commandId)
        cv.put("exit_code", exitCode)
        cv.put("stdout_path", stdoutPath)
        cv.put("stdout_len", stdoutLen)
        cv.put("duration_ms", durationMs)
        cv.put("findings_count", 0)
        cv.put("started_at", startedAt)
        return dbHelper.writableDatabase.insert("script_outputs", null, cv)
    }

    fun outputsForRun(runId: Long): List<OutputRow> {
        val out = ArrayList<OutputRow>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT so.id, so.run_id, so.command_id, COALESCE(c.name, '?'), so.exit_code, " +
                "so.stdout_path, so.stdout_len, so.duration_ms, so.findings_count, so.started_at " +
                "FROM script_outputs so LEFT JOIN commands c ON c.id = so.command_id " +
                "WHERE so.run_id = ? ORDER BY so.id",
            arrayOf(runId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    OutputRow(
                        id = c.getLong(0),
                        runId = c.getLong(1),
                        commandId = if (c.isNull(2)) null else c.getLong(2),
                        commandName = c.getString(3) ?: "?",
                        exitCode = c.getInt(4),
                        stdoutPath = c.getString(5),
                        stdoutLen = c.getLong(6),
                        durationMs = c.getLong(7),
                        findingsCount = c.getInt(8),
                        startedAt = c.getLong(9)
                    )
                )
            }
        }
        return out
    }

    fun updateFindingsCount(outputId: Long, count: Int) {
        val cv = ContentValues()
        cv.put("findings_count", count)
        dbHelper.writableDatabase.update("script_outputs", cv, "id = ?", arrayOf(outputId.toString()))
    }
}
