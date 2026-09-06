package com.camillanapoles.droidauditor.di

import android.content.Context
import com.camillanapoles.droidauditor.data.db.CommandDao
import com.camillanapoles.droidauditor.data.db.DataDao
import com.camillanapoles.droidauditor.data.db.DbHelper
import com.camillanapoles.droidauditor.data.db.FindingsDao
import com.camillanapoles.droidauditor.data.db.GraphDao
import com.camillanapoles.droidauditor.data.db.RunDao
import com.camillanapoles.droidauditor.data.db.SettingsDao
import com.camillanapoles.droidauditor.data.exec.RootAccess
import com.camillanapoles.droidauditor.data.exec.ScriptInstaller
import com.camillanapoles.droidauditor.data.exec.ShellExec
import com.camillanapoles.droidauditor.data.seed.SeedLoader
import com.camillanapoles.droidauditor.domain.CategoryInfo
import com.camillanapoles.droidauditor.engine.ActionExecutor
import com.camillanapoles.droidauditor.engine.AuditEngine

/**
 * Manual dependency container: one instance per process, owned by AuditApp.
 * Seeding from assets runs once here so every DAO sees a populated catalog.
 */
class AppContainer(val context: Context) {

    val dbHelper = DbHelper(context)
    val settingsDao = SettingsDao(dbHelper)
    val commandDao = CommandDao(dbHelper)
    val runDao = RunDao(dbHelper)
    val dataDao = DataDao(dbHelper)
    val findingsDao = FindingsDao(dbHelper)
    val graphDao = GraphDao(dbHelper)

    val rootAccess = RootAccess()
    val shellExec = ShellExec(rootAccess)
    val scriptInstaller = ScriptInstaller(context)

    val actionExecutor = ActionExecutor(shellExec, rootAccess)

    private val seedLoader = SeedLoader(context, dbHelper)

    val engine = AuditEngine(this)

    /** Category registry from assets/seed/categories.json (descriptions for the Commands screen). */
    val categories: List<CategoryInfo> by lazy { seedLoader.loadCategories() }

    init {
        seedLoader.seedIfEmpty()
    }
}
