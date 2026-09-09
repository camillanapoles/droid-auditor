package com.camillanapoles.droidauditor.data.seed

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.camillanapoles.droidauditor.data.db.DbHelper
import com.camillanapoles.droidauditor.domain.CategoryInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Seeds commands, settings and relation_types from the seed JSON assets on first launch.
 * Everything configurable lives in the DB; the Kotlin code contains no catalog.
 */
class SeedLoader(private val context: Context, private val dbHelper: DbHelper) {

    fun seedIfEmpty() {
        // commands rows are (re)seeded idempotently: the UNIQUE index on
        // commands(name) + CONFLICT_IGNORE adds catalog entries shipped in
        // newer asset versions without touching user edits on older installs.
        seedCommands()
        if (countRows("relation_types") == 0L) seedRelations()
        seedSettings()
    }

    fun loadCategories(): List<CategoryInfo> {
        val out = ArrayList<CategoryInfo>()
        val array = JSONArray(readAsset("categories.json"))
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            out.add(CategoryInfo(obj.getString("name"), obj.optString("description", "")))
        }
        return out
    }

    private fun seedCommands() {
        val array = JSONArray(readAsset("commands.json"))
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val cv = ContentValues()
                cv.put("name", o.getString("name"))
                cv.put("category", o.getString("category"))
                cv.put("kind", o.getString("kind"))
                cv.put("command", o.getString("command"))
                cv.put("requires_root", if (o.optBoolean("requires_root", false)) 1 else 0)
                cv.put("parser", o.optString("parser", "raw"))
                cv.put("enabled", if (o.optBoolean("enabled", true)) 1 else 0)
                cv.put("danger", if (o.optBoolean("danger", false)) 1 else 0)
                cv.put("description", o.optString("description", ""))
                cv.put("source", "seed")
                cv.put("updated_at", System.currentTimeMillis())
                db.insertWithOnConflict("commands", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun seedSettings() {
        val obj = JSONObject(readAsset("settings.json"))
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            for (key in obj.keys()) {
                db.insertWithOnConflict(
                    "settings", null,
                    ContentValues().apply {
                        put("key", key)
                        put("value", obj.getString(key))
                    },
                    SQLiteDatabase.CONFLICT_IGNORE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun seedRelations() {
        val array = JSONArray(readAsset("relations.json"))
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                db.insertWithOnConflict(
                    "relation_types", null,
                    ContentValues().apply {
                        put("name", o.getString("name"))
                        put("forward_label", o.getString("forward_label"))
                        put("reverse_label", o.getString("reverse_label"))
                    },
                    SQLiteDatabase.CONFLICT_IGNORE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun countRows(table: String): Long {
        dbHelper.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return 0L
    }

    private fun readAsset(name: String): String =
        context.assets.open("seed/$name").bufferedReader().use { it.readText() }
}
