package com.camillanapoles.droidauditor.data.correlate

import com.camillanapoles.droidauditor.data.db.DataDao
import com.camillanapoles.droidauditor.data.db.DbHelper
import com.camillanapoles.droidauditor.data.db.GraphDao

/**
 * Builds the unified entities + edges graph for a run:
 * package / process / service / activity / permission(dangerous+signature) / uid nodes,
 * linked by runs_as, spawned, installs, launched, binds_to, uses_permission edges.
 */
class Correlator(
    private val dbHelper: DbHelper,
    private val dataDao: DataDao,
    private val graphDao: GraphDao
) {

    private data class ComponentKey(val pkg: String, val cls: String)

    fun correlate(runId: Long) {
        val packages = dataDao.packagesFor(runId)
        val processes = dataDao.processesFor(runId)
        val services = dataDao.servicesFor(runId)
        val activities = dataDao.activitiesFor(runId)
        val dangerousPerms = dataDao.dangerousPermissionNames(runId, MAX_PERM_NODES)
        val launches = dataDao.launchEventsFor(runId, MAX_LAUNCH_EDGES)
        val grantedDangerous = dataDao.grantedDangerousPerms(runId, MAX_PERM_EDGES)

        val nameToPid = HashMap<String, Int>(processes.size)
        for (proc in processes) {
            if (!nameToPid.containsKey(proc.name)) nameToPid[proc.name] = proc.pid
        }

        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            // ---- entities ----
            val pkgIds = HashMap<String, Long>(packages.size)
            for (pkg in packages) {
                pkgIds[pkg.packageName] =
                    graphDao.insertEntity(db, runId, "package", pkg.packageName, pkg.packageName)
            }
            val uidIds = HashMap<Int, Long>()
            for (pkg in packages) {
                if (pkg.uid > 0 && !uidIds.containsKey(pkg.uid)) {
                    uidIds[pkg.uid] = graphDao.insertEntity(db, runId, "uid", pkg.uid.toString(), "uid ${pkg.uid}")
                }
            }
            val procIds = HashMap<Int, Long>(processes.size)
            for (proc in processes) {
                procIds[proc.pid] =
                    graphDao.insertEntity(db, runId, "process", "pid:${proc.pid}", "${proc.name} (${proc.pid})")
            }
            val svcIds = HashMap<ComponentKey, Long>(services.size)
            for (svc in services) {
                val key = ComponentKey(svc.packageName, svc.serviceClass)
                if (!svcIds.containsKey(key)) {
                    svcIds[key] = graphDao.insertEntity(
                        db, runId, "service",
                        "${svc.packageName}/${svc.serviceClass}",
                        svc.serviceClass.substringAfterLast('.')
                    )
                }
            }
            val actIds = HashMap<ComponentKey, Long>(activities.size)
            for (act in activities) {
                val key = ComponentKey(act.packageName, act.activityClass)
                if (!actIds.containsKey(key)) {
                    actIds[key] = graphDao.insertEntity(
                        db, runId, "activity",
                        "${act.packageName}/${act.activityClass}",
                        act.activityClass.substringAfterLast('.')
                    )
                }
            }
            val permIds = HashMap<String, Long>(dangerousPerms.size)
            for (perm in dangerousPerms) {
                permIds[perm] = graphDao.insertEntity(db, runId, "permission", perm, perm.substringAfterLast('.'))
            }

            // ---- edges ----
            var edgeCount = 0
            fun edge(src: Long?, dst: Long?, relation: String, evidence: String) {
                if (src == null || dst == null || src == dst) return
                if (edgeCount >= MAX_EDGES) return
                graphDao.insertEdge(db, runId, src, dst, relation, evidence.take(300))
                edgeCount++
            }

            // process -> package (runs_as, from the uid map)
            for (proc in processes) {
                val target = proc.packageName?.let { pkgIds[it] }
                edge(procIds[proc.pid], target, "runs_as", "uid=${proc.uid}")
            }
            // process -> process (spawned via ppid)
            for (proc in processes) {
                if (proc.ppid > 0) {
                    edge(procIds[proc.pid], procIds[proc.ppid], "spawned", "ppid=${proc.ppid}")
                }
            }
            // service -> process (runs_as, matched by processName or main package process)
            for (svc in services) {
                val svcId = svcIds[ComponentKey(svc.packageName, svc.serviceClass)] ?: continue
                val pid = svc.processName?.let { nameToPid[it] }
                    ?: nameToPid[svc.packageName]
                edge(svcId, pid?.let { procIds[it] }, "runs_as", "processName=${svc.processName ?: svc.packageName}")
            }
            // activity -> package (runs_as)
            for (act in activities) {
                edge(
                    actIds[ComponentKey(act.packageName, act.activityClass)],
                    pkgIds[act.packageName],
                    "runs_as",
                    "activity"
                )
            }
            // package -> installer (installs)
            for (pkg in packages) {
                val installer = pkg.installerPkg
                if (!installer.isNullOrBlank() && pkgIds.containsKey(installer)) {
                    edge(pkgIds[pkg.packageName], pkgIds[installer], "installs", "installer=$installer")
                }
            }
            // launch events (launched)
            for (launch in launches) {
                val from = launch.fromPackage?.let { pkgIds[it] }
                val to = launch.toPackage?.let { pkgIds[it] }
                edge(from, to, "launched", launch.evidence ?: launch.activity ?: "")
            }
            // service clients (binds_to)
            for (svc in services) {
                val svcId = svcIds[ComponentKey(svc.packageName, svc.serviceClass)] ?: continue
                val clients = svc.clientPackages
                if (clients.isNullOrBlank()) continue
                for (client in clients.split(',')) {
                    val trimmed = client.trim()
                    if (trimmed.isEmpty() || trimmed == svc.packageName) continue
                    edge(pkgIds[trimmed], svcId, "binds_to", "client")
                }
            }
            // package -> permission (uses_permission, dangerous + granted only)
            for ((pkg, perm) in grantedDangerous) {
                edge(pkgIds[pkg], permIds[perm], "uses_permission", "granted")
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        const val MAX_PERM_NODES = 1000
        const val MAX_PERM_EDGES = 5000
        const val MAX_LAUNCH_EDGES = 500
        const val MAX_EDGES = 20000
    }
}
