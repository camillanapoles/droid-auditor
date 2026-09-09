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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.camillanapoles.droidauditor.R
import com.camillanapoles.droidauditor.di.AppContainer
import com.camillanapoles.droidauditor.domain.AuditUiState
import com.camillanapoles.droidauditor.domain.CmdProgress
import com.camillanapoles.droidauditor.domain.Finding
import com.camillanapoles.droidauditor.domain.OutputRow
import com.camillanapoles.droidauditor.domain.Phase
import com.camillanapoles.droidauditor.ui.components.EmptyState
import com.camillanapoles.droidauditor.ui.components.SeverityChip
import com.camillanapoles.droidauditor.ui.components.StatusGlyph
import com.camillanapoles.droidauditor.ui.components.formatBytes
import com.camillanapoles.droidauditor.ui.vm.AuditViewModel
import com.camillanapoles.droidauditor.ui.vm.VmFactory

private val SEVERITIES = listOf("ALL", "CRITICAL", "HIGH", "MEDIUM", "INFO")

@Composable
fun AuditScreen(
    container: AppContainer,
    onOpenOutput: (String) -> Unit,
    onOpenPackage: (Long, String) -> Unit
) {
    val vm: AuditViewModel = viewModel(factory = remember { VmFactory(container) })
    val state by vm.engineState.collectAsState()
    val outputs by vm.outputs.collectAsState()
    val findings by vm.findings.collectAsState()
    var tab by remember { mutableStateOf(0) }
    var severity by remember { mutableStateOf("ALL") }

    LaunchedEffect(state) {
        if (state is AuditUiState.Finished) vm.refresh()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        when (val s = state) {
            is AuditUiState.Running -> {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = phaseLabel(s.phase),
                        style = MaterialTheme.typography.titleSmall
                    )
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                }
            }
            is AuditUiState.Finished -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(if (s.cancelled) R.string.phase_cancelled else R.string.phase_done) +
                            "  •  OK ${s.okCount}  •  FAIL ${s.failCount}  •  " +
                            stringResource(R.string.findings_label) + " ${s.findingsCount}",
                        style = MaterialTheme.typography.titleSmall
                    )
                    TextButton(onClick = { vm.refresh() }) {
                        Text(stringResource(R.string.refresh))
                    }
                }
            }
            else -> Unit
        }

        val tabs = listOf(
            stringResource(R.string.tab_progress),
            stringResource(R.string.tab_outputs),
            stringResource(R.string.tab_findings),
            stringResource(R.string.tab_diagnosis)
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
            0 -> ProgressTab(state)
            1 -> OutputsTab(outputs, onOpenOutput)
            2 -> FindingsTab(findings, severity, { severity = it }, onOpenPackage)
            3 -> CrashTab(container, onOpenPackage)
        }
    }
}

@Composable
private fun ProgressTab(state: AuditUiState) {
    val running = state as? AuditUiState.Running
    if (running == null) {
        EmptyState(text = stringResource(R.string.audit_idle_hint))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(running.items, key = { it.commandId }) { item ->
            ProgressRow(item)
        }
    }
}

@Composable
private fun ProgressRow(item: CmdProgress) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusGlyph(status = item.status)
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = buildString {
                    append(item.kind)
                    append(" · ")
                    append(statusLabel(item.status))
                    if (item.durationMs > 0) append(" · ${item.durationMs}ms")
                    if (item.detail.isNotEmpty()) append(" · ${item.detail}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun OutputsTab(outputs: List<OutputRow>, onOpenOutput: (String) -> Unit) {
    if (outputs.isEmpty()) {
        EmptyState(text = stringResource(R.string.no_outputs))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(outputs, key = { it.id }) { output ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { output.stdoutPath?.let { onOpenOutput(it) } }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.padding(end = 8.dp).weight(1f)) {
                    Text(
                        text = output.commandName,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.exit_code_label) + " ${output.exitCode}  ·  " +
                            "${output.durationMs}" + stringResource(R.string.duration_label) + "  ·  " +
                            formatBytes(output.stdoutLen) + "  ·  " +
                            stringResource(R.string.findings_label) + " ${output.findingsCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = output.findingsCount.toString(),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

@Composable
private fun FindingsTab(
    findings: List<Finding>,
    severity: String,
    onSeverityChange: (String) -> Unit,
    onOpenPackage: (Long, String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SEVERITIES.forEach { option ->
                FilterChip(
                    selected = severity == option,
                    onClick = { onSeverityChange(option) },
                    label = {
                        Text(if (option == "ALL") stringResource(R.string.severity_all) else option)
                    }
                )
            }
        }
        if (findings.isEmpty()) {
            EmptyState(text = stringResource(R.string.no_findings))
            return
        }
        val filtered = if (severity == "ALL") findings else findings.filter { it.severity == severity }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(filtered, key = { it.id }) { finding ->
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SeverityChip(severity = finding.severity)
                        Text(
                            text = finding.source,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = finding.message,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp)
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
}

@Composable
private fun phaseLabel(phase: Phase): String = when (phase) {
    Phase.COLLECT -> stringResource(R.string.phase_collect)
    Phase.PARSE -> stringResource(R.string.phase_parse)
    Phase.CORRELATE -> stringResource(R.string.phase_correlate)
    Phase.OPTIMIZE -> stringResource(R.string.phase_optimize)
}

@Composable
private fun statusLabel(status: String): String = when (status) {
    com.camillanapoles.droidauditor.domain.ProgressStatus.PENDING -> stringResource(R.string.status_pending)
    com.camillanapoles.droidauditor.domain.ProgressStatus.RUNNING -> stringResource(R.string.status_running)
    com.camillanapoles.droidauditor.domain.ProgressStatus.OK -> stringResource(R.string.status_ok)
    com.camillanapoles.droidauditor.domain.ProgressStatus.FAIL -> stringResource(R.string.status_fail)
    else -> stringResource(R.string.status_skip)
}
