package com.camillanapoles.droidauditor.data.db

import android.content.ContentValues
import com.camillanapoles.droidauditor.domain.Finding

/** findings rows written by scripts (via FindingsParser) and by collectors/optimizer. */
class FindingsDao(private val dbHelper: DbHelper) {

    fun insert(
        runId: Long,
        source: String,
        severity: String,
        message: String,
        evidencePath: String?,
        relatedPackage: String?
    ): Long {
        val cv = ContentValues()
        cv.put("run_id", runId)
        cv.put("source", source)
        cv.put("severity", severity)
        cv.put("message", message)
        cv.put("evidence_path", evidencePath)
        cv.put("related_package", relatedPackage)
        return dbHelper.writableDatabase.insert("findings", null, cv)
    }

    fun forRun(runId: Long, limit: Int = 2000): List<Finding> =
        query(
            "SELECT id, run_id, source, severity, message, evidence_path, related_package FROM findings " +
                "WHERE run_id = ? ORDER BY CASE severity WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 " +
                "WHEN 'MEDIUM' THEN 2 ELSE 3 END, id LIMIT $limit",
            runId.toString()
        )

    fun forRunAndSourcePrefix(runId: Long, sourcePrefix: String, limit: Int = 500): List<Finding> =
        query(
            "SELECT id, run_id, source, severity, message, evidence_path, related_package FROM findings " +
                "WHERE run_id = ? AND source LIKE ? " +
                "ORDER BY CASE severity WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 " +
                "WHEN 'MEDIUM' THEN 2 ELSE 3 END, id LIMIT $limit",
            runId.toString(), "$sourcePrefix%"
        )

    fun byRelatedPackage(runId: Long, pkg: String, limit: Int = 200): List<Finding> =
        query(
            "SELECT id, run_id, source, severity, message, evidence_path, related_package FROM findings " +
                "WHERE run_id = ? AND related_package = ? " +
                "ORDER BY CASE severity WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 " +
                "WHEN 'MEDIUM' THEN 2 ELSE 3 END, id LIMIT $limit",
            runId.toString(), pkg
        )

    fun countForRun(runId: Long): Int {
        dbHelper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM findings WHERE run_id = ?", arrayOf(runId.toString())
        ).use { c ->
            if (c.moveToFirst()) return c.getInt(0)
        }
        return 0
    }

    fun countsBySeverity(runId: Long): Map<String, Int> {
        val map = HashMap<String, Int>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT severity, COUNT(*) FROM findings WHERE run_id = ? GROUP BY severity",
            arrayOf(runId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                map[c.getString(0) ?: continue] = c.getInt(1)
            }
        }
        return map
    }

    private fun query(sql: String, vararg args: String): List<Finding> {
        val out = ArrayList<Finding>()
        dbHelper.readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Finding(
                        id = c.getLong(0),
                        runId = c.getLong(1),
                        source = c.getString(2) ?: "",
                        severity = c.getString(3) ?: "INFO",
                        message = c.getString(4) ?: "",
                        evidencePath = c.getString(5),
                        relatedPackage = c.getString(6)
                    )
                )
            }
        }
        return out
    }
}
