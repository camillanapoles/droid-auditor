package com.camillanapoles.droidauditor.data.db

/** Key/value settings, seeded from assets/seed/settings.json, editable at runtime. */
class SettingsDao(private val dbHelper: DbHelper) {

    fun get(key: String, default: String): String {
        dbHelper.readableDatabase.rawQuery("SELECT value FROM settings WHERE key = ?", arrayOf(key)).use { c ->
            return if (c.moveToFirst()) c.getString(0) ?: default else default
        }
    }

    fun getLong(key: String, default: Long): Long {
        val raw = get(key, default.toString())
        return raw.toLongOrNull() ?: default
    }

    fun put(key: String, value: String) {
        dbHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO settings(key, value) VALUES(?, ?)",
            arrayOf(key, value)
        )
    }

    fun all(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        dbHelper.readableDatabase.rawQuery("SELECT key, value FROM settings ORDER BY key", null).use { c ->
            while (c.moveToNext()) {
                result[c.getString(0) ?: continue] = c.getString(1) ?: ""
            }
        }
        return result
    }
}
