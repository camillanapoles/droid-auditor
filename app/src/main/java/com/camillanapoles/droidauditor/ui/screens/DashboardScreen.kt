package com.camillanapoles.droidauditor.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.camillanapoles.droidauditor.R
import com.camillanapoles.droidauditor.di.AppContainer
import com.camillanapoles.droidauditor.domain.AuditUiState
import com.camillanapoles.droidauditor.ui.components.KeyValueRow
import com.camillanapoles.droidauditor.ui.components.SectionTitle
import com.camillanapoles.droidauditor.ui.components.formatBytes
import com.camillanapoles.droidauditor.ui.components.formatTime
import com.camillanapoles.droidauditor.ui.vm.DashboardViewModel
import com.camillanapoles.droidauditor.ui.vm.VmFactory
import com.camillanapoles.droidauditor.ui.theme.OkGreen
import com.camillanapoles.droidauditor.ui.theme.CriticalRed

@Composable
fun DashboardScreen(container: AppContainer) {
    val vm: DashboardViewModel = viewModel(factory = remember { VmFactory(container) })
    val ui by vm.ui.collectAsState()
    val engineState by vm.engineState.collectAsState()

    LaunchedEffect(engineState) {
        if (engineState is AuditUiState.Finished) vm.refresh()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.nav_dashboard),
            style = MaterialTheme.typography.headlineSmall
        )

        // Root + usage-stats status
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.root_status),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(
                                if (ui.rootGranted) R.string.root_granted else R.string.root_denied
                            ),
                            color = if (ui.rootGranted) OkGreen else CriticalRed,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(onClick = { vm.recheckRoot() }) {
                            Text(stringResource(R.string.recheck))
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.usage_status),
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (ui.usageGranted) {
                        Text(
                            text = stringResource(R.string.usage_granted),
                            color = OkGreen,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Button(onClick = { vm.openUsageSettings() }) {
                            Text(stringResource(R.string.grant_usage))
                        }
                    }
                }
            }
        }

        // Device info
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = stringResource(R.string.device_info),
                    style = MaterialTheme.typography.titleSmall
                )
                KeyValueRow(label = stringResource(R.string.model_label), value = vm.deviceModel)
                KeyValueRow(label = stringResource(R.string.version_label), value = vm.androidVersion)
                KeyValueRow(label = stringResource(R.string.fingerprint_label), value = vm.fingerprint)
            }
        }

        // Storage / RAM quick stats
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = stringResource(R.string.storage_title),
                    style = MaterialTheme.typography.titleSmall
                )
                KeyValueRow(
                    label = stringResource(R.string.stat_total),
                    value = formatBytes(ui.dataTotalBytes)
                )
                KeyValueRow(
                    label = stringResource(R.string.stat_avail),
                    value = formatBytes(ui.dataAvailBytes)
                )
                SectionTitle(text = stringResource(R.string.ram_title))
                KeyValueRow(
                    label = stringResource(R.string.ram_total),
                    value = formatBytes(ui.ramTotalBytes)
                )
                KeyValueRow(
                    label = stringResource(R.string.ram_avail),
                    value = formatBytes(ui.ramAvailBytes)
                )
            }
        }

        // Last run summary
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = stringResource(R.string.last_run),
                    style = MaterialTheme.typography.titleSmall
                )
                val run = ui.lastRun
                if (run == null) {
                    Text(
                        text = stringResource(R.string.last_run_none),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    KeyValueRow(
                        label = stringResource(R.string.started_label),
                        value = formatTime(run.startedAt)
                    )
                    KeyValueRow(
                        label = stringResource(R.string.scripts_ok_label),
                        value = run.scriptsOk.toString()
                    )
                    KeyValueRow(
                        label = stringResource(R.string.scripts_fail_label),
                        value = run.scriptsFail.toString()
                    )
                    KeyValueRow(
                        label = stringResource(R.string.findings_label),
                        value = ui.findingsCount.toString()
                    )
                }
            }
        }

        // Audit controls
        val running = engineState is AuditUiState.Running
        if (running) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.audit_running),
                    style = MaterialTheme.typography.titleSmall
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { vm.cancelAudit() }) {
                    Text(stringResource(R.string.cancel_audit))
                }
            }
        } else {
            Button(onClick = { vm.startAudit() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.full_audit))
            }
            OutlinedButton(onClick = { vm.refresh() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.refresh))
            }
        }
    }
}
