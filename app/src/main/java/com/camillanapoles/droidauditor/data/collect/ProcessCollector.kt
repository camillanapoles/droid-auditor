package com.camillanapoles.droidauditor.data.collect

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.camillanapoles.droidauditor.data.db.DataDao
import com.camillanapoles.droidauditor.data.exec.ShellExec
import java.io.File

/**
 * Processes via `ps -A -o USER,PID,PPID,RSS,NAME` with fallback to legacy `ps -A`
 * and finally a /proc walk. Enriched with `dumpsys activity processes`
 * (oom adj / cached state) and a uid -> package map from the DB plus
 * `pm list packages -U`. Best effort: never crashes on format drift.
 */
class ProcessCollector(
    private val shellExec: ShellExec,
    private val dataDao: DataDao
) : Collector {

    override val id: String = CollectorIds.PROCESSES

    internal class RawProc(uid: Int, pid: Int, ppid: Int, rssKb: Long, state: String) {
        var uid: Int = uid
        var pid: Int = pid
        var ppid: Int = ppid
        var rssKb: Long = rssKb
        var state: String = state
        var name: String = ""
    }

    internal class OomInfo(val adj: Int?, val cached: Boolean)

    override suspend fun collect(runId: Long, runDir: File) {
        val uidMap = HashMap<Int, String>(dataDao.uidToPackageMap(runId))
        for ((uid, pkg) in parsePmListPackagesU(shellExec.exec("pm list packages -U", asRoot = false).output)) {
            if (!uidMap.containsKey(uid)) uidMap[uid] = pkg
        }

        var rows = parseModernPs(shellExec.exec("ps -A -o USER,PID,PPID,RSS,NAME", asRoot = false).output)
        if (rows.size < 3) {
            rows = parseLegacyPs(shellExec.exec("ps -A", asRoot = false).output)
        }
        if (rows.size < 3) {
            rows = readProc()
        }

        val oomPair = parseOomDump(shellExec.exec("dumpsys activity processes", asRoot = false).output)
        val oomByName = oomPair.first
        val oomByPid = oomPair.second

        val values = ArrayList<ContentValues>(rows.size)
        for (proc in rows) {
            if (proc.uid < 0) proc.uid = readUidFromProc(proc.pid)
            var info = oomByName[proc.name]
            if (info == null) info = oomByPid[proc.pid]
            val cv = ContentValues()
            cv.put("run_id", runId)
            cv.put("pid", proc.pid)
            cv.put("ppid", proc.ppid)
            cv.put("uid", proc.uid)
            cv.put("name", proc.name)
            cv.put("rss_kb", proc.rssKb)
            cv.put("state", proc.state)
            if (info?.adj != null) cv.put("oom_adj", info.adj) else cv.putNull("oom_adj")
            cv.put("package_name", if (proc.uid >= 0) uidMap[proc.uid] else null)
            cv.put("is_cached", if (info?.cached == true) 1 else 0)
            cv.put("has_services", 0)
            cv.put("has_foreground", 0)
            values.add(cv)
        }

        dataDao.inTx { db ->
            for (cv in values) {
                db.insertWithOnConflict("processes", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    // ---------- ps parsing ----------

    private fun parseModernPs(output: String): MutableList<RawProc> {
        val out = ArrayList<RawProc>()
        for (line in output.lineSequence()) {
            val f = line.trim().split(WHITESPACE)
            if (f.size < 5) continue
            val pid = f[1].toIntOrNull() ?: continue
            val ppid = f[2].toIntOrNull() ?: continue
            val rss = f[3].toLongOrNull() ?: continue
            if (pid <= 0) continue
            val name = f.subList(4, f.size).joinToString(" ")
            out.add(RawProc(userToUid(f[0]), pid, ppid, rss, "").apply { this.name = name })
        }
        return out
    }

    private fun parseLegacyPs(output: String): MutableList<RawProc> {
        val out = ArrayList<RawProc>()
        for (line in output.lineSequence()) {
            val f = line.trim().split(WHITESPACE)
            if (f.size < 10) continue // USER PID PPID VSZ RSS WCHAN ADDR S NAME...
            val pid = f[1].toIntOrNull() ?: continue
            val ppid = f[2].toIntOrNull() ?: continue
            val rss = f[4].toLongOrNull() ?: continue
            if (pid <= 0) continue
            val state = f[8]
            val name = f.subList(9, f.size).joinToString(" ")
            out.add(RawProc(userToUid(f[0]), pid, ppid, rss, state).apply { this.name = name })
        }
        return out
    }

    private fun readProc(): MutableList<RawProc> {
        val out = ArrayList<RawProc>()
        val dirs = File("/proc").listFiles() ?: return out
        for (dir in dirs) {
            val pid = dir.name.toIntOrNull() ?: continue
            try {
                val stat = File(dir, "stat").readText()
                val close = stat.lastIndexOf(')')
                val open = stat.indexOf('(')
                if (open < 0 || close <= open || close + 2 >= stat.length) continue
                val name = stat.substring(open + 1, close)
                val rest = stat.substring(close + 2).split(' ')
                if (rest.size < 22) continue
                val state = rest[0]
                val ppid = rest[1].toIntOrNull() ?: continue
                val rssPages = rest[21].toLongOrNull() ?: 0L
                out.add(RawProc(readUidFromProc(pid), pid, ppid, rssPages * 4L, state).apply { this.name = name })
            } catch (_: Exception) {
                // process vanished or unreadable stat; skip
            }
        }
        return out
    }

    private fun readUidFromProc(pid: Int): Int {
        return try {
            File("/proc/$pid/status").bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("Uid:")) {
                        return line.split(WHITESPACE).getOrNull(1)?.toIntOrNull() ?: -1
                    }
                }
            }
            -1
        } catch (_: Exception) {
            -1
        }
    }

    internal fun userToUid(user: String): Int {
        user.toIntOrNull()?.let { return it }
        return when (user) {
            "root" -> 0
            "system" -> 1000
            else -> {
                val app = Regex("^u(\\d+)_a(\\d+)$").find(user)
                if (app != null) {
                    val userId = app.groupValues[1].toIntOrNull() ?: return -1
                    val appId = app.groupValues[2].toIntOrNull() ?: return -1
                    userId * 100000 + 10000 + appId
                } else {
                    val isolated = Regex("^u(\\d+)_i(\\d+)$").find(user)
                    if (isolated != null) {
                        val userId = isolated.groupValues[1].toIntOrNull() ?: return -1
                        val appId = isolated.groupValues[2].toIntOrNull() ?: return -1
                        userId * 100000 + 99000 + appId
                    } else {
                        -1
                    }
                }
            }
        }
    }

    // ---------- supplementary parsing ----------

    private fun parsePmListPackagesU(output: String): Map<Int, String> {
        val map = HashMap<Int, String>()
        for (line in output.lineSequence()) {
            val uidMatch = Regex("uid:\\s*(\\d+)").find(line) ?: continue
            val uid = uidMatch.groupValues[1].toIntOrNull() ?: continue
            val matcher = ParseUtil.PACKAGE_PATTERN.matcher(line)
            var pkg: String? = null
            while (matcher.find()) {
                val token = matcher.group() ?: continue
                if (token == "uid") continue
                if (token.endsWith(".apk") || token.endsWith(".so") || token.endsWith(".dex")) continue
                pkg = token
            }
            if (pkg != null) map[uid] = pkg
        }
        return map
    }

    private fun parseOomDump(output: String): Pair<Map<String, OomInfo>, Map<Int, OomInfo>> {
        val byName = HashMap<String, OomInfo>()
        val byPid = HashMap<Int, OomInfo>()
        val procRe = Regex("(\\d+):([^\\s/(]+)\\s*\\(([^)]*)\\)")
        val adjRe = Regex("(?:oom adj|adj)[= ](-?\\d+)")
        val cachedRe = Regex("cached|prev|empty|home", RegexOption.IGNORE_CASE)
        for (line in output.lineSequence()) {
            val proc = procRe.find(line) ?: continue
            val pid = proc.groupValues[1].toIntOrNull() ?: continue
            val name = proc.groupValues[2]
            val adj = adjRe.find(line)?.groupValues?.get(1)?.toIntOrNull()
            val info = OomInfo(adj, cachedRe.containsMatchIn(proc.groupValues[3]))
            byName[name] = info
            byPid[pid] = info
        }
        return byName to byPid
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")
    }
}
