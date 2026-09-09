package com.camillanapoles.droidauditor.engine

import android.content.Context
import android.os.Build
import com.camillanapoles.droidauditor.data.collect.Collector
import com.camillanapoles.droidauditor.data.collect.FocusCollector
import com.camillanapoles.droidauditor.data.collect.LaunchGraphCollector
import com.camillanapoles.droidauditor.data.collect.PackageCollector
import com.camillanapoles.droidauditor.data.collect.CrashCollector
import com.camillanapoles.droidauditor.data.collect.PermissionCollector
import com.camillanapoles.droidauditor.data.collect.ProcessCollector
import com.camillanapoles.droidauditor.data.collect.ServiceCollector
import com.camillanapoles.droidauditor.data.collect.StorageCachesCollector
import com.camillanapoles.droidauditor.data.collect.TermuxStorageCollector
import com.camillanapoles.droidauditor.data.collect.UsageCollector
import com.camillanapoles.droidauditor.data.correlate.Correlator
import com.camillanapoles.droidauditor.data.exec.ShellExec
import com.camillanapoles.droidauditor.data.findings.FindingsParser
import com.camillanapoles.droidauditor.data.findings.OptimizerRules
import com.camillanapoles.droidauditor.di.AppContainer
import com.camillanapoles.droidauditor.domain.AuditUiState
import com.camillanapoles.droidauditor.domain.CmdProgress
import com.camillanapoles.droidauditor.domain.Kinds
import com.camillanapoles.droidauditor.domain.Phase
import com.camillanapoles.droidauditor.domain.ProgressStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Orchestrates a full audit: scripts + shell commands via ShellExec (raw output
 * under runs/run_&lt;ts&gt;/raw/), then Kotlin collectors, process flag enrichment,
 * FindingsParser, Correlator and OptimizerRules. Progress flows to the UI.
 */
class AuditEngine(private val container: AppContainer) {

    private val context: Context = container.context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<AuditUiState>(AuditUiState.Idle)
    val state: StateFlow<AuditUiState> = _state

    @Volatile
    private var cancelRequested = false

    private var currentPhase: Phase = Phase.COLLECT

    private val collectors: Map<String, Collector> = listOf(
        PackageCollector(context, container.dataDao),
        PermissionCollector(context, container.dataDao, container.findingsDao),
        ProcessCollector(container.shellExec, container.dataDao),
        ServiceCollector(container.shellExec, container.dataDao),
        FocusCollector(container.shellExec, container.dataDao, container.findingsDao),
        LaunchGraphCollector(container.shellExec, container.dataDao, container.settingsDao),
        UsageCollector(context, container.dataDao, container.settingsDao, container.findingsDao),
        StorageCachesCollector(container.rootAccess, container.shellExec, container.dataDao, container.findingsDao),
        TermuxStorageCollector(container.rootAccess, container.shellExec, container.dataDao),
        CrashCollector(container.shellExec, container.rootAccess, container.dataDao, container.findingsDao)
    ).associateBy { it.id }

    val isRunning: Boolean
        get() = _state.value is AuditUiState.Running

    fun startFullAudit() {
        if (isRunning) return
        scope.launch { runFullAudit() }
    }

    fun requestCancel() {
        cancelRequested = true
    }

    private suspend fun runFullAudit() {
        cancelRequested = false
        val rootGranted = container.rootAccess.isRootAvailable()

        val runId = container.runDao.insertRun(
            rootGranted, Build.MODEL, Build.VERSION.RELEASE, Build.FINGERPRINT
        )
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val runDir = File(baseDir, "runs/run_${System.currentTimeMillis()}")
        val rawDir = File(runDir, "raw")
        rawDir.mkdirs()
        val toolchainDir = container.scriptInstaller.ensureInstalled()

        val active = container.commandDao.listEnabled().filter { it.kind != Kinds.ACTION }
        val items = ArrayList(active.map { CmdProgress(it.id, it.name, it.kind, ProgressStatus.PENDING) })
        currentPhase = Phase.COLLECT
        publish(runId, items)

        var ok = 0
        var fail = 0

        for ((index, command) in active.withIndex()) {
            if (cancelRequested) break
            updateItem(runId, items, index, ProgressStatus.RUNNING, 0L, "")
            val startedAt = System.currentTimeMillis()
            try {
                if (command.requiresRoot && !rootGranted) {
                    updateItem(runId, items, index, ProgressStatus.SKIP, 0L, "root required")
                    continue
                }
                when (command.kind) {
                    Kinds.SCRIPT -> {
                        val scriptFile = File(toolchainDir, command.command)
                        if (!scriptFile.exists()) {
                            fail++
                            updateItem(runId, items, index, ProgressStatus.FAIL, 0L, "script missing")
                            continue
                        }
                        val cmd = "sh ${ShellExec.quote(scriptFile.absolutePath)} ${ShellExec.quote(runDir.absolutePath)}"
                        val exit = execAndStore(cmd, command, runId, rawDir, startedAt, asRoot = rootGranted)
                        if (exit == 0) ok++ else fail++
                        updateItem(runId, items, index, statusOf(exit), elapsed(startedAt), "exit $exit")
                    }
                    Kinds.SHELL -> {
                        val resolved = command.command.replace("<outdir>", runDir.absolutePath)
                        val exit = execAndStore(
                            resolved, command, runId, rawDir, startedAt,
                            asRoot = command.requiresRoot && rootGranted
                        )
                        if (exit == 0) ok++ else fail++
                        updateItem(runId, items, index, statusOf(exit), elapsed(startedAt), "exit $exit")
                    }
                    Kinds.COLLECTOR -> {
                        val collector = collectors[command.command]
                        if (collector == null) {
                            fail++
                            updateItem(runId, items, index, ProgressStatus.FAIL, 0L, "unknown collector")
                        } else {
                            collector.collect(runId, runDir)
                            ok++
                            updateItem(runId, items, index, ProgressStatus.OK, elapsed(startedAt), "")
                        }
                    }
                    else -> {
                        updateItem(runId, items, index, ProgressStatus.SKIP, 0L, "not part of audit loop")
                    }
                }
            } catch (t: Throwable) {
                fail++
                updateItem(runId, items, index, ProgressStatus.FAIL, elapsed(startedAt), t.message ?: "error")
            }
        }

        // has_services / has_foreground need services + activities to be collected first
        container.dataDao.updateProcessFlags(runId)

        currentPhase = Phase.PARSE
        publish(runId, items)
        FindingsParser(container.runDao, container.findingsDao, container.dataDao).parse(runId)

        currentPhase = Phase.CORRELATE
        publish(runId, items)
        Correlator(container.dbHelper, container.dataDao, container.graphDao).correlate(runId)

        currentPhase = Phase.OPTIMIZE
        publish(runId, items)
        OptimizerRules(container.settingsDao, container.dataDao, container.findingsDao).apply(runId)

        val findingsCount = container.findingsDao.countForRun(runId)
        val cancelled = cancelRequested
        container.runDao.finishRun(runId, ok, fail)
        _state.value = AuditUiState.Finished(runId, ok, fail, findingsCount, cancelled)
    }

    private fun execAndStore(
        cmdString: String,
        command: com.camillanapoles.droidauditor.domain.CommandRow,
        runId: Long,
        rawDir: File,
        startedAt: Long,
        asRoot: Boolean
    ): Int {
        val outFile = File(rawDir, "${command.id}_${slugify(command.name)}.txt")
        val result = container.shellExec.execToFile(cmdString, outFile, asRoot = asRoot)
        container.runDao.insertOutput(
            runId, command.id, result.exitCode, outFile.absolutePath,
            outFile.length(), result.durationMs, startedAt
        )
        return result.exitCode
    }

    private fun statusOf(exitCode: Int): String =
        if (exitCode == 0) ProgressStatus.OK else ProgressStatus.FAIL

    private fun elapsed(startedAt: Long): Long = System.currentTimeMillis() - startedAt

    private fun updateItem(
        runId: Long,
        items: MutableList<CmdProgress>,
        index: Int,
        status: String,
        durationMs: Long,
        detail: String
    ) {
        if (index >= items.size) return
        items[index] = items[index].copy(status = status, durationMs = durationMs, detail = detail)
        publish(runId, items)
    }

    private fun publish(runId: Long, items: List<CmdProgress>) {
        _state.value = AuditUiState.Running(runId, items.toList(), currentPhase)
    }

    internal fun slugify(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9_-]+"), "_").take(40)
}
