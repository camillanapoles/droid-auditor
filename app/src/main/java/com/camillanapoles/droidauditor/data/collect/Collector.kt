package com.camillanapoles.droidauditor.data.collect

import android.content.pm.PackageManager
import java.io.File
import java.util.regex.Pattern

/** Collector ids, mirroring the kind=collector entries in seed commands.json. */
object CollectorIds {
    const val PACKAGES = "packages"
    const val PERMISSIONS = "permissions"
    const val PROCESSES = "processes"
    const val SERVICES = "services"
    const val FOCUS = "focus"
    const val LAUNCH_GRAPH = "launch_graph"
    const val USAGE = "usage_stats"
    const val STORAGE = "storage_caches"
    const val TERMUX = "termux_storage"
    const val OVERLAY_STATE = "overlay_state"
}

/** A built-in Kotlin collector invoked by the audit engine. */
interface Collector {
    val id: String
    suspend fun collect(runId: Long, runDir: File)
}

/** Best-effort parsing helpers shared by collectors. Never throws on format drift. */
object ParseUtil {

    val PACKAGE_PATTERN: Pattern = Pattern.compile("[a-zA-Z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+){2,}")

    /** First package-looking token in [text] that is part of [known]; null otherwise. */
    fun firstPackageIn(text: String, known: Set<String>): String? {
        val matcher = PACKAGE_PATTERN.matcher(text)
        while (matcher.find()) {
            val candidate = matcher.group() ?: continue
            if (known.contains(candidate)) return candidate
        }
        return null
    }

    fun looksLikePackage(token: String): Boolean =
        token.contains('.') && PACKAGE_PATTERN.matcher(token).matches()

    /** Expands short activity/service class names (".Main", "Main") to fully qualified ones. */
    fun expandComponent(pkg: String, cls: String): String = when {
        cls.startsWith(".") -> pkg + cls
        cls.contains('.') -> cls
        else -> "$pkg.$cls"
    }

    /** Severity tag used by the forensic toolchain scripts. */
    fun severityOf(line: String): String? = when {
        line.contains("[CRITICAL]") -> "CRITICAL"
        line.contains("[HIGH]") -> "HIGH"
        line.contains("[MEDIUM]") -> "MEDIUM"
        line.contains("[INFO]") -> "INFO"
        else -> null
    }

    /** Human-readable protection level for a permission; "unknown" when unresolvable. */
    fun protectionLevelOf(pm: PackageManager, permissionName: String): String {
        return try {
            val level = pm.getPermissionInfo(permissionName, 0).protectionLevel
            val base = level and 0xff
            val name = when (base) {
                0 -> "normal"
                1 -> "dangerous"
                else -> "signature"
            }
            if (level and 0x10 != 0) "$name|privileged" else name
        } catch (_: Exception) {
            "unknown"
        }
    }
}
