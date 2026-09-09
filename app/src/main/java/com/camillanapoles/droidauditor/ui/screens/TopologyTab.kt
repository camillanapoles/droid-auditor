package com.camillanapoles.droidauditor.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.camillanapoles.droidauditor.R
import com.camillanapoles.droidauditor.di.AppContainer
import com.camillanapoles.droidauditor.domain.TopologyApp
import com.camillanapoles.droidauditor.domain.TopologyCategory
import com.camillanapoles.droidauditor.domain.TopologySort
import com.camillanapoles.droidauditor.domain.TopologyTypeSection
import com.camillanapoles.droidauditor.ui.components.Badge
import com.camillanapoles.droidauditor.ui.components.EmptyState
import com.camillanapoles.droidauditor.ui.components.formatBytes
import com.camillanapoles.droidauditor.ui.vm.TopologyViewModel
import com.camillanapoles.droidauditor.ui.vm.VmFactory
import com.camillanapoles.droidauditor.ui.theme.CriticalRed
import com.camillanapoles.droidauditor.ui.theme.HighOrange
import com.camillanapoles.droidauditor.ui.theme.InfoBlue
import com.camillanapoles.droidauditor.ui.theme.MediumAmber
import com.camillanapoles.droidauditor.ui.theme.OkGreen

/**
 * Categorical topology tree: Tipo [Usuário/Sistema] -> Categoria funcional ->
 * apps por prioridade (oom adj), impacto e ordem de surgimento.
 */
@Composable
fun TopologyTab(container: AppContainer, onOpenPackage: (Long, String) -> Unit) {
    val vm: TopologyViewModel = viewModel(factory = remember { VmFactory(container) })
    val ui by vm.ui.collectAsState()
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    Column(modifier = Modifier.fillMaxSize()) {
        // Sort mode
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            FilterChip(
                selected = ui.sort == TopologySort.IMPACT,
                onClick = { vm.setSort(TopologySort.IMPACT) },
                label = { Text(stringResource(R.string.topo_sort_impact)) }
            )
            FilterChip(
                selected = ui.sort == TopologySort.PRIORITY,
                onClick = { vm.setSort(TopologySort.PRIORITY) },
                label = { Text(stringResource(R.string.topo_sort_priority)) }
            )
            FilterChip(
                selected = ui.sort == TopologySort.ORDER,
                onClick = { vm.setSort(TopologySort.ORDER) },
                label = { Text(stringResource(R.string.topo_sort_order)) }
            )
            FilterChip(
                selected = ui.sort == TopologySort.NAME,
                onClick = { vm.setSort(TopologySort.NAME) },
                label = { Text(stringResource(R.string.topo_sort_name)) }
            )
        }
        // Type filter
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            FilterChip(
                selected = ui.typeFilter == 0,
                onClick = { vm.setTypeFilter(0) },
                label = { Text(stringResource(R.string.topo_type_all)) }
            )
            FilterChip(
                selected = ui.typeFilter == 1,
                onClick = { vm.setTypeFilter(1) },
                label = { Text(stringResource(R.string.topo_type_user)) }
            )
            FilterChip(
                selected = ui.typeFilter == 2,
                onClick = { vm.setTypeFilter(2) },
                label = { Text(stringResource(R.string.topo_type_system)) }
            )
        }
        OutlinedTextField(
            value = ui.query,
            onValueChange = { vm.setQuery(it) },
            placeholder = { Text(stringResource(R.string.topo_search)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        )

        if (!ui.loaded || ui.sections.all { it.apps.isEmpty() }) {
            EmptyState(text = stringResource(R.string.audit_idle_hint))
        } else {
            val visible = ui.sections.filter { section ->
                (ui.typeFilter == 0) ||
                    (ui.typeFilter == 1 && !section.isSystem) ||
                    (ui.typeFilter == 2 && section.isSystem)
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                visible.forEach { section ->
                    item(key = "section_${section.isSystem}") {
                        SectionHeader(section)
                    }
                    val categories = filteredCategories(section, ui.query, ui.sort)
                    categories.forEach { cat ->
                        val key = "${section.isSystem}_${cat.name}"
                        // Auto-expands high-impact categories on first render.
                        if (!expanded.contains(key)) expanded[key] = cat.maxImpact >= 50
                        val isOpen = expanded[key] ?: false
                        item(key = "cat_$key") {
                            CategoryHeader(
                                category = cat,
                                isOpen = isOpen,
                                onToggle = { expanded[key] = !isOpen }
                            )
                        }
                        if (isOpen) {
                            items(
                                sortedApps(cat, ui.sort),
                                key = { "app_${section.isSystem}_${it.packageName}" }
                            ) { app ->
                                AppRow(app, ui.runId, onOpenPackage)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(section: TopologyTypeSection) {
    Text(
        text = (if (section.isSystem) {
            stringResource(R.string.topo_section_system)
        } else {
            stringResource(R.string.topo_section_user)
        }) + "  ·  ${section.apps.size} " + stringResource(R.string.topo_apps_count),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun CategoryHeader(
    category: TopologyCategory,
    isOpen: Boolean,
    onToggle: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .animateContentSize()
            .padding(vertical = 8.dp)
    ) {
        Icon(
            imageVector = if (isOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = category.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "${category.apps.size} " + stringResource(R.string.topo_apps_count) +
                    "  ·  " + stringResource(R.string.proc_rss) + " " + formatBytes(category.totalRssKb * 1024L) +
                    "  ·  " + stringResource(R.string.topo_cache) + " " + formatBytes(category.totalCacheBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
        Badge(
            text = stringResource(R.string.topo_impact) + " " + category.maxImpact,
            color = impactColor(category.maxImpact)
        )
    }
}

@Composable
private fun AppRow(
    app: TopologyApp,
    runId: Long,
    onOpenPackage: (Long, String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenPackage(runId, app.packageName) }
            .padding(start = 24.dp, top = 6.dp, bottom = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Badge(
                text = if (app.priority >= 0) "P${app.priority}" else "P—",
                color = priorityColor(app.priority)
            )
            Badge(
                text = app.impactScore.toString(),
                color = impactColor(app.impactScore)
            )
            Text(
                text = "#${app.installOrder}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (app.isRunning) {
                Text(
                    text = stringResource(R.string.proc_rss) + " " + formatBytes(app.rssKb * 1024L),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (app.isCachedIdle) {
                Badge(text = stringResource(R.string.topo_idle_badge), color = CriticalRed)
            }
            if (app.dangerousPerms > 0) {
                Text(
                    text = "${app.dangerousPerms} " + stringResource(R.string.topo_dangerous_perms),
                    style = MaterialTheme.typography.labelSmall,
                    color = HighOrange
                )
            }
        }
    }
}

private fun filteredCategories(
    section: TopologyTypeSection,
    query: String,
    sort: TopologySort
): List<TopologyCategory> {
    val q = query.trim()
    val cats = section.categories.mapNotNull { cat ->
        if (q.isEmpty()) cat
        else cat.copy(apps = cat.apps.filter { it.packageName.contains(q, ignoreCase = true) })
    }.filter { it.apps.isNotEmpty() }
    return when (sort) {
        TopologySort.IMPACT -> cats.sortedByDescending { it.maxImpact }
        TopologySort.PRIORITY -> cats.sortedByDescending { c ->
            c.apps.maxOfOrNull { it.priority } ?: -1
        }
        TopologySort.ORDER -> cats.sortedBy { c -> c.apps.minOfOrNull { it.installOrder } ?: Int.MAX_VALUE }
        TopologySort.NAME -> cats.sortedBy { it.name }
    }
}

private fun sortedApps(cat: TopologyCategory, sort: TopologySort): List<TopologyApp> = when (sort) {
    TopologySort.IMPACT -> cat.apps.sortedWith(
        compareByDescending<TopologyApp> { it.impactScore }.thenBy { it.packageName }
    )
    TopologySort.PRIORITY -> cat.apps.sortedWith(
        compareByDescending<TopologyApp> { it.priority }.thenByDescending { it.rssKb }
    )
    TopologySort.ORDER -> cat.apps.sortedBy { it.installOrder }
    TopologySort.NAME -> cat.apps.sortedBy { it.packageName }
}

private fun priorityColor(priority: Int) = when {
    priority >= 4 -> OkGreen
    priority == 3 -> InfoBlue
    priority == 2 -> MediumAmber
    priority >= 0 -> HighOrange      // cached/idle
    else -> MediumAmber.copy(alpha = 0.5f)
}

private fun impactColor(score: Int) = when {
    score >= 60 -> CriticalRed
    score >= 30 -> HighOrange
    else -> InfoBlue
}
