package com.scanx.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.produceState
import com.scanx.app.R
import com.scanx.app.data.DocumentMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun TrashScreen(
    trashedDocuments: List<DocumentMeta>,
    getThumbnailFile: (String) -> File,
    onBack: () -> Unit,
    onRestore: (String) -> Unit,
    onDeleteForever: (String) -> Unit,
    onEmptyTrash: () -> Unit,
) {
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var showEmptyAllConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trash_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
                },
                actions = {
                    if (trashedDocuments.isNotEmpty()) {
                        IconButton(onClick = { showEmptyAllConfirm = true }) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource(R.string.trash_empty_all))
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (trashedDocuments.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.trash_empty_state),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(
                        stringResource(R.string.trash_auto_purge_notice),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
                LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(trashedDocuments, key = { it.id }) { doc ->
                        TrashRow(
                            doc = doc,
                            thumbnailFile = getThumbnailFile(doc.id),
                            onRestore = { onRestore(doc.id) },
                            onDeleteForever = { pendingDeleteId = doc.id },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    pendingDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text(stringResource(R.string.trash_delete_forever_confirm_title)) },
            text = { Text(stringResource(R.string.trash_delete_forever_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { onDeleteForever(id); pendingDeleteId = null }) {
                    Text(stringResource(R.string.trash_delete_forever))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    if (showEmptyAllConfirm) {
        AlertDialog(
            onDismissRequest = { showEmptyAllConfirm = false },
            title = { Text(stringResource(R.string.trash_empty_all_confirm_title)) },
            text = { Text(stringResource(R.string.trash_empty_all_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { onEmptyTrash(); showEmptyAllConfirm = false }) {
                    Text(stringResource(R.string.trash_empty_all))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyAllConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

@Composable
private fun TrashRow(
    doc: DocumentMeta,
    thumbnailFile: File,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    val bitmapState = produceState<Bitmap?>(initialValue = null, doc.id) {
        value = withContext(Dispatchers.IO) {
            runCatching { if (thumbnailFile.exists()) BitmapFactory.decodeFile(thumbnailFile.path) else null }.getOrNull()
        }
    }
    val daysLeft = remember(doc.trashedAtEpochMillis) {
        val trashedAt = doc.trashedAtEpochMillis ?: System.currentTimeMillis()
        val elapsedDays = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - trashedAt)
        (30 - elapsedDays).coerceAtLeast(0)
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = bitmapState.value
            if (bmp != null) {
                Image(bitmap = bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Filled.Description, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(doc.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.trash_days_left, daysLeft),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRestore) {
            Icon(Icons.Filled.Restore, contentDescription = stringResource(R.string.trash_restore))
        }
        IconButton(onClick = onDeleteForever) {
            Icon(Icons.Filled.DeleteForever, contentDescription = stringResource(R.string.trash_delete_forever))
        }
    }
}
