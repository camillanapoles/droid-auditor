package com.camillanapoles.droidauditor.data.findings

import com.camillanapoles.droidauditor.data.db.DataDao
import com.camillanapoles.droidauditor.data.db.FindingsDao
import com.camillanapoles.droidauditor.data.db.SettingsDao
import com.camillanapoles.droidauditor.domain.Sources

/**
 * Turns collected data into optimization findings and suggestions:
 * (a) app caches over min_cache_mb -> HIGH, clear-cache action
 * (b) running-but-idle packages -> MEDIUM, force-stop action
 * (c) Termux caches total -> dedicated finding referencing termux_cache_clean
 * (d) non-system BOOT_COMPLETED receivers -> INFO top offenders
 * (e) non-system apps with most granted dangerous permissions -> INFO
 */
class OptimizerRules(
    private val settingsDao: SettingsDao,
    private val dataDao: DataDao,
    private val findingsDao: FindingsDao
) {

    fun apply(runId: Long) {
        val minCacheBytes = settingsDao.getLong("min_cache_mb", 100L) * 1024L * 1024L
        val idleDays = settingsDao.getLong("idle_days_threshold", 30L)
        val now = System.currentTimeMillis()

        // (a) large app caches
        for (entry in dataDao.storageFor(runId)) {
            val pkg = entry.packageName
            if (entry.category == "app_cache" && entry.sizeBytes >= minCacheBytes && !pkg.isNullOrBlank()) {
                findingsDao.insert(
                    runId, Sources.OPTIMIZER_CACHE, "HIGH",
                    "App cache ${entry.sizeBytes / 1024L / 1024L} MB - clear to reclaim space ($pkg)",
                    null, pkg
                )
            }
        }

        // (c) Termux caches total, referencing the action id
        val termuxBytes = dataDao.termuxTotalBytes(runId)
        if (termuxBytes > 0L) {
            findingsDao.insert(
                runId, Sources.OPTIMIZER_TERMUX, "HIGH",
                "Termux caches total ${termuxBytes / 1024L / 1024L} MB - run action termux_cache_clean to reclaim",
                null, "com.termux"
            )
        }

        // (b) running but idle
        val usageByPkg = dataDao.usageMaxTsByPackage(runId)
        val usageAvailable = usageByPkg.isNotEmpty() || dataDao.usageHasRows(runId)
        for (candidate in dataDao.runningIdleCandidates(runId)) {
            val lastUse = usageByPkg[candidate.packageName]
            val lowConfidence = lastUse == null && !usageAvailable
            val reference = lastUse ?: candidate.lastUpdateTime
            val days = (now - reference) / DAY_MS
            if (days >= idleDays) {
                val confidence = if (lowConfidence) " (low confidence: usage stats unavailable)" else ""
                findingsDao.insert(
                    runId, Sources.OPTIMIZER_IDLE, "MEDIUM",
                    "Running sem execução (running but idle): process alive, no resumed activity, " +
                        "no services, last use ${days}d ago$confidence - force stop ${candidate.packageName}",
                    null, candidate.packageName
                )
            }
        }

        // (d) boot receivers (top offenders)
        val bootReceivers = dataDao.bootCompletedGranted(runId, 5)
        if (bootReceivers.isNotEmpty()) {
            val names = bootReceivers.joinToString(", ") { it.packageName }
            findingsDao.insert(
                runId, Sources.OPTIMIZER_BOOT, "INFO",
                "Apps auto-starting at boot (BOOT_COMPLETED granted, top offenders): $names",
                null, bootReceivers.first().packageName
            )
        }

        // (e) most granted dangerous permissions
        for (row in dataDao.grantedDangerousCounts(runId, 10)) {
            findingsDao.insert(
                runId, Sources.OPTIMIZER_PERMS, "INFO",
                "App holds ${row.count} granted dangerous permissions: ${row.packageName}",
                null, row.packageName
            )
        }
    }

    companion object {
        const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
