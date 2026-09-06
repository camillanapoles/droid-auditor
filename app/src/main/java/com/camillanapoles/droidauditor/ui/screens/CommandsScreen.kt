package com.camillanapoles.droidauditor.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.camillanapoles.droidauditor.R
import com.camillanapoles.droidauditor.di.AppContainer
import com.camillanapoles.droidauditor.domain.CommandRow
import com.camillanapoles.droidauditor.domain.Kinds
import com.camillanapoles.droidauditor.ui.components.Badge
import com.camillanapoles.droidauditor.ui.components.EmptyState
import com.camillanapoles.droidauditor.ui.components.SectionTitle
import com.camillanapoles.droidauditor.ui.theme.CriticalRed
import com.camillanapoles.droidauditor.ui.theme.InfoBlue
import com.camillanapoles.droidauditor.ui.vm.CommandsViewModel
import com.camillanapoles.droidauditor.ui.vm.VmFactory

private val KIND_VALUES = listOf(Kinds.SCRIPT, Kinds.SHELL, Kinds.COLLECTOR, Kinds.ACTION)

/**
 * Catalog CRUD: the device-local AdbCommands.md surface. Every category,
 * command, parser and flag is editable here and stored in the DB.
 */
@Composable
fun CommandsScreen(container: AppContainer, onOpenOutput: (String) -> Unit) {
    val vm: CommandsViewModel = viewModel(factory = remember { VmFactory(container) })
    val commands by vm.commands.collectAsState()
    val adhoc by vm.adhoc.collectAsState()
    val busy by vm.busy.collectAsState()
    val context = LocalContext.current

    var dialogOpen by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<CommandRow?>(null) }
    var deleteTarget by remember { mutableStateOf<CommandRow?>(null) }

    LaunchedEffect(adhoc) {
        val result = adhoc
        if (result != null) {
            Toast.makeText(
                context,
                context.getString(R.string.run_finished, result.second),
                Toast.LENGTH_SHORT
            ).show()
            onOpenOutput(result.first)
            vm.clearAdhoc()
        }
    }

    val categoryDescriptions = remember(vm.categories) {
        vm.categories.associate { it.name to it.description }
    }
    val categoryNames = remember(vm.categories) { vm.categories.map { it.name } }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.commands_title),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    TextButton(onClick = { vm.refresh() }) {
                        Text(stringResource(R.string.refresh))
                    }
                }
            }
            if (commands.isEmpty()) {
                item { EmptyState(text = stringResource(R.string.no_commands)) }
            }
            val grouped = commands.groupBy { it.category }.toSortedMap()
            for ((category, rows) in grouped) {
                item(key = "header_$category") {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        SectionTitle(text = category)
                        val description = categoryDescriptions[category]
                        if (!description.isNullOrBlank()) {
                            Text(
                                text = description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                items(rows, key = { it.id }) { row ->
                    CommandRowItem(
                        row = row,
                        enabled = !busy,
                        onToggle = { vm.setEnabled(row.id, it) },
                        onEdit = {
                            editTarget = row
                            dialogOpen = true
                        },
                        onDelete = { deleteTarget = row },
                        onRun = { vm.runNow(row) }
                    )
                }
            }
        }
        FloatingActionButton(
            onClick = {
                editTarget = null
                dialogOpen = true
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = stringResource(R.string.add_command)
            )
        }
    }

    if (dialogOpen) {
        CommandEditDialog(
            initial = editTarget,
            categoryNames = categoryNames,
            onDismiss = { dialogOpen = false },
            onSave = { row ->
                vm.save(row, editTarget == null)
                dialogOpen = false
            }
        )
    }

    val target = deleteTarget
    if (target != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_confirm)) },
            text = { Text(target.name) },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(target.id)
                    deleteTarget = null
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun CommandRowItem(
    row: CommandRow,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRun: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() }
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = row.command,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(checked = row.enabled, onCheckedChange = onToggle)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Badge(text = kindLabel(row.kind), color = InfoBlue)
            if (row.requiresRoot) {
                Badge(text = stringResource(R.string.requires_root_flag), color = CriticalRed)
            }
            if (row.danger) {
                Badge(text = stringResource(R.string.danger_flag), color = CriticalRed)
            }
            if (!row.enabled) {
                Badge(
                    text = stringResource(R.string.disabled_flag),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (row.kind == Kinds.SHELL) {
                IconButton(onClick = onRun, enabled = enabled) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.run_now_label),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.delete),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun kindLabel(kind: String): String = when (kind) {
    Kinds.SCRIPT -> stringResource(R.string.kind_script)
    Kinds.SHELL -> stringResource(R.string.kind_shell)
    Kinds.COLLECTOR -> stringResource(R.string.kind_collector)
    else -> stringResource(R.string.kind_action)
}

@Composable
private fun CommandEditDialog(
    initial: CommandRow?,
    categoryNames: List<String>,
    onDismiss: () -> Unit,
    onSave: (CommandRow) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var category by remember { mutableStateOf(initial?.category ?: "") }
    var kind by remember { mutableStateOf(initial?.kind ?: Kinds.SHELL) }
    var command by remember { mutableStateOf(initial?.command ?: "") }
    var parser by remember { mutableStateOf(initial?.parser ?: "raw") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var requiresRoot by remember { mutableStateOf(initial?.requiresRoot ?: false) }
    var danger by remember { mutableStateOf(initial?.danger ?: false) }
    var enabledFlag by remember { mutableStateOf(initial?.enabled ?: true) }
    var error by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (initial == null) R.string.add_command else R.string.edit_command))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.field_name)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text(stringResource(R.string.field_category)) },
                    singleLine = true
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    categoryNames.forEach { candidate ->
                        FilterChip(
                            selected = category == candidate,
                            onClick = { category = candidate },
                            label = { Text(candidate, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KIND_VALUES.forEach { candidate ->
                        FilterChip(
                            selected = kind == candidate,
                            onClick = { kind = candidate },
                            label = { Text(kindLabel(candidate), style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text(stringResource(R.string.field_command)) }
                )
                OutlinedTextField(
                    value = parser,
                    onValueChange = { parser = it },
                    label = { Text(stringResource(R.string.field_parser)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.field_description)) }
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(R.string.flag_requires_root),
                            style = MaterialTheme.typography.labelSmall
                        )
                        Switch(checked = requiresRoot, onCheckedChange = { requiresRoot = it })
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(R.string.danger_flag),
                            style = MaterialTheme.typography.labelSmall
                        )
                        Switch(checked = danger, onCheckedChange = { danger = it })
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(R.string.enabled_flag),
                            style = MaterialTheme.typography.labelSmall
                        )
                        Switch(checked = enabledFlag, onCheckedChange = { enabledFlag = it })
                    }
                }
                Text(
                    text = stringResource(R.string.hint_placeholders),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (error) {
                    Text(
                        text = stringResource(R.string.bad_input),
                        color = CriticalRed,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank() || category.isBlank() || command.isBlank()) {
                    error = true
                } else {
                    onSave(
                        CommandRow(
                            id = initial?.id ?: 0L,
                            name = name.trim(),
                            category = category.trim(),
                            kind = kind,
                            command = command.trim(),
                            requiresRoot = requiresRoot,
                            parser = parser.trim().ifEmpty { "raw" },
                            enabled = enabledFlag,
                            danger = danger,
                            description = description.trim(),
                            source = initial?.source ?: "user",
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}
