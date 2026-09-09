package com.camillanapoles.droidauditor.data.topology

import com.camillanapoles.droidauditor.data.db.DataDao
import com.camillanapoles.droidauditor.domain.TopologyApp
import com.camillanapoles.droidauditor.domain.TopologyCategory
import com.camillanapoles.droidauditor.domain.TopologyTypeSection

/**
 * Categorical topology over the collected audit data:
 * Tipo [Sistema/Usuário] -> Categoria funcional -> apps ordenados por
 * prioridade (oom adj), impacto (RSS + cache + permissões + ociosidade)
 * e ordem de surgimento (install order).
 *
 * Pure computation over DataDao queries — no extra collection pass needed;
 * everything derives from packages + processes + permissions + storage.
 */
class TopologyBuilder(private val dataDao: DataDao) {

    fun build(runId: Long): List<TopologyTypeSection> {
        if (runId <= 0) return emptyList()

        val packages = dataDao.packagesFor(runId)
        if (packages.isEmpty()) return emptyList()

        val processes = dataDao.processesFor(runId, 5000)
        val dangerous = dataDao.grantedDangerousCounts(runId, 2000)
            .associate { it.packageName to it.count }
        val cache = dataDao.cacheBytesByPackage(runId)

        // Install order across all packages (1 = oldest).
        val installOrder: Map<String, Int> = packages
            .sortedBy { it.firstInstallTime }
            .mapIndexed { index, row -> row.packageName to (index + 1) }
            .toMap()

        val procsByPkg = processes.filter { !it.packageName.isNullOrBlank() }
            .groupBy { it.packageName!! }

        val apps = packages.map { row ->
            val procs = procsByPkg[row.packageName].orEmpty()
            val rssKb = procs.sumOf { it.rssKb }
            val adj = procs.mapNotNull { it.oomAdj }.minOrNull()
            val isCachedIdle = procs.isNotEmpty() &&
                procs.all { it.isCached && !it.hasServices && !it.hasForeground }
            TopologyApp(
                packageName = row.packageName,
                isSystem = row.isSystem,
                category = categoryOf(row.packageName, row.isSystem),
                priority = priorityOf(adj),
                impactScore = impactScore(
                    rssKb = rssKb,
                    cacheBytes = cache[row.packageName] ?: 0L,
                    dangerousPerms = dangerous[row.packageName] ?: 0,
                    isRunning = procs.isNotEmpty(),
                    isCachedIdle = isCachedIdle,
                    priority = priorityOf(adj)
                ),
                rssKb = rssKb,
                cacheBytes = cache[row.packageName] ?: 0L,
                dangerousPerms = dangerous[row.packageName] ?: 0,
                installOrder = installOrder[row.packageName] ?: 0,
                isRunning = procs.isNotEmpty(),
                isCachedIdle = isCachedIdle
            )
        }

        return listOf(false, true).map { isSystem ->
            val sectionApps = apps.filter { it.isSystem == isSystem }
                .sortedBy { it.packageName }
            TopologyTypeSection(
                isSystem = isSystem,
                apps = sectionApps,
                categories = sectionApps
                    .groupBy { it.category }
                    .map { (name, list) ->
                        TopologyCategory(
                            name = name,
                            apps = list,
                            totalRssKb = list.sumOf { it.rssKb },
                            totalCacheBytes = list.sumOf { it.cacheBytes },
                            maxImpact = list.maxOfOrNull { it.impactScore } ?: 0
                        )
                    }
                    .sortedBy { it.name }
            )
        }
    }

    companion object {

        /** Functional category for a package; ordered rules, first match wins. */
        fun categoryOf(pkg: String, isSystem: Boolean): String = when {
            pkg.contains("inputmethod") || pkg.endsWith(".ime") || pkg.contains(".ime.") -> "Teclado (IME)"
            pkg.contains("launcher") || pkg.contains(".home") -> "Launcher"
            pkg.startsWith("com.google.android.gms") ||
                pkg.startsWith("com.google.android.gsf") ||
                pkg == "com.android.vending" ||
                pkg.startsWith("com.google.android.googlequicksearchbox") -> "Google/Servicos"
            pkg.contains("phone") || pkg.contains("telephony") || pkg.contains(".ims") ||
                pkg.contains("radio") || pkg.contains("dataservices") ||
                pkg.contains("networkstack") || pkg.contains("modem") -> "Telefonia/Rede"
            pkg.contains("bluetooth") -> "Bluetooth"
            pkg == "com.android.systemui" || pkg.contains(".aospa.") ||
                pkg.contains("lineageos") || pkg.contains("libremobileos") ||
                pkg.contains("protonaosp") || pkg.contains("lunaris") ||
                pkg.contains("neoteric") || pkg.contains("axion.") -> "Interface do Sistema"
            pkg.startsWith("com.android.providers.") || pkg == "android.process.acore" ||
                pkg.contains("ext.services") || pkg.contains("ext.shared") -> "Provedores/Shared"
            pkg.contains("settings") || pkg.contains("updat") || pkg.contains("shell") ||
                pkg.contains("backup") || pkg.contains("adb") -> "Manutencao do Sistema"
            pkg.contains("camera") || pkg.contains("gallery") || pkg.contains("music") ||
                pkg.contains("media") || pkg.contains("video") || pkg.contains("audio") -> "Midia"
            pkg.contains("vpn") || pkg.contains("firewall") || pkg.contains("permission") ||
                pkg.contains("security") || pkg.contains("lockdown") -> "Seguranca"
            !isSystem -> "Aplicativos do Usuario"
            else -> "Sistema (outros)"
        }

        /**
         * Priority 0-5 from the best (lowest) oom adj of the app's processes.
         * -1 = not running.
         */
        fun priorityOf(adj: Int?): Int = when {
            adj == null -> -1
            adj <= -700 -> 5          // persistent
            adj <= 0 -> 4             // foreground / top
            adj <= 250 -> 3           // visible / perceptible
            adj <= 500 -> 2           // service / backup
            adj <= 800 -> 1           // home / previous / service-b
            else -> 0                 // cached
        }

        /** Composite impact 0-100. */
        fun impactScore(
            rssKb: Long,
            cacheBytes: Long,
            dangerousPerms: Int,
            isRunning: Boolean,
            isCachedIdle: Boolean,
            priority: Int
        ): Int {
            var score = 0.0
            score += minOf(40.0, rssKb / 1024.0 / 10.0)          // 400 MB+ => 40
            score += minOf(25.0, cacheBytes / 1024.0 / 1024.0 / 8.0) // 200 MB+ => 25
            score += minOf(20.0, dangerousPerms * 2.0)
            if (isRunning) score += 5.0
            if (isCachedIdle) score += 10.0                       // running without real activity
            if (priority >= 5) score += 5.0                        // always-resident weight
            return score.toInt().coerceIn(0, 100)
        }
    }
}
