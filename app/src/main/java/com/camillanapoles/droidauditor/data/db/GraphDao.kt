package com.camillanapoles.droidauditor.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.camillanapoles.droidauditor.domain.EdgeDisplay
import com.camillanapoles.droidauditor.domain.RelationType

/** entities + edges graph, labels resolved through the seeded relation_types registry. */
class GraphDao(private val dbHelper: DbHelper) {

    fun insertEntity(db: SQLiteDatabase, runId: Long, etype: String, ekey: String, elabel: String): Long {
        val cv = ContentValues()
        cv.put("run_id", runId)
        cv.put("etype", etype)
        cv.put("ekey", ekey)
        cv.put("elabel", elabel)
        return db.insert("entities", null, cv)
    }

    fun insertEdge(
        db: SQLiteDatabase, runId: Long, src: Long, dst: Long, relation: String, evidence: String?
    ): Long {
        val cv = ContentValues()
        cv.put("run_id", runId)
        cv.put("src", src)
        cv.put("dst", dst)
        cv.put("relation", relation)
        cv.put("evidence", evidence)
        return db.insert("edges", null, cv)
    }

    fun relationTypes(): List<RelationType> {
        val out = ArrayList<RelationType>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT name, forward_label, reverse_label FROM relation_types ORDER BY name", null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    RelationType(
                        name = c.getString(0) ?: continue,
                        forwardLabel = c.getString(1) ?: "",
                        reverseLabel = c.getString(2) ?: ""
                    )
                )
            }
        }
        return out
    }

    /** All edges touching the entity identified by [ekey], with display labels. */
    fun edgesForEntity(runId: Long, ekey: String, limit: Int = 200): List<EdgeDisplay> {
        val relations = HashMap<String, RelationType>()
        for (r in relationTypes()) relations[r.name] = r

        val out = ArrayList<EdgeDisplay>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT e.relation, e.evidence, s.ekey, s.etype, s.elabel, d.ekey, d.etype, d.elabel " +
                "FROM edges e " +
                "JOIN entities s ON s.id = e.src " +
                "JOIN entities d ON d.id = e.dst " +
                "WHERE e.run_id = ? AND (s.ekey = ? OR d.ekey = ?) LIMIT $limit",
            arrayOf(runId.toString(), ekey, ekey)
        ).use { c ->
            while (c.moveToNext()) {
                val relation = c.getString(0) ?: ""
                val evidence = c.getString(1)
                val srcKey = c.getString(2) ?: ""
                val rel = relations[relation]
                val outgoing = srcKey == ekey
                out.add(
                    EdgeDisplay(
                        direction = if (outgoing) "out" else "in",
                        relation = relation,
                        label = if (outgoing) rel?.forwardLabel ?: relation else rel?.reverseLabel ?: relation,
                        otherKey = if (outgoing) c.getString(5) ?: "" else srcKey,
                        otherType = if (outgoing) c.getString(6) ?: "" else c.getString(3) ?: "",
                        otherLabel = if (outgoing) c.getString(7) ?: "" else c.getString(4) ?: "",
                        evidence = evidence
                    )
                )
            }
        }
        return out
    }

    /**
     * Reverse dependency tree ("utilizado por"): who depends on [ekey],
     * recursively. Incoming edges src -> ekey, labels from relation_types
     * (reverse). Guards: max depth, max nodes, visited-set (no cycles).
     */
    fun usedByTree(
        runId: Long,
        ekey: String,
        maxDepth: Int = 5,
        maxNodes: Int = 200
    ): List<com.camillanapoles.droidauditor.domain.UsedByNode> {
        val relations = HashMap<String, RelationType>()
        for (r in relationTypes()) relations[r.name] = r
        val visited = HashSet<String>()
        var budget = maxNodes

        fun expand(key: String, depth: Int): List<com.camillanapoles.droidauditor.domain.UsedByNode> {
            if (depth <= 0 || budget <= 0) return emptyList()
            val parents = ArrayList<Pair<String, Array<String>>>()  // relation, [ekey, etype, elabel]
            dbHelper.readableDatabase.rawQuery(
                "SELECT e.relation, s.ekey, s.etype, s.elabel " +
                    "FROM edges e " +
                    "JOIN entities s ON s.id = e.src " +
                    "JOIN entities d ON d.id = e.dst " +
                    "WHERE e.run_id = ? AND s.run_id = ? AND d.run_id = ? AND d.ekey = ? " +
                    "ORDER BY s.ekey LIMIT 60",
                arrayOf(runId.toString(), runId.toString(), runId.toString(), key)
            ).use { c ->
                while (c.moveToNext()) {
                    val otherKey = c.getString(1) ?: continue
                    if (otherKey == key) continue
                    parents.add(
                        (c.getString(0) ?: "") to arrayOf(otherKey, c.getString(2) ?: "", c.getString(3) ?: "")
                    )
                }
            }
            val out = ArrayList<com.camillanapoles.droidauditor.domain.UsedByNode>()
            for ((relation, fields) in parents) {
                if (budget <= 0) break
                if (!visited.add(fields[0])) {
                    // already expanded elsewhere: leaf reference, no recursion
                    out.add(
                        com.camillanapoles.droidauditor.domain.UsedByNode(
                            ekey = fields[0], etype = fields[1], elabel = fields[2],
                            relationLabel = relations[relation]?.reverseLabel ?: relation,
                            children = emptyList(), truncated = true
                        )
                    )
                    continue
                }
                budget--
                out.add(
                    com.camillanapoles.droidauditor.domain.UsedByNode(
                        ekey = fields[0], etype = fields[1], elabel = fields[2],
                        relationLabel = relations[relation]?.reverseLabel ?: relation,
                        children = expand(fields[0], depth - 1),
                        truncated = false
                    )
                )
            }
            return out
        }

        return expand(ekey, maxDepth)
    }

    fun countEdges(runId: Long): Int {
        dbHelper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM edges WHERE run_id = ?", arrayOf(runId.toString())
        ).use { c ->
            if (c.moveToFirst()) return c.getInt(0)
        }
        return 0
    }

    fun countEntities(runId: Long): Int {
        dbHelper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM entities WHERE run_id = ?", arrayOf(runId.toString())
        ).use { c ->
            if (c.moveToFirst()) return c.getInt(0)
        }
        return 0
    }
}
