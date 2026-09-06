package com.camillanapoles.droidauditor.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camillanapoles.droidauditor.R
import com.camillanapoles.droidauditor.data.collect.ParseUtil
import com.camillanapoles.droidauditor.ui.components.EmptyState
import com.camillanapoles.droidauditor.ui.components.formatBytes
import com.camillanapoles.droidauditor.ui.theme.severityColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Monospace raw-output viewer with severity-colored lines. */
@Composable
fun OutputViewerScreen(path: String) {
    val decoded = remember(path) { Uri.decode(path) }
    var lines by remember { mutableStateOf<List<String>?>(null) }
    var truncated by remember { mutableStateOf(false) }

    LaunchedEffect(decoded) {
        withContext(Dispatchers.IO) {
            val file = File(decoded)
            if (file.exists() && file.isFile) {
                val read = file.useLines { it.take(MAX_LINES + 1).toList() }
                truncated = read.size > MAX_LINES
                lines = if (truncated) read.subList(0, MAX_LINES) else read
            } else {
                lines = null
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = File(decoded).name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1
            )
            Text(
                text = stringResource(R.string.lines_label) + " ${lines?.size ?: 0}  ·  " +
                    formatBytes(File(decoded).length()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val content = lines
        if (content == null) {
            EmptyState(text = stringResource(R.string.output_not_found))
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (truncated) {
                    item {
                        Text(
                            text = stringResource(R.string.truncated_note, MAX_LINES),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
                items(content) { line ->
                    Text(
                        text = line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = lineColor(line),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun lineColor(line: String) =
    ParseUtil.severityOf(line)?.let { severityColor(it) }
        ?: MaterialTheme.colorScheme.onSurface

private const val MAX_LINES = 5000
