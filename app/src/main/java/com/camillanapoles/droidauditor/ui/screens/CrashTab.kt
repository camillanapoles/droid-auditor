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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.camillanapoles.droidauditor.R
import com.camillanapoles.droidauditor.di.AppContainer
import com.camillanapoles.droidauditor.domain.CrashEventRow
import com.camillanapoles.droidauditor.ui.components.Badge
import com.camillanapoles.droidauditor.ui.components.EmptyState
import com.camillanapoles.droidauditor.ui.components.formatTime
import com.camillanapoles.droidauditor.ui.vm.CrashViewModel
import com.camillanapoles.droidauditor.ui.vm.VmFactory
import com.camillanapoles.droidauditor.ui.theme.CriticalRed
import com.camillanapoles.droidauditor.ui.theme.HighOrange
import com.camillanapoles.droidauditor.ui.theme.OkGreen

/** Crash/ANR events with resolutive diagnosis and one-tap fix actions. */
@Composable
fun CrashTab(container: AppContainer, onOpenPackage: (Long, String) -> Unit) {
    val vm: CrashViewModel = viewModel(factory = remember { VmFactory(container) })
    val ui by vm.ui.collectAsState()
    var confirmEvent by remember { mutableStateOf<CrashEventRow?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.crash_summary, ui.events.count { it.kind == "CRASH" }, ui.events.count { it.kind == "ANR" }),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            if (ui.busy) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp))
            } else {
                TextButton(onClick = { vm.rescan() }) {
                    Text(stringResource(R.string.crash_rescan))
                }
            }
        }

        ui.result?.let { result ->
            Text(
                text = result,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }

        if (ui.loaded && ui.events.isEmpty()) {
            EmptyState(text = stringResource(R.string.crash_none))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                items(ui.events, key = { "${it.ts}_${it.packageName}_${it.summary}" }) { ev ->
                    CrashEventCard(
                        ev = ev,
                        enabled = !ui.busy,
                        onOpen = { onOpenPackage(ui.runId, ev.packageName) },
                        onFix = { confirmEvent = ev }
                    )
                }
            }
        }
    }

    confirmEvent?.let { ev ->
        AlertDialog(
            onDismissRequest = { confirmEvent = null },
            title = { Text(stringResource(R.string.confirm_title)) },
            text = { Text(stringResource(R.string.crash_fix_confirm, ev.packageName) + "\n\n" + ev.command.orEmpty()) },
            confirmButton = {
                TextButton(onClick = {
                    vm.runResolution(ev)
                    confirmEvent = null
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmEvent = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun CrashEventCard(
    ev: CrashEventRow,
    enabled: Boolean,
    onOpen: () -> Unit,
    onFix: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .padding(vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Badge(
                text = ev.kind,
                color = if (ev.kind == "ANR") HighOrange else CriticalRed
            )
            Text(
                text = ev.packageName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (ev.command != null) {
                TextButton(onClick = onFix, enabled = enabled) {
                    Text(stringResource(R.string.crash_fix_action))
                }
            }
        }
        Text(
            text = formatTime(ev.ts) + "  ·  " + ev.summary,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = ev.diagnosisTitle,
            style = MaterialTheme.typography.bodyMedium,
            color = CriticalRed
        )
        Text(
            text = stringResource(R.string.crash_cause_label) + " " + ev.cause,
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = stringResource(R.string.crash_fix_label) + " " + ev.resolution,
            style = MaterialTheme.typography.bodySmall,
            color = OkGreen
        )
    }
}
