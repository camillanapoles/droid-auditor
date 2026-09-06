package com.camillanapoles.droidauditor.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.camillanapoles.droidauditor.R
import com.camillanapoles.droidauditor.di.AppContainer
import com.camillanapoles.droidauditor.domain.CommandRow
import com.camillanapoles.droidauditor.domain.Finding
import com.camillanapoles.droidauditor.domain.Sources
import com.camillanapoles.droidauditor.engine.ActionExecutor
import com.camillanapoles.droidauditor.ui.components.EmptyState
import com.camillanapoles.droidauditor.ui.components.SectionTitle
import com.camillanapoles.droidauditor.ui.components.SeverityChip
import com.camillanapoles.droidauditor.ui.components.formatBytes
import com.camillanapoles.droidauditor.ui.vm.OptimizeViewModel
import com.camillanapoles.droidauditor.ui.vm.VmFactory

private data class PendingAction(val label: String, val command: String, val pkg: String?)

/** Optimizer suggestions with destructive-action confirmation and execution. */
@Composable
fun OptimizeScreen(container: AppContainer) {
    val vm: OptimizeViewModel = viewModel(factory = remember { VmFactory(container) })
    val cacheFindings by vm.cacheFindings.collectAsState()
    val idleFindings by vm.idleFindings.collectAsState()
    val otherFindings by vm.otherFindings.collectAsState()
    val termuxBytes by vm.termuxBytes.collectAsState()
    val actions by vm.actions.collectAsState()
    val result by vm.result.collectAsState()
    val busy by vm.busy.collectAsState()
    var pending by remember { mutableStateOf<PendingAction?>(null) }

    val termuxAction: CommandRow? = actions["termux_cache_clean"]
    val trimAction: CommandRow? = actions["trim_caches"]

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    text = stringResource(R.string.nav_optimize),
                    style = MaterialTheme.typography.headlineSmall
                )
                TextButton(onClick = { vm.refresh() }) {
                    Text(stringResource(R.string.refresh))
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.termux_cache_size) + ": " + formatBytes(termuxBytes),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (termuxAction != null) {
                            Button(
                                enabled = !busy,
                                onClick = {
                                    pending = PendingAction(termuxAction.name, termuxAction.command, null)
                                }
                            ) {
                                Text(stringResource(R.string.action_termux_clean))
                            }
                        }
                        if (trimAction != null) {
                            OutlinedButton(
                                enabled = !busy,
                                onClick = {
                                    pending = PendingAction(trimAction.name, trimAction.command, null)
                                }
                            ) {
                                Text(stringResource(R.string.action_trim_caches))
                            }
                        }
                    }
                }
            }
        }
        item {
            SectionTitle(
                text = stringResource(R.string.suggestions),
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        val suggestions = cacheFindings + otherFindings
        if (suggestions.isEmpty() && idleFindings.isEmpty()) {
            item { EmptyState(text = stringResource(R.string.no_suggestions)) }
        }
        items(suggestions, key = { it.id }) { finding ->
            SuggestionCard(finding = finding, enabled = !busy, onAction = { label, command, pkg ->
                pending = PendingAction(label, command, pkg)
            })
        }
        if (idleFindings.isNotEmpty()) {
            item {
                SectionTitle(
                    text = stringResource(R.string.idle_section),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            items(idleFindings, key = { it.id }) { finding ->
                SuggestionCard(finding = finding, enabled = !busy, onAction = { label, command, pkg ->
                    pending = PendingAction(label, command, pkg)
                })
            }
        }
    }

    val action = pending
    if (action != null) {
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.confirm_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.confirm_danger))
                    Text(
                        text = action.command.take(400),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.executeRaw(action.label, action.command, action.pkg)
                    pending = null
                }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    val message = result
    if (message != null) {
        AlertDialog(
            onDismissRequest = { vm.clearResult() },
            title = { Text(stringResource(R.string.action_result)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { vm.clearResult() }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }
}

@Composable
private fun SuggestionCard(
    finding: Finding,
    enabled: Boolean,
    onAction: (label: String, command: String, pkg: String?) -> Unit
) {
    val clearLabel = stringResource(R.string.action_clear_cache)
    val stopLabel = stringResource(R.string.action_force_stop)
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (finding.source) {
                    Sources.OPTIMIZER_CACHE -> {
                        if (pkg != null) {
                            OutlinedButton(
                                enabled = enabled,
                                onClick = {
                                    onAction(
                                        clearLabel + " " + pkg,
                                        ActionExecutor.clearAppCacheCommand(pkg),
                                        pkg
                                    )
                                }
                            ) {
                                Text(clearLabel)
                            }
                            OutlinedButton(
                                enabled = enabled,
                                onClick = {
                                    onAction(
                                        stopLabel + " " + pkg,
                                        ActionExecutor.forceStopCommand(pkg),
                                        pkg
                                    )
                                }
                            ) {
                                Text(stopLabel)
                            }
                        }
                    }
                    Sources.OPTIMIZER_IDLE -> {
                        if (pkg != null) {
                            OutlinedButton(
                                enabled = enabled,
                                onClick = {
                                    onAction(
                                        stopLabel + " " + pkg,
                                        ActionExecutor.forceStopCommand(pkg),
                                        pkg
                                    )
                                }
                            ) {
                                Text(stopLabel)
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}
