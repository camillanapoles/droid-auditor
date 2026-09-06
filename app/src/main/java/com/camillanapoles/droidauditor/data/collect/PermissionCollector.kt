package com.camillanapoles.droidauditor.data.collect

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.camillanapoles.droidauditor.data.db.DataDao
import com.camillanapoles.droidauditor.data.db.FindingsDao
import com.camillanapoles.droidauditor.domain.Sources
import java.io.File

/**
 * Enriches permission rows whose protection level could not be resolved during the
 * packages pass, and emits one summary finding with the top dangerous-permission holder.
 */
class PermissionCollector(
    private val context: Context,
    private val dataDao: DataDao,
    private val findingsDao: FindingsDao
) : Collector {

    override val id: String = CollectorIds.PERMISSIONS

    override suspend fun collect(runId: Long, runDir: File) {
        val unresolved = dataDao.permissionNamesWithUnknownProtection(runId)
        if (unresolved.isNotEmpty()) {
            val pm = context.packageManager
            val resolved = HashMap<String, String>(unresolved.size)
            for (name in unresolved) {
                resolved[name] = ParseUtil.protectionLevelOf(pm, name)
            }
            dataDao.inTx { db ->
                for ((name, level) in resolved) {
                    db.update(
                        "permissions",
                        android.content.ContentValues().apply { put("protection_level", level) },
                        "run_id = ? AND permission_name = ?",
                        arrayOf(runId.toString(), name)
                    )
                }
            }
        }

        val total = dataDao.permissionRowCount(runId)
        val top = dataDao.grantedDangerousCounts(runId, 1).firstOrNull()
        val message = buildString {
            append("Permissions: ")
            append(total)
            append(" grants scanned")
            if (top != null) {
                append("; top holder ")
                append(top.packageName)
                append(" (")
                append(top.count)
                append(" granted dangerous)")
            }
        }
        findingsDao.insert(runId, Sources.collector(id), "INFO", message, null, top?.packageName)
    }
}
