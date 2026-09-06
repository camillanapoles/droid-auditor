package com.camillanapoles.droidauditor.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.camillanapoles.droidauditor.R
import com.camillanapoles.droidauditor.di.AppContainer
import com.camillanapoles.droidauditor.ui.components.EmptyState
import com.camillanapoles.droidauditor.ui.components.SectionTitle
import com.camillanapoles.droidauditor.ui.components.formatDate
import com.camillanapoles.droidauditor.ui.components.formatTime
import com.camillanapoles.droidauditor.ui.vm.TimelineViewModel
import com.camillanapoles.droidauditor.ui.vm.VmFactory
import com.camillanapoles.droidauditor.ui.theme.InfoBlue

/** Install order (ordem de surgimento) + launch graph (quem chamou quem). */
@Composable
fun TimelineScreen(container: AppContainer) {
    val vm: TimelineViewModel = viewModel(factory = remember { VmFactory(container) })
    val timeline by vm.timeline.collectAsState()
    val launches by vm.launches.collectAsState()

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    text = stringResource(R.string.nav_timeline),
                    style = MaterialTheme.typography.headlineSmall
                )
                TextButton(onClick = { vm.refresh() }) {
                    Text(stringResource(R.string.refresh))
                }
            }
        }
        item {
            SectionTitle(
                text = stringResource(R.string.install_order),
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        if (timeline.isEmpty()) {
            item { EmptyState(text = stringResource(R.string.no_timeline)) }
        } else {
            itemsIndexed(timeline, key = { _, row -> row.packageName }) { index, row ->
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(
                        text = "#${index + 1}  ${formatDate(row.firstInstallTime)}  ${row.packageName}",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.installed_by) + " " +
                            (row.installerPkg ?: stringResource(R.string.unknown_installer)) +
                            "  ·  " + stringResource(R.string.pkg_updated) + " " + formatDate(row.lastUpdateTime),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item {
            SectionTitle(
                text = stringResource(R.string.launch_graph),
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        if (launches.isEmpty()) {
            item { EmptyState(text = stringResource(R.string.no_launches)) }
        } else {
            items(launches) { event ->
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(
                        text = (event.fromPackage ?: "?") + " → " + (event.toPackage ?: "?") +
                            " (" + (event.activity ?: "") + ", " + formatTime(event.ts) + ")",
                        style = MaterialTheme.typography.bodySmall,
                        color = InfoBlue
                    )
                    val evidence = event.evidence
                    if (!evidence.isNullOrBlank()) {
                        Text(
                            text = evidence,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
