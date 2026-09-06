package com.camillanapoles.droidauditor.data.findings

import com.camillanapoles.droidauditor.data.collect.ParseUtil
import com.camillanapoles.droidauditor.data.db.DataDao
import com.camillanapoles.droidauditor.data.db.FindingsDao
import com.camillanapoles.droidauditor.data.db.RunDao
import com.camillanapoles.droidauditor.domain.Sources
import java.io.File

/**
 * Scans every raw script/shell output of a run for [CRITICAL]/[HIGH]/[MEDIUM]/[INFO]
 * tagged lines and turns them into findings rows. Links a related package when a
 * known package name appears on the line; updates findings_count per output.
 */
class FindingsParser(
    private val runDao: RunDao,
    private val findingsDao: FindingsDao,
    private val dataDao: DataDao
) {

    fun parse(runId: Long) {
        val knownPackages = HashSet<String>(dataDao.packageNamesFor(runId))
        for (output in runDao.outputsForRun(runId)) {
            val path = output.stdoutPath ?: continue
            val file = File(path)
            if (!file.exists() || !file.isFile) continue

            var count = 0
            file.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val severity = ParseUtil.severityOf(line) ?: continue
                    val message = line.trim().take(2000)
                    val related = ParseUtil.firstPackageIn(message, knownPackages)
                    findingsDao.insert(runId, Sources.script(output.commandName), severity, message, path, related)
                    count++
                    if (count >= MAX_FINDINGS_PER_OUTPUT) break
                }
            }
            runDao.updateFindingsCount(output.id, count)
        }
    }

    companion object {
        const val MAX_FINDINGS_PER_OUTPUT = 500
    }
}
