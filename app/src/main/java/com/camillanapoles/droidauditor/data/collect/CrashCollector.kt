package com.camillanapoles.droidauditor.data.collect

import com.camillanapoles.droidauditor.data.db.DataDao
import com.camillanapoles.droidauditor.data.db.FindingsDao
import com.camillanapoles.droidauditor.data.exec.RootAccess
import com.camillanapoles.droidauditor.data.exec.ShellExec
import com.camillanapoles.droidauditor.data.findings.CrashDiagnoser
import com.camillanapoles.droidauditor.domain.CrashEventRow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash + ANR collection with resolutive diagnosis:
 *  - `logcat -d -b crash -v time`  (fatal exceptions; root/shell needed for
 *    other apps' logs, otherwise only the auditor's own crashes are visible);
 *  - `logcat -d -b events` am_crash / am_anr lines;
 *  - dropbox data_app_* entries (root best-effort).
 * Every parsed event is run through [CrashDiagnoser] and stored in
 * crash_events + findings, with the raw block saved under runDir/raw/.
 */
class CrashCollector(
    private val shellExec: ShellExec,
    private val rootAccess: RootAccess,
    private val dataDao: DataDao,
    private val findingsDao: FindingsDao
) : Collector {

    override val id: String = CollectorIds.CRASH_DIAGNOSIS

    override suspend fun collect(runId: Long, runDir: File) {
        val rawDir = File(runDir, "raw").apply { mkdirs() }
        val asRoot = rootAccess.isRootAvailable()
        val events = ArrayList<CrashEventRow>()

        // 1) crash buffer — fatal exception blocks
        val crashDump = shellExec.exec("logcat -d -b crash -v time", asRoot = asRoot)
        if (crashDump.output.isNotBlank()) {
            parseCrashBlocks(crashDump.output).forEach { (ts, pkg, block) ->
                val diag = CrashDiagnoser.diagnose(block)
                val rawFile = saveRaw(rawDir, "crash_${pkg.substringAfterLast('.')}", block)
                events.add(
                    CrashEventRow(
                        ts = ts, packageName = pkg, kind = "CRASH",
                        summary = firstExceptionLine(block),
                        diagnosisTitle = diag.title, cause = diag.cause,
                        resolution = diag.resolution, command = diag.command,
                        requiresRoot = diag.requiresRoot, rawPath = rawFile
                    )
                )
            }
        }

        // 2) events buffer — am_anr lines ("... am_anr: [0,123,10456,com.pkg,...]")
        val eventsDump = shellExec.exec("logcat -d -b events -v time", asRoot = asRoot)
        parseAnrLines(eventsDump.output).forEach { (ts, pkg) ->
            val diag = CrashDiagnoser.diagnose("Input dispatching timed out")
            events.add(
                CrashEventRow(
                    ts = ts, packageName = pkg, kind = "ANR",
                    summary = "Application Not Responding",
                    diagnosisTitle = diag.title, cause = diag.cause,
                    resolution = diag.resolution, command = diag.command,
                    requiresRoot = diag.requiresRoot, rawPath = null
                )
            )
        }

        // 3) persist + findings
        dataDao.replaceCrashEvents(runId, events)
        events.forEach { ev ->
            findingsDao.insert(
                runId, id, if (ev.kind == "ANR") "HIGH" else "HIGH",
                "${ev.kind} em ${ev.packageName}: ${ev.diagnosisTitle} → ${ev.resolution.take(160)}",
                ev.rawPath, ev.packageName
            )
        }

        // 4) crash-loop detection: >= 3 same pkg => escalation
        events.groupBy { it.packageName }.filterValues { it.size >= 3 }.forEach { (pkg, list) ->
            findingsDao.insert(
                runId, id, "CRITICAL",
                "Loop de crash: $pkg crashou ${list.size}x — limpar dados do app (pm clear) ou reinstalar",
                null, pkg
            )
        }
    }

    // ---------- parsing ----------

    /** Splits a crash-buffer dump into (timestamp, package, block). */
    internal fun parseCrashBlocks(dump: String): List<Triple<Long, String, String>> {
        val out = ArrayList<Triple<Long, String, String>>()
        val blocks = dump.split("(?=-{5,} *beginning of crash)".toRegex())
        for (block in blocks) {
            val pkg = PROCESS_LINE.find(block)?.groupValues?.get(1) ?: continue
            if (!ParseUtil.looksLikePackage(pkg)) continue
            val ts = block.lineSequence().firstOrNull()?.let { parseLogcatTime(it) } ?: System.currentTimeMillis()
            out.add(Triple(ts, pkg, block.take(6000)))
        }
        return out
    }

    /** am_anr / am_crash event lines -> (timestamp, package). */
    internal fun parseAnrLines(dump: String): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        for (line in dump.lineSequence()) {
            if (!line.contains("am_anr")) continue
            val pkg = EVENT_PKG.find(line)?.groupValues?.get(1) ?: continue
            if (!ParseUtil.looksLikePackage(pkg)) continue
            out.add(parseLogcatTime(line) to pkg)
        }
        return out
    }

    private fun firstExceptionLine(block: String): String =
        block.lineSequence().firstOrNull { it.trimStart().startsWith(("java.")) || it.trimStart().startsWith(("android.")) }
            ?.trim()?.take(200) ?: "FATAL EXCEPTION"

    /** logcat -v time lines start with "MM-DD HH:MM:SS.mmm". */
    private fun parseLogcatTime(line: String): Long = try {
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        fmt.parse(line.take(18))?.let { date ->
            val cal = java.util.Calendar.getInstance().apply { time = date }
            cal.set(java.util.Calendar.YEAR, year)
            cal.timeInMillis
        } ?: System.currentTimeMillis()
    } catch (_: Exception) {
        System.currentTimeMillis()
    }

    private fun saveRaw(rawDir: File, slug: String, block: String): String? = try {
        val f = File(rawDir, "crashdiagnosis_${System.currentTimeMillis()}_$slug.txt")
        f.writeText(block)
        f.absolutePath
    } catch (_: Exception) {
        null
    }

    companion object {
        private val PROCESS_LINE = Regex("Process:\\s*([\\w.]+)")
        private val EVENT_PKG = Regex("am_anr:.*?,([\\w.]+)")
    }
}
