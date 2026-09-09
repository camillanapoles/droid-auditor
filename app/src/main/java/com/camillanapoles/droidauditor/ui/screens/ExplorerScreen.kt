package com.camillanapoles.droidauditor.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.camillanapoles.droidauditor.R
import com.camillanapoles.droidauditor.di.AppContainer
import com.camillanapoles.droidauditor.ui.components.Badge
import com.camillanapoles.droidauditor.ui.components.EmptyState
import com.camillanapoles.droidauditor.ui.components.SeverityChip
import com.camillanapoles.droidauditor.ui.components.formatBytes
import com.camillanapoles.droidauditor.ui.vm.ExplorerViewModel
import com.camillanapoles.droidauditor.ui.vm.VmFactory
import com.camillanapoles.droidauditor.ui.theme.InfoBlue
import com.camillanapoles.droidauditor.ui.theme.OkGreen

/** Entity browser: Packages (search + system filter) / Processes / Services / Findings. */
@Composable
fun ExplorerScreen(container: AppContainer, onOpenPackage: (Long, String) -> Unit) {
    val vm: ExplorerViewModel = viewModel(factory = remember { VmFactory(container) })
    val packages by vm.packages.collectAsState()
    val processes by vm.processes.collectAsState()
    val services by vm.services.collectAsState()
    val findings by vm.findings.collectAsState()
    val runId by vm.runId.collectAsState()
    var tab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var includeSystem by remember { mutableStateOf(false) }

    LaunchedEffect(query, includeSystem) {
        vm.searchPackages(query, includeSystem)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        val tabs = listOf(
            stringResource(R.string.tab_packages),
            stringResource(R.string.tab_processes),
            stringResource(R.string.tab_services),
            stringResource(R.string.tab_findings),
            stringResource(R.string.tab_overlay)
        )
        TabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(title) }
                )
            }
        }

        when (tab) {
            0 -> PackagesTab(packages, runId, query, includeSystem, { query = it }, { includeSystem = it }, onOpenPackage)
            1 -> ProcessesTab(processes)
            2 -> ServicesTab(services)
            3 -> FindingsTab(findings, onOpenPackage)
            4 -> OverlayTab(container, onOpenPackage)
        }
    }
}

@Composable
private fun PackagesTab(
    packages: List<com.camillanapoles.droidauditor.domain.PackageRow>,
    runId: Long,
    query: String,
    includeSystem: Boolean,
    onQueryChange: (String) -> Unit,
    onIncludeSystemChange: (Boolean) -> Unit,
    onOpenPackage: (Long, String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text(stringResource(R.string.search_packages)) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = includeSystem,
                onClick = { onIncludeSystemChange(!includeSystem) },
                label = { Text(stringResource(R.string.filter_system)) }
            )
        }
        if (packages.isEmpty()) {
            EmptyState(text = stringResource(R.string.empty_list))
            return
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(packages, key = { it.packageName }) { pkg ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { if (runId > 0) onOpenPackage(runId, pkg.packageName) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = pkg.packageName,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(R.string.pkg_uid) + " ${pkg.uid}  ·  " +
                                stringResource(R.string.pkg_version) + " ${pkg.versionName}  ·  " +
                                stringResource(R.string.pkg_perms) + " ${pkg.permissionCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (pkg.isSystem) {
                        Badge(text = stringResource(R.string.system_flag), color = InfoBlue)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProcessesTab(processes: List<com.camillanapoles.droidauditor.domain.ProcessRow>) {
    if (processes.isEmpty()) {
        EmptyState(text = stringResource(R.string.audit_idle_hint))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(processes, key = { it.pid }) { proc ->
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(
                    text = proc.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.proc_pid) + " ${proc.pid}  ·  " +
                        stringResource(R.string.proc_uid) + " ${proc.uid}  ·  " +
                        stringResource(R.string.proc_rss) + " " + formatBytes(proc.rssKb * 1024L) + "  ·  " +
                        stringResource(R.string.proc_oom) + " " +
                        (proc.oomAdj?.toString() ?: stringResource(R.string.unknown_value)) +
                        "  ·  " + stringResource(R.string.proc_state) + " ${proc.state}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (proc.packageName != null) {
                        Text(
                            text = proc.packageName,
                            style = MaterialTheme.typography.labelSmall,
                            color = InfoBlue
                        )
                    }
                    if (proc.isCached) {
                        Badge(text = stringResource(R.string.proc_cached), color = OkGreen)
                    }
                    if (proc.hasServices) {
                        Badge(text = stringResource(R.string.proc_has_services), color = InfoBlue)
                    }
                    if (proc.hasForeground) {
                        Badge(text = stringResource(R.string.proc_has_fg), color = OkGreen)
                    }
                }
            }
        }
    }
}

@Composable
private fun ServicesTab(services: List<com.camillanapoles.droidauditor.domain.ServiceRow>) {
    if (services.isEmpty()) {
        EmptyState(text = stringResource(R.string.audit_idle_hint))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(
            services,
            key = { "${it.packageName}/${it.serviceClass}" }
        ) { svc ->
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(
                    text = svc.serviceClass,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = svc.packageName +
                        (svc.processName?.let { "  ·  $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!svc.clientPackages.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.edges_in) + ": " + svc.clientPackages,
                        style = MaterialTheme.typography.labelSmall,
                        color = InfoBlue
                    )
                }
            }
        }
    }
}

@Composable
private fun FindingsTab(
    findings: List<com.camillanapoles.droidauditor.domain.Finding>,
    onOpenPackage: (Long, String) -> Unit
) {
    if (findings.isEmpty()) {
        EmptyState(text = stringResource(R.string.no_findings))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(findings, key = { it.id }) { finding ->
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SeverityChip(severity = finding.severity)
                    Text(
                        text = finding.source,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = finding.message,
                    style = MaterialTheme.typography.bodySmall
                )
                val pkg = finding.relatedPackage
                if (pkg != null) {
                    TextButton(onClick = { onOpenPackage(finding.runId, pkg) }) {
                        Text(text = pkg, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
