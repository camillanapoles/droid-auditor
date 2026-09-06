package com.camillanapoles.droidauditor.data.db

import android.content.ContentValues
import android.database.Cursor
import com.camillanapoles.droidauditor.domain.CommandRow

/** CRUD over the commands catalog (the device-local AdbCommands.md surface). */
class CommandDao(private val dbHelper: DbHelper) {

    private val columns = "id, name, category, kind, command, requires_root, parser, enabled, danger, description, source, updated_at"

    fun listAll(): List<CommandRow> =
        query("$columns FROM commands ORDER BY category, name")

    fun listEnabled(): List<CommandRow> =
        query("$columns FROM commands WHERE enabled = 1 ORDER BY id")

    fun byId(id: Long): CommandRow? =
        query("$columns FROM commands WHERE id = ?", id.toString()).firstOrNull()

    fun categories(): List<String> {
        val out = ArrayList<String>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT DISTINCT category FROM commands ORDER BY category", null
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0) ?: continue)
        }
        return out
    }

    fun insert(row: CommandRow): Long {
        val db = dbHelper.writableDatabase
        return db.insert("commands", null, toValues(row))
    }

    fun update(row: CommandRow) {
        val db = dbHelper.writableDatabase
        db.update("commands", toValues(row), "id = ?", arrayOf(row.id.toString()))
    }

    fun delete(id: Long) {
        dbHelper.writableDatabase.delete("commands", "id = ?", arrayOf(id.toString()))
    }

    fun setEnabled(id: Long, enabled: Boolean) {
        val cv = ContentValues()
        cv.put("enabled", if (enabled) 1 else 0)
        cv.put("updated_at", System.currentTimeMillis())
        dbHelper.writableDatabase.update("commands", cv, "id = ?", arrayOf(id.toString()))
    }

    fun findByCommand(command: String): CommandRow? =
        query("$columns FROM commands WHERE command = ? LIMIT 1", command).firstOrNull()

    private fun toValues(row: CommandRow): ContentValues {
        val cv = ContentValues()
        cv.put("name", row.name)
        cv.put("category", row.category)
        cv.put("kind", row.kind)
        cv.put("command", row.command)
        cv.put("requires_root", if (row.requiresRoot) 1 else 0)
        cv.put("parser", row.parser)
        cv.put("enabled", if (row.enabled) 1 else 0)
        cv.put("danger", if (row.danger) 1 else 0)
        cv.put("description", row.description)
        cv.put("source", row.source)
        cv.put("updated_at", System.currentTimeMillis())
        return cv
    }

    private fun query(sql: String, vararg args: String): List<CommandRow> {
        val out = ArrayList<CommandRow>()
        dbHelper.readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) out.add(mapRow(c))
        }
        return out
    }

    private fun mapRow(c: Cursor): CommandRow = CommandRow(
        id = c.getLong(0),
        name = c.getString(1) ?: "",
        category = c.getString(2) ?: "",
        kind = c.getString(3) ?: "",
        command = c.getString(4) ?: "",
        requiresRoot = c.getInt(5) != 0,
        parser = c.getString(6) ?: "raw",
        enabled = c.getInt(7) != 0,
        danger = c.getInt(8) != 0,
        description = c.getString(9) ?: "",
        source = c.getString(10) ?: "seed",
        updatedAt = c.getLong(11)
    )
}
