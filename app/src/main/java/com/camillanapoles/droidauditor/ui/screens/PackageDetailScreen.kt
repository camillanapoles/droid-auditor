package com.camillanapoles.droidauditor.ui.screens
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.camillanapoles.droidauditor.ui.components.KeyValueRow
import com.camillanapoles.droidauditor.ui.components.SectionTitle
import com.camillanapoles.droidauditor.ui.components.formatBytes
import com.camillanapoles.droidauditor.ui.components.formatDate
import com.camillanapoles.droidauditor.ui.components.formatTime
import com.camillanapoles.droidauditor.ui.vm.ExplorerViewModel
import com.camillanapoles.droidauditor.ui.vm.VmFactory
import com.camillanapoles.droidauditor.ui.theme.CriticalRed
import com.camillanapoles.droidauditor.ui.theme.InfoBlue
import com.camillanapoles.droidauditor.ui.theme.OkGreen

/** Package detail: metadata, permissions, processes, services, activities, edges, last usage. */
@Composable
fun PackageDetailScreen(
    container: AppContainer,
    runId: Long,
    pkg: String,
    onBack: () -> Unit,
    onOpenPackage: (Long, String) -> Unit = { _, _ -> }
) {
    val vm: ExplorerViewModel = viewModel(factory = remember { VmFactory(container) })
    val detail by vm.detail.collectAsState()

    LaunchedEffect(runId, pkg) {
        vm.loadDetail(runId, pkg)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back)
                )
            }
            Text(
                text = pkg,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        val d = detail
        if (d == null) {
            EmptyState(text = stringResource(R.string.audit_idle_hint))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                item { DetailHeader(d) }
                item {
                    SectionTitle(text = stringResource(R.string.detail_processes))
                }
                items(d.processes, key = { it.pid }) { proc ->
                    DetailProcessRow(proc)
                }
                item {
                    SectionTitle(text = stringResource(R.string.detail_services))
                }
                items(
                    d.services,
                    key = { "${it.packageName}/${it.serviceClass}" }
                ) { svc ->
                    Text(
                        text = svc.serviceClass +
                            (svc.processName?.let { " ($it)" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
                item {
                    SectionTitle(text = stringResource(R.string.detail_activities))
                }
                items(
                    d.activities,
                    key = { "${it.packageName}/${it.activityClass}" }
                ) { act ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 1.dp)
                    ) {
                        Text(
                            text = act.activityClass,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (act.isResumed) {
                            Badge(text = stringResource(R.string.proc_has_fg), color = OkGreen)
                        }
                    }
                }
                item {
                    SectionTitle(text = stringResource(R.string.detail_permissions))
                }
                items(d.permissions, key = { it.permissionName }) { perm ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 1.dp)
                    ) {
                        Text(
                            text = perm.permissionName,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = perm.protectionLevel,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (perm.protectionLevel.startsWith("dangerous")) {
                                CriticalRed
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        if (perm.granted) {
                            Badge(text = stringResource(R.string.usage_granted), color = OkGreen)
                        }
                    }
                }
                item {
                    SectionTitle(text = stringResource(R.string.detail_edges))
                }
                items(d.edges) { edge ->
                    Column(modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(
                            text = (if (edge.direction == "out") {
                                stringResource(R.string.edges_out)
                            } else {
                                stringResource(R.string.edges_in)
                            }) + "  ${edge.label}  ${edge.otherLabel} (${edge.otherType})",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (!edge.evidence.isNullOrBlank()) {
                            Text(
                                text = edge.evidence,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                item {
                    SectionTitle(text = stringResource(R.string.detail_used_by))
                }
                if (d.usedBy.isEmpty()) {
                    item { Text(stringResource(R.string.used_by_empty), style = MaterialTheme.typography.bodySmall) }
                }
                d.usedBy.forEach { node ->
                    item(key = "ub_${node.ekey}") {
                        UsedByTree(
                            node = node,
                            depth = 0,
                            runId = runId,
                            onOpenPackage = onOpenPackage
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UsedByTree(
    node: com.camillanapoles.droidauditor.domain.UsedByNode,
    depth: Int,
    runId: Long,
    onOpenPackage: (Long, String) -> Unit
) {
    Column(modifier = Modifier.padding(start = (depth * 16).dp, top = 2.dp, bottom = 2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenPackage(runId, node.ekey) }
        ) {
            Text(
                text = "└ " + node.relationLabel + ":",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = node.elabel.ifBlank { node.ekey },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (node.truncated) {
                Badge(text = stringResource(R.string.used_by_repeated), color = InfoBlue)
            }
        }
        node.children.forEach { child ->
            UsedByTree(node = child, depth = depth + 1, runId = runId, onOpenPackage = onOpenPackage)
        }
    }
}

@Composable
private fun DetailHeader(d: ExplorerViewModel.PackageDetail) {
    val row = d.row
    Column(modifier = Modifier.fillMaxWidth()) {
        if (row != null) {
            KeyValueRow(label = stringResource(R.string.pkg_uid), value = row.uid.toString())
            KeyValueRow(label = stringResource(R.string.pkg_version), value = "${row.versionName} (${row.versionCode})")
            KeyValueRow(label = stringResource(R.string.pkg_installed), value = formatDate(row.firstInstallTime))
            KeyValueRow(label = stringResource(R.string.pkg_updated), value = formatDate(row.lastUpdateTime))
            KeyValueRow(
                label = stringResource(R.string.pkg_installer),
                value = row.installerPkg ?: stringResource(R.string.unknown_installer)
            )
            KeyValueRow(label = stringResource(R.string.pkg_target_sdk), value = row.targetSdk.toString())
            KeyValueRow(label = stringResource(R.string.pkg_perms), value = row.permissionCount.toString())
            KeyValueRow(label = stringResource(R.string.pkg_apk), value = row.apkPath)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (row.isSystem) {
                    Badge(text = stringResource(R.string.system_flag), color = InfoBlue)
                }
                Badge(
                    text = stringResource(
                        if (row.isEnabled) R.string.enabled_flag else R.string.disabled_flag
                    ),
                    color = if (row.isEnabled) OkGreen else CriticalRed
                )
            }
        }
        SectionTitle(text = stringResource(R.string.detail_usage))
        Text(
            text = d.lastUsage?.let { formatTime(it) } ?: stringResource(R.string.usage_unknown),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun DetailProcessRow(proc: com.camillanapoles.droidauditor.domain.ProcessRow) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = "${proc.name} (${proc.pid})",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = stringResource(R.string.proc_rss) + " " + formatBytes(proc.rssKb * 1024L) + "  ·  " +
                stringResource(R.string.proc_oom) + " " +
                (proc.oomAdj?.toString() ?: stringResource(R.string.unknown_value)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
