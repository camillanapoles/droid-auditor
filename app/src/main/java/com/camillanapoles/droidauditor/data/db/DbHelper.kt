package com.camillanapoles.droidauditor.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Single source of truth for the relational audit schema.
 * user_version = 1. WAL is enabled. onUpgrade drops and recreates (v1 has no migrations).
 */
class DbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        for (statement in DDL) {
            db.execSQL(statement)
        }
        db.execSQL("PRAGMA user_version = $DB_VERSION")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        for (table in ALL_TABLES) {
            db.execSQL("DROP TABLE IF EXISTS $table")
        }
        onCreate(db)
    }

    companion object {
        const val DB_NAME = "droidauditor.db"
        const val DB_VERSION = 1

        val ALL_TABLES = listOf(
            "settings", "commands", "audit_runs", "script_outputs", "packages",
            "permissions", "processes", "services", "activities", "launch_events",
            "install_timeline", "usage_events", "storage_entries", "findings",
            "entities", "edges", "relation_types"
        )

        private val DDL: List<String> = listOf(
            "CREATE TABLE settings (" +
                "key TEXT PRIMARY KEY, " +
                "value TEXT)",
            "CREATE TABLE commands (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "name TEXT NOT NULL, " +
                "category TEXT NOT NULL, " +
                "kind TEXT NOT NULL CHECK(kind IN ('script','shell','collector','action')), " +
                "command TEXT NOT NULL, " +
                "requires_root INTEGER DEFAULT 0, " +
                "parser TEXT DEFAULT 'raw', " +
                "enabled INTEGER DEFAULT 1, " +
                "danger INTEGER DEFAULT 0, " +
                "description TEXT, " +
                "source TEXT DEFAULT 'seed', " +
                "updated_at INTEGER)",
            "CREATE TABLE audit_runs (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "started_at INTEGER, " +
                "finished_at INTEGER, " +
                "root_granted INTEGER, " +
                "device_model TEXT, " +
                "android_version TEXT, " +
                "build_fingerprint TEXT, " +
                "scripts_ok INTEGER, " +
                "scripts_fail INTEGER)",
            "CREATE TABLE script_outputs (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "run_id INTEGER REFERENCES audit_runs(id), " +
                "command_id INTEGER REFERENCES commands(id), " +
                "exit_code INTEGER, " +
                "stdout_path TEXT, " +
                "stdout_len INTEGER, " +
                "duration_ms INTEGER, " +
                "findings_count INTEGER DEFAULT 0, " +
                "started_at INTEGER)",
            "CREATE TABLE packages (" +
                "run_id INTEGER, " +
                "package_name TEXT, " +
                "uid INTEGER, " +
                "version_name TEXT, " +
                "version_code INTEGER, " +
                "first_install_time INTEGER, " +
                "last_update_time INTEGER, " +
                "installer_pkg TEXT, " +
                "apk_path TEXT, " +
                "is_system INTEGER, " +
                "is_enabled INTEGER, " +
                "target_sdk INTEGER, " +
                "permission_count INTEGER, " +
                "PRIMARY KEY(run_id, package_name))",
            "CREATE TABLE permissions (" +
                "run_id INTEGER, " +
                "package_name TEXT, " +
                "permission_name TEXT, " +
                "granted INTEGER, " +
                "protection_level TEXT, " +
                "PRIMARY KEY(run_id, package_name, permission_name))",
            "CREATE TABLE processes (" +
                "run_id INTEGER, " +
                "pid INTEGER, " +
                "ppid INTEGER, " +
                "uid INTEGER, " +
                "name TEXT, " +
                "rss_kb INTEGER, " +
                "state TEXT, " +
                "oom_adj INTEGER, " +
                "package_name TEXT, " +
                "is_cached INTEGER, " +
                "has_services INTEGER DEFAULT 0, " +
                "has_foreground INTEGER DEFAULT 0, " +
                "PRIMARY KEY(run_id, pid))",
            "CREATE TABLE services (" +
                "run_id INTEGER, " +
                "package_name TEXT, " +
                "service_class TEXT, " +
                "process_name TEXT, " +
                "client_packages TEXT, " +
                "started_by TEXT, " +
                "PRIMARY KEY(run_id, package_name, service_class))",
            "CREATE TABLE activities (" +
                "run_id INTEGER, " +
                "package_name TEXT, " +
                "activity_class TEXT, " +
                "is_resumed INTEGER DEFAULT 0, " +
                "PRIMARY KEY(run_id, package_name, activity_class))",
            "CREATE TABLE launch_events (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "run_id INTEGER, " +
                "ts INTEGER, " +
                "from_package TEXT, " +
                "to_package TEXT, " +
                "activity TEXT, " +
                "evidence TEXT)",
            "CREATE TABLE install_timeline (" +
                "run_id INTEGER, " +
                "package_name TEXT, " +
                "first_install_time INTEGER, " +
                "last_update_time INTEGER, " +
                "installer_pkg TEXT, " +
                "PRIMARY KEY(run_id, package_name))",
            "CREATE TABLE usage_events (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "run_id INTEGER, " +
                "ts INTEGER, " +
                "package_name TEXT, " +
                "event_type TEXT, " +
                "extra TEXT)",
            "CREATE TABLE storage_entries (" +
                "run_id INTEGER, " +
                "path TEXT, " +
                "size_bytes INTEGER, " +
                "package_name TEXT, " +
                "category TEXT, " +
                "PRIMARY KEY(run_id, path))",
            "CREATE TABLE findings (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "run_id INTEGER, " +
                "source TEXT, " +
                "severity TEXT, " +
                "message TEXT, " +
                "evidence_path TEXT, " +
                "related_package TEXT)",
            "CREATE TABLE entities (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "run_id INTEGER, " +
                "etype TEXT, " +
                "ekey TEXT, " +
                "elabel TEXT)",
            "CREATE TABLE edges (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "run_id INTEGER, " +
                "src INTEGER, " +
                "dst INTEGER, " +
                "relation TEXT, " +
                "evidence TEXT)",
            "CREATE TABLE relation_types (" +
                "name TEXT PRIMARY KEY, " +
                "forward_label TEXT, " +
                "reverse_label TEXT)",
            // Indexes on run_id / package_name hot paths
            "CREATE INDEX idx_script_outputs_run ON script_outputs(run_id)",
            "CREATE INDEX idx_script_outputs_command ON script_outputs(command_id)",
            "CREATE INDEX idx_commands_category ON commands(category)",
            "CREATE INDEX idx_commands_kind ON commands(kind)",
            "CREATE INDEX idx_packages_run ON packages(run_id)",
            "CREATE INDEX idx_packages_pkg ON packages(package_name)",
            "CREATE INDEX idx_permissions_run ON permissions(run_id)",
            "CREATE INDEX idx_permissions_pkg ON permissions(package_name)",
            "CREATE INDEX idx_processes_run ON processes(run_id)",
            "CREATE INDEX idx_processes_pkg ON processes(package_name)",
            "CREATE INDEX idx_services_run ON services(run_id)",
            "CREATE INDEX idx_services_pkg ON services(package_name)",
            "CREATE INDEX idx_activities_run ON activities(run_id)",
            "CREATE INDEX idx_activities_pkg ON activities(package_name)",
            "CREATE INDEX idx_launch_events_run ON launch_events(run_id)",
            "CREATE INDEX idx_install_timeline_run ON install_timeline(run_id)",
            "CREATE INDEX idx_install_timeline_pkg ON install_timeline(package_name)",
            "CREATE INDEX idx_usage_events_run ON usage_events(run_id)",
            "CREATE INDEX idx_usage_events_pkg ON usage_events(package_name)",
            "CREATE INDEX idx_storage_run ON storage_entries(run_id)",
            "CREATE INDEX idx_storage_pkg ON storage_entries(package_name)",
            "CREATE INDEX idx_findings_run ON findings(run_id)",
            "CREATE INDEX idx_findings_related ON findings(related_package)",
            "CREATE INDEX idx_findings_severity ON findings(severity)",
            "CREATE INDEX idx_entities_run ON entities(run_id)",
            "CREATE INDEX idx_entities_ekey ON entities(ekey)",
            "CREATE INDEX idx_entities_etype ON entities(etype)",
            "CREATE INDEX idx_edges_run ON edges(run_id)",
            "CREATE INDEX idx_edges_src ON edges(src)",
            "CREATE INDEX idx_edges_dst ON edges(dst)",
            "CREATE INDEX idx_edges_relation ON edges(relation)"
        )
    }
}
