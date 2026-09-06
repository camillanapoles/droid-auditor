package com.camillanapoles.droidauditor.data.collect

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.content.ContentValues
import android.os.Build
import com.camillanapoles.droidauditor.data.db.DataDao
import java.io.File

/**
 * PackageManager.getInstalledPackages(GET_PERMISSIONS) -> packages, permissions,
 * install_timeline. UID comes from ApplicationInfo, installer from the installer package name.
 */
class PackageCollector(
    private val context: Context,
    private val dataDao: DataDao
) : Collector {

    override val id: String = CollectorIds.PACKAGES

    override suspend fun collect(runId: Long, runDir: File) {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val installed = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)

        val protectionCache = HashMap<String, String>()
        val packageRows = ArrayList<ContentValues>(installed.size)
        val timelineRows = ArrayList<ContentValues>(installed.size)
        val permissionRows = ArrayList<ContentValues>()

        for (info in installed) {
            val ai = info.applicationInfo ?: continue
            val pkgName = info.packageName
            val installer = installerOf(pm, pkgName)
            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0

            val pkgValues = ContentValues()
            pkgValues.put("run_id", runId)
            pkgValues.put("package_name", pkgName)
            pkgValues.put("uid", ai.uid)
            pkgValues.put("version_name", info.versionName ?: "")
            pkgValues.put("version_code", versionCodeOf(info))
            pkgValues.put("first_install_time", info.firstInstallTime)
            pkgValues.put("last_update_time", info.lastUpdateTime)
            pkgValues.put("installer_pkg", installer)
            pkgValues.put("apk_path", ai.sourceDir ?: "")
            pkgValues.put("is_system", if (isSystem) 1 else 0)
            pkgValues.put("is_enabled", if (ai.enabled) 1 else 0)
            pkgValues.put("target_sdk", ai.targetSdkVersion)
            pkgValues.put("permission_count", info.requestedPermissions?.size ?: 0)
            packageRows.add(pkgValues)

            val timelineValues = ContentValues()
            timelineValues.put("run_id", runId)
            timelineValues.put("package_name", pkgName)
            timelineValues.put("first_install_time", info.firstInstallTime)
            timelineValues.put("last_update_time", info.lastUpdateTime)
            timelineValues.put("installer_pkg", installer)
            timelineRows.add(timelineValues)

            val requested = info.requestedPermissions
            val flags = info.requestedPermissionsFlags
            if (requested != null) {
                for (i in requested.indices) {
                    val perm = requested[i]
                    if (perm.isNullOrEmpty()) continue
                    val granted = flags != null &&
                        (flags.getOrNull(i) ?: 0) and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
                    val protection = protectionCache.getOrPut(perm) {
                        ParseUtil.protectionLevelOf(pm, perm)
                    }
                    val pv = ContentValues()
                    pv.put("run_id", runId)
                    pv.put("package_name", pkgName)
                    pv.put("permission_name", perm)
                    pv.put("granted", if (granted) 1 else 0)
                    pv.put("protection_level", protection)
                    permissionRows.add(pv)
                }
            }
        }

        dataDao.inTx { db ->
            for (cv in packageRows) {
                db.insertWithOnConflict("packages", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
            }
            for (cv in timelineRows) {
                db.insertWithOnConflict("install_timeline", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
            }
            for (cv in permissionRows) {
                db.insertWithOnConflict("permissions", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    private fun installerOf(pm: PackageManager, pkg: String): String? = try {
        if (Build.VERSION.SDK_INT >= 30) {
            pm.getInstallSourceInfo(pkg).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(pkg)
        }
    } catch (_: Exception) {
        null
    }
}
