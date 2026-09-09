package com.camillanapoles.droidauditor.domain

/** Category registry entry loaded from assets/seed/categories.json. */
data class CategoryInfo(val name: String, val description: String)

/** Row of the [com.camillanapoles.droidauditor.data.db.DbHelper] commands table. */
data class CommandRow(
    val id: Long,
    val name: String,
    val category: String,
    val kind: String,
    val command: String,
    val requiresRoot: Boolean,
    val parser: String,
    val enabled: Boolean,
    val danger: Boolean,
    val description: String,
    val source: String,
    val updatedAt: Long
)

data class RunRow(
    val id: Long,
    val startedAt: Long,
    val finishedAt: Long?,
    val rootGranted: Boolean,
    val deviceModel: String?,
    val androidVersion: String?,
    val buildFingerprint: String?,
    val scriptsOk: Int,
    val scriptsFail: Int
)

data class OutputRow(
    val id: Long,
    val runId: Long,
    val commandId: Long?,
    val commandName: String,
    val exitCode: Int,
    val stdoutPath: String?,
    val stdoutLen: Long,
    val durationMs: Long,
    val findingsCount: Int,
    val startedAt: Long
)

data class PackageRow(
    val packageName: String,
    val uid: Int,
    val versionName: String,
    val versionCode: Long,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val installerPkg: String?,
    val apkPath: String,
    val isSystem: Boolean,
    val isEnabled: Boolean,
    val targetSdk: Int,
    val permissionCount: Int
)

data class PermissionRow(
    val permissionName: String,
    val granted: Boolean,
    val protectionLevel: String
)

data class ProcessRow(
    val pid: Int,
    val ppid: Int,
    val uid: Int,
    val name: String,
    val rssKb: Long,
    val state: String,
    val oomAdj: Int?,
    val packageName: String?,
    val isCached: Boolean,
    val hasServices: Boolean,
    val hasForeground: Boolean
)

data class ServiceRow(
    val packageName: String,
    val serviceClass: String,
    val processName: String?,
    val clientPackages: String?,
    val startedBy: String?
)

data class ActivityRow(
    val packageName: String,
    val activityClass: String,
    val isResumed: Boolean
)

data class LaunchEvent(
    val ts: Long,
    val fromPackage: String?,
    val toPackage: String?,
    val activity: String?,
    val evidence: String?
)

data class TimelineRow(
    val packageName: String,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val installerPkg: String?
)

data class StorageRow(
    val path: String,
    val sizeBytes: Long,
    val packageName: String?,
    val category: String
)

data class Finding(
    val id: Long,
    val runId: Long,
    val source: String,
    val severity: String,
    val message: String,
    val evidencePath: String?,
    val relatedPackage: String?
)

data class RelationType(
    val name: String,
    val forwardLabel: String,
    val reverseLabel: String
)

/** Directed graph edge as shown in the Explorer, labels resolved via relation_types. */
data class EdgeDisplay(
    val direction: String,
    val relation: String,
    val label: String,
    val otherKey: String,
    val otherType: String,
    val otherLabel: String,
    val evidence: String?
)

data class PkgCount(val packageName: String, val count: Int)

data class IdleCandidate(val packageName: String, val lastUpdateTime: Long)

/** Diagnosed crash/ANR event with a resolutive action. */
data class CrashEventRow(
    val ts: Long,
    val packageName: String,
    val kind: String,            // CRASH | ANR
    val summary: String,
    val diagnosisTitle: String,
    val cause: String,
    val resolution: String,
    val command: String?,        // executable template (<pkg>), null = manual only
    val requiresRoot: Boolean,
    val rawPath: String?
)

/** Row of overlay_state: per-app overlay permission + active overlay windows. */
data class OverlayStateRow(
    val packageName: String,
    val overlayAllowed: Boolean,
    val appopsMode: String,
    val activeWindows: Int,
    val isSystem: Boolean
)

// ─────────── Topology ───────────

/** App node in the categorical topology: priority (oom), impact, install order. */
data class TopologyApp(
    val packageName: String,
    val isSystem: Boolean,
    val category: String,
    val priority: Int,          // -1 not running; 0 cached .. 5 persistent
    val impactScore: Int,       // 0..100 composite
    val rssKb: Long,
    val cacheBytes: Long,
    val dangerousPerms: Int,
    val installOrder: Int,      // 1-based across all packages (ordem de surgimento)
    val isRunning: Boolean,
    val isCachedIdle: Boolean   // running without real activity
)

data class TopologyCategory(
    val name: String,
    val apps: List<TopologyApp>,
    val totalRssKb: Long,
    val totalCacheBytes: Long,
    val maxImpact: Int
)

/** Top-level topology section: type [system/user]. */
data class TopologyTypeSection(
    val isSystem: Boolean,
    val categories: List<TopologyCategory>,
    val apps: List<TopologyApp>
)

/** Sort modes for the topology tree. */
enum class TopologySort { IMPACT, PRIORITY, ORDER, NAME }

object Kinds {
    const val SCRIPT = "script"
    const val SHELL = "shell"
    const val COLLECTOR = "collector"
    const val ACTION = "action"
}

object ProgressStatus {
    const val PENDING = "pending"
    const val RUNNING = "running"
    const val OK = "ok"
    const val FAIL = "fail"
    const val SKIP = "skipped"
}

object Sources {
    const val OPTIMIZER_CACHE = "optimizer:cache"
    const val OPTIMIZER_IDLE = "optimizer:idle"
    const val OPTIMIZER_TERMUX = "optimizer:termux"
    const val OPTIMIZER_BOOT = "optimizer:boot"
    const val OPTIMIZER_PERMS = "optimizer:perms"

    fun script(name: String) = "script:$name"
    fun collector(id: String) = "collector:$id"
}

enum class Phase { COLLECT, PARSE, CORRELATE, OPTIMIZE }

data class CmdProgress(
    val commandId: Long,
    val name: String,
    val kind: String,
    val status: String,
    val durationMs: Long = 0L,
    val detail: String = ""
)

/** Lifecycle of a full audit, observed by Dashboard and Audit screens. */
sealed interface AuditUiState {
    object Idle : AuditUiState
    data class Running(val runId: Long, val items: List<CmdProgress>, val phase: Phase) : AuditUiState
    data class Finished(
        val runId: Long,
        val okCount: Int,
        val failCount: Int,
        val findingsCount: Int,
        val cancelled: Boolean
    ) : AuditUiState
}
