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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.camillanapoles.droidauditor.R
import com.camillanapoles.droidauditor.di.AppContainer
import com.camillanapoles.droidauditor.domain.OverlayStateRow
import com.camillanapoles.droidauditor.ui.components.Badge
import com.camillanapoles.droidauditor.ui.components.EmptyState
import com.camillanapoles.droidauditor.ui.vm.OverlayViewModel
import com.camillanapoles.droidauditor.ui.vm.VmFactory
import com.camillanapoles.droidauditor.ui.theme.CriticalRed
import com.camillanapoles.droidauditor.ui.theme.HighOrange
import com.camillanapoles.droidauditor.ui.theme.InfoBlue

/** "Qual app esta em sobreposicao?": permission + active overlay windows. */
@Composable
fun OverlayTab(container: AppContainer, onOpenPackage: (Long, String) -> Unit) {
    val vm: OverlayViewModel = viewModel(factory = remember { VmFactory(container) })
    val ui by vm.ui.collectAsState()
    var confirmPkg by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            val active = ui.rows.count { it.activeWindows > 0 }
            val allowed = ui.rows.count { it.overlayAllowed }
            Text(
                text = stringResource(R.string.overlay_summary, allowed, active),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            if (ui.busy) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp))
            } else {
                TextButton(onClick = { vm.rescan() }) {
                    Text(stringResource(R.string.overlay_rescan))
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

        if (ui.loaded && ui.rows.isEmpty()) {
            EmptyState(text = stringResource(R.string.overlay_none))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                items(ui.rows, key = { it.packageName }) { row ->
                    OverlayRow(
                        row = row,
                        enabled = !ui.busy,
                        onOpen = { onOpenPackage(ui.runId, row.packageName) },
                        onRevoke = { confirmPkg = row.packageName }
                    )
                }
            }
        }
    }

    confirmPkg?.let { pkg ->
        AlertDialog(
            onDismissRequest = { confirmPkg = null },
            title = { Text(stringResource(R.string.confirm_title)) },
            text = { Text(stringResource(R.string.overlay_revoke_confirm, pkg)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.revoke(pkg)
                    confirmPkg = null
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmPkg = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun OverlayRow(
    row: OverlayStateRow,
    enabled: Boolean,
    onOpen: () -> Unit,
    onRevoke: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .padding(vertical = 6.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.packageName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (row.activeWindows > 0) {
                    Badge(
                        text = stringResource(R.string.overlay_active_badge, row.activeWindows),
                        color = CriticalRed
                    )
                }
                if (row.overlayAllowed) {
                    Badge(text = stringResource(R.string.overlay_allowed_badge), color = HighOrange)
                }
                if (row.isSystem) {
                    Badge(text = stringResource(R.string.system_flag), color = InfoBlue)
                }
                Text(
                    text = row.appopsMode,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (row.overlayAllowed && !row.isSystem) {
            TextButton(onClick = onRevoke, enabled = enabled) {
                Text(stringResource(R.string.overlay_action_revoke))
            }
        }
    }
}
