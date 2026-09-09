# Droid Auditor

On-device holistic Android auditor — **root-optional, degrades gracefully**.
It bundles a forensic shell toolchain (41 audit scripts + report zipper), collects
Android entities with built-in Kotlin collectors (packages, processes, services,
activities, permissions, usage stats, storage), correlates everything in a local
relational SQLite database, produces tagged findings, and offers optimization
actions including Termux cache cleanup.

**Everything lives in the DB.** The AndroidManifest contains only the launcher
activity and permissions ("manifest zero hardcode"). The command catalog,
categories, relation-type registry and default settings are seeded on first
launch from `app/src/main/assets/seed/*.json` into SQLite and are fully
editable at runtime from the **Commands** screen (complete CRUD — the
device-local equivalent of an AdbCommands.md catalog).

## Architecture

```
assets/toolchain/*.sh        42 forensic scripts (0_RunAll.sh excluded; the app runs them individually)
assets/seed/*.json           commands (103), categories (17), settings, relation_types — the whole config surface
AuditApp / di.AppContainer   manual DI; seeds the DB on first launch
data/db                      DbHelper (SQLiteOpenHelper, WAL, user_version=1) + 6 DAOs
data/exec                    RootAccess (su -c id), ShellExec (sh -c / su -c "sh -c '…'", 120 s timeout),
                             ScriptInstaller (assets/toolchain → filesDir/toolchain)
data/collect                 packages, permissions, processes (ps→legacy ps→/proc fallbacks, dumpsys oom),
                             services, focus/foreground, launch-graph (ActivityTaskManager logcat),
                             usage-stats (AppOps-gated), storage caches + Termux paths (root du, batched)
data/correlate               entities + edges graph (runs_as, spawned, binds_to, launched, installs, uses_permission)
data/findings                FindingsParser ([CRITICAL]/[HIGH]/[MEDIUM]/[INFO] scan of every raw output),
                             OptimizerRules (caches, running-but-idle, Termux, BOOT_COMPLETED, dangerous perms)
engine                       AuditEngine (scripted run loop, progress StateFlow), ActionExecutor (danger-gated actions)
ui                           Compose Material3, bottom-nav: Dashboard / Audit / Explorer / Timeline / Optimize / Commands
```

### Database schema (v1, `PRAGMA user_version=1`, WAL)

| Table | Purpose |
|---|---|
| `settings` | key/value defaults (idle_days_threshold, min_cache_mb, usage_lookback_days, max_logcat_lines) |
| `commands` | the catalog: name, category, kind (`script`/`shell`/`collector`/`action`), command, requires_root, parser, enabled, danger, source |
| `audit_runs`, `script_outputs` | run metadata + per-command exit code, raw output path, duration, findings count |
| `packages`, `permissions`, `processes`, `services`, `activities` | collected entities (PK includes run_id) |
| `launch_events`, `install_timeline`, `usage_events`, `storage_entries` | who-launched-whom, install order, usage, cache sizes |
| `findings` | severity-tagged results from scripts, collectors and the optimizer |
| `entities`, `edges`, `relation_types` | unified correlation graph with a seeded label registry |

Runs are stored under `getExternalFilesDir(null)/runs/run_<ts>/` (raw output in
`raw/<commandId>_<slug>.txt`), so no storage permission is needed. `onUpgrade`
drops and recreates all tables (v1 has no migrations to preserve).

## Usage

1. Install the APK (debug or CI-built release).
2. **Dashboard** — check root status (optional; recheck available), grant
   *Usage stats access* if you want real idle detection, then **Full audit**.
3. **Audit** — live per-command progress; tap any output for the monospace
   viewer (severity-colored); findings tab with severity filters and
   related-package navigation.
4. **Explorer** — packages (search, system filter) → package detail with
   permissions, processes, services, activities, graph edges in/out (relation
   registry labels) and last usage; plus Processes / Services / Findings tabs.
5. **Timeline** — install order ("ordem de surgimento") with installer chains and
   the recent launch graph ("quem chamou quem": `A → B (activity, ts)`).
6. **Optimize** — suggestions by severity; destructive actions (clear app cache,
   force stop, Termux cache clean, `pm trim-caches`) always require confirmation.
7. **Explorer → Topologia** — categorical tree: Tipo [Usuário/Sistema] →
   categoria funcional → apps por prioridade (P0-P5 do oom adj), impacto
   composto (RSS + cache + permissões perigosas + ociosidade) e ordem de
   surgimento (install order); filtros e ordenação Impacto/Prioridade/Ordem/A-Z.
8. **Explorer → Sobreposição** — "qual app está em sobreposição?": permissão
   AppOps por app (sem root) + janelas overlay ATIVAS via `dumpsys window`
   (root best-effort) + ação Revogar (`appops set ... deny`).
9. **Audit → Diagnóstico** — crashes/ANRs (logcat crash+events buffers) com
   diagnóstico RESOLUTIVO: título + causa + solução + comando executável
   (Corrigir, danger-gated). Sem root cobre os próprios logs; com
   `adb shell pm grant <pkg> android.permission.READ_LOGS` cobre todos os apps.
10. **Package detail → Utilizado por (árvore)** — árvore reversa de dependências
    (quem usa este app/provider/serviço), nós clicáveis.
7. **Commands** — full CRUD over the catalog; `kind=shell` rows have *Run now*
   with an output viewer. `<outdir>` and `<pkg>` are substituted at run time.

## Building

Android Studio / Gradle: `./gradlew assembleDebug` (or `assembleRelease`).
CI builds via GitHub Actions on JDK 17 (`.github/workflows`, maintained
alongside this repo). Pinned toolchain: Gradle 8.7, AGP 8.5.2, Kotlin 1.9.24,
Compose compiler 1.5.14, Compose BOM 2024.06.00, compileSdk 34, minSdk 26,
JVM target 17. No annotation processing (no kapt/ksp/Hilt/Room) — plain
`SQLiteOpenHelper` + `org.json`.

Release signing: create a root `keystore.properties`
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) next to
`build.gradle.kts`; without it, release builds fall back to debug signing so
`assembleRelease` always yields an installable APK.

## Notes & legal

- **QUERY_ALL_PACKAGES**: the auditor's purpose is device-wide package,
  process and permission correlation; the full package list is required for
  uid↔package mapping and the correlation graph. Nothing leaves the device.
- **Outputs contain sensitive data.** Raw command output, findings and reports
  (dumpsys, properties, secrets-scan hits, …) are written under app-private
  external storage — treat run directories and zipped reports as confidential.
- The bundled scripts and actions can modify device state (`pm clear`,
  force-stop, cache deletion, battery faking, input injection). Destructive
  entries are seeded with `danger=1` and the UI asks for confirmation.
  Use responsibly and consult your local laws; the toolchain's original legal
  notice applies ("I'm not responsible for anything stupid you do with these").
