package com.camillanapoles.droidauditor.ui.vm

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.camillanapoles.droidauditor.data.collect.UsageCollector
import com.camillanapoles.droidauditor.di.AppContainer
import com.camillanapoles.droidauditor.domain.ActivityRow
import com.camillanapoles.droidauditor.domain.AuditUiState
import com.camillanapoles.droidauditor.domain.CategoryInfo
import com.camillanapoles.droidauditor.domain.CommandRow
import com.camillanapoles.droidauditor.domain.EdgeDisplay
import com.camillanapoles.droidauditor.domain.Finding
import com.camillanapoles.droidauditor.domain.LaunchEvent
import com.camillanapoles.droidauditor.domain.OutputRow
import com.camillanapoles.droidauditor.domain.PackageRow
import com.camillanapoles.droidauditor.domain.PermissionRow
import com.camillanapoles.droidauditor.domain.ProcessRow
import com.camillanapoles.droidauditor.domain.RunRow
import com.camillanapoles.droidauditor.domain.ServiceRow
import com.camillanapoles.droidauditor.domain.TimelineRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** One factory for every screen ViewModel. */
class VmFactory(private val container: AppContainer) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        DashboardViewModel::class.java -> DashboardViewModel(container)
        AuditViewModel::class.java -> AuditViewModel(container)
        ExplorerViewModel::class.java -> ExplorerViewModel(container)
        TimelineViewModel::class.java -> TimelineViewModel(container)
        OptimizeViewModel::class.java -> OptimizeViewModel(container)
        CommandsViewModel::class.java -> CommandsViewModel(container)
        else -> throw IllegalArgumentException("Unknown ViewModel ${modelClass.name}")
    } as T
}

class DashboardViewModel(private val c: AppContainer) : ViewModel() {

    data class UiState(
        val rootGranted: Boolean = false,
        val usageGranted: Boolean = false,
        val dataTotalBytes: Long = 0L,
        val dataAvailBytes: Long = 0L,
        val ramTotalBytes: Long = 0L,
        val ramAvailBytes: Long = 0L,
        val lastRun: RunRow? = null,
        val findingsCount: Int = 0
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    val engineState: StateFlow<AuditUiState> = c.engine.state

    val deviceModel: String = android.os.Build.MODEL
    val androidVersion: String = android.os.Build.VERSION.RELEASE
    val fingerprint: String = android.os.Build.FINGERPRINT

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val stat = try {
                StatFs(Environment.getDataDirectory().absolutePath)
            } catch (_: Exception) {
                null
            }
            val am = c.context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mem = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mem)
            val last = c.runDao.lastRun()
            _ui.value = UiState(
                rootGranted = c.rootAccess.isRootAvailable(),
                usageGranted = UsageCollector.hasAccess(c.context),
                dataTotalBytes = stat?.totalBytes ?: 0L,
                dataAvailBytes = stat?.availableBytes ?: 0L,
                ramTotalBytes = mem.totalMem,
                ramAvailBytes = mem.availMem,
                lastRun = last,
                findingsCount = last?.let { c.findingsDao.countForRun(it.id) } ?: 0
            )
        }
    }

    fun recheckRoot() {
        viewModelScope.launch(Dispatchers.IO) {
            c.rootAccess.isRootAvailable(forceCheck = true)
            refresh()
        }
    }

    fun openUsageSettings() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            c.context.startActivity(intent)
        } catch (_: Exception) {
            // device without a usage-access settings panel
        }
    }

    fun startAudit() = c.engine.startFullAudit()

    fun cancelAudit() = c.engine.requestCancel()
}

class AuditViewModel(private val c: AppContainer) : ViewModel() {

    val engineState: StateFlow<AuditUiState> = c.engine.state

    private val _outputs = MutableStateFlow<List<OutputRow>>(emptyList())
    val outputs: StateFlow<List<OutputRow>> = _outputs

    private val _findings = MutableStateFlow<List<Finding>>(emptyList())
    val findings: StateFlow<List<Finding>> = _findings

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val run = c.runDao.lastRun() ?: return@launch
            _outputs.value = c.runDao.outputsForRun(run.id)
            _findings.value = c.findingsDao.forRun(run.id)
        }
    }
}

class ExplorerViewModel(private val c: AppContainer) : ViewModel() {

    data class PackageDetail(
        val row: PackageRow?,
        val permissions: List<PermissionRow>,
        val processes: List<ProcessRow>,
        val services: List<ServiceRow>,
        val activities: List<ActivityRow>,
        val edges: List<EdgeDisplay>,
        val usedBy: List<com.camillanapoles.droidauditor.domain.UsedByNode>,
        val lastUsage: Long?
    )

    private val _packages = MutableStateFlow<List<PackageRow>>(emptyList())
    val packages: StateFlow<List<PackageRow>> = _packages

    private val _processes = MutableStateFlow<List<ProcessRow>>(emptyList())
    val processes: StateFlow<List<ProcessRow>> = _processes

    private val _services = MutableStateFlow<List<ServiceRow>>(emptyList())
    val services: StateFlow<List<ServiceRow>> = _services

    private val _findings = MutableStateFlow<List<Finding>>(emptyList())
    val findings: StateFlow<List<Finding>> = _findings

    private val _runId = MutableStateFlow(-1L)
    val runId: StateFlow<Long> = _runId

    private val _detail = MutableStateFlow<PackageDetail?>(null)
    val detail: StateFlow<PackageDetail?> = _detail

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            val run = c.runDao.lastRun()
            val rid = run?.id ?: -1L
            _runId.value = rid
            if (rid > 0) {
                _packages.value = c.dataDao.searchPackages(rid, "", false)
                _processes.value = c.dataDao.processesFor(rid, 1000)
                _services.value = c.dataDao.servicesFor(rid, 1000)
                _findings.value = c.findingsDao.forRun(rid, 500)
            } else {
                _packages.value = emptyList()
                _processes.value = emptyList()
                _services.value = emptyList()
                _findings.value = emptyList()
            }
        }
    }

    fun searchPackages(query: String, includeSystem: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val rid = _runId.value
            _packages.value = if (rid > 0) {
                c.dataDao.searchPackages(rid, query, includeSystem)
            } else {
                emptyList()
            }
        }
    }

    fun loadDetail(runId: Long, pkg: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (runId <= 0) {
                _detail.value = null
                return@launch
            }
            _detail.value = PackageDetail(
                row = c.dataDao.packageByName(runId, pkg),
                permissions = c.dataDao.permissionsFor(runId, pkg),
                processes = c.dataDao.processesForPackage(runId, pkg),
                services = c.dataDao.servicesForPackage(runId, pkg),
                activities = c.dataDao.activitiesForPackage(runId, pkg),
                edges = c.graphDao.edgesForEntity(runId, pkg),
                usedBy = c.graphDao.usedByTree(runId, pkg),
                lastUsage = c.dataDao.lastUsageForPackage(runId, pkg)
            )
        }
    }
}

class TimelineViewModel(private val c: AppContainer) : ViewModel() {

    private val _timeline = MutableStateFlow<List<TimelineRow>>(emptyList())
    val timeline: StateFlow<List<TimelineRow>> = _timeline

    private val _launches = MutableStateFlow<List<LaunchEvent>>(emptyList())
    val launches: StateFlow<List<LaunchEvent>> = _launches

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val run = c.runDao.lastRun() ?: return@launch
            _timeline.value = c.dataDao.timelineFor(run.id)
            _launches.value = c.dataDao.launchEventsFor(run.id, 300)
        }
    }
}

class OptimizeViewModel(private val c: AppContainer) : ViewModel() {

    private val _cacheFindings = MutableStateFlow<List<Finding>>(emptyList())
    val cacheFindings: StateFlow<List<Finding>> = _cacheFindings

    private val _idleFindings = MutableStateFlow<List<Finding>>(emptyList())
    val idleFindings: StateFlow<List<Finding>> = _idleFindings

    private val _otherFindings = MutableStateFlow<List<Finding>>(emptyList())
    val otherFindings: StateFlow<List<Finding>> = _otherFindings

    private val _termuxBytes = MutableStateFlow(0L)
    val termuxBytes: StateFlow<Long> = _termuxBytes

    private val _actions = MutableStateFlow<Map<String, CommandRow>>(emptyMap())
    val actions: StateFlow<Map<String, CommandRow>> = _actions

    private val _result = MutableStateFlow<String?>(null)
    val result: StateFlow<String?> = _result

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val run = c.runDao.lastRun() ?: return@launch
            val all = c.findingsDao.forRunAndSourcePrefix(run.id, "optimizer:")
            _cacheFindings.value = all.filter { it.source == com.camillanapoles.droidauditor.domain.Sources.OPTIMIZER_CACHE }
            _idleFindings.value = all.filter { it.source == com.camillanapoles.droidauditor.domain.Sources.OPTIMIZER_IDLE }
            _otherFindings.value = all.filter {
                it.source != com.camillanapoles.droidauditor.domain.Sources.OPTIMIZER_CACHE &&
                    it.source != com.camillanapoles.droidauditor.domain.Sources.OPTIMIZER_IDLE
            }
            _termuxBytes.value = c.dataDao.termuxTotalBytes(run.id)
            _actions.value = listOf("termux_cache_clean", "trim_caches", "force_stop")
                .mapNotNull { id -> c.commandDao.findByCommand(id)?.let { id to it } }
                .toMap()
        }
    }

    fun executeAction(row: CommandRow, pkg: String? = null) {
        run("${row.name} (${row.command})", row.command, row.requiresRoot, pkg)
    }

    fun executeRaw(label: String, command: String, pkg: String? = null) {
        run(label, command, true, pkg)
    }

    private fun run(label: String, command: String, requiresRoot: Boolean, pkg: String?) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val res = c.actionExecutor.runRaw(command, requiresRoot, pkg)
            _result.value = if (res.ok) {
                "$label: OK"
            } else {
                "$label: exit ${res.exitCode}\n${res.output.take(500)}"
            }
            _busy.value = false
            val run = c.runDao.lastRun()
            if (run != null) _termuxBytes.value = c.dataDao.termuxTotalBytes(run.id)
        }
    }

    fun clearResult() {
        _result.value = null
    }
}

class CommandsViewModel(private val c: AppContainer) : ViewModel() {

    private val _commands = MutableStateFlow<List<CommandRow>>(emptyList())
    val commands: StateFlow<List<CommandRow>> = _commands

    private val _adhoc = MutableStateFlow<Pair<String, Int>?>(null)
    val adhoc: StateFlow<Pair<String, Int>?> = _adhoc

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /** Category registry from the bundled seed assets. */
    val categories: List<CategoryInfo>
        get() = c.categories

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _commands.value = c.commandDao.listAll()
        }
    }

    fun setEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            c.commandDao.setEnabled(id, enabled)
            refresh()
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            c.commandDao.delete(id)
            refresh()
        }
    }

    fun save(row: CommandRow, isNew: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (isNew) {
                c.commandDao.insert(row)
            } else {
                c.commandDao.update(row)
            }
            refresh()
        }
    }

    /** Runs a shell command immediately; output lands under runs/adhoc/. */
    fun runNow(row: CommandRow) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val base = c.context.getExternalFilesDir(null) ?: c.context.filesDir
            val dir = File(base, "runs/adhoc")
            if (!dir.exists()) dir.mkdirs()
            val outFile = File(dir, "${System.currentTimeMillis()}_${slug(row.name)}.txt")
            val resolved = row.command
                .replace("<outdir>", dir.absolutePath)
                .replace("<pkg>", "")
            val result = c.shellExec.execToFile(
                resolved, outFile,
                asRoot = row.requiresRoot && c.rootAccess.isRootAvailable()
            )
            _adhoc.value = outFile.absolutePath to result.exitCode
            _busy.value = false
        }
    }

    fun clearAdhoc() {
        _adhoc.value = null
    }

    private fun slug(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9_-]+"), "_").take(40)
}
