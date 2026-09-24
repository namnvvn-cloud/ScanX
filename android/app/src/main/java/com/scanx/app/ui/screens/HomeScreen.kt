package com.scanx.app.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.scanx.app.R
import com.scanx.app.data.DocumentMeta
import com.scanx.app.data.FolderMeta
import com.scanx.app.data.SortOrder
import com.scanx.app.data.ViewMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Màn hình chính "My Scans" — tham khảo bố cục Scanner Pro: thanh tìm kiếm, Bộ lọc thông minh
 * (khoá "Sắp ra mắt" ở Phase 1), danh sách thư mục, lưới/danh sách tài liệu, thanh dưới cùng gồm
 * Công cụ (4 ô vuông) / Camera (giữa, nổi bật) / Mở ảnh có sẵn.
 */
@Composable
fun HomeScreen(
    documents: List<DocumentMeta>,
    folders: List<FolderMeta>,
    currentFolderId: String?,
    searchQuery: String,
    sortOrder: SortOrder,
    viewMode: ViewMode,
    isProcessing: Boolean,
    getThumbnailFile: (String) -> File,
    onSearchQueryChange: (String) -> Unit,
    onOpenFolder: (String?) -> Unit,
    onCreateFolder: (String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onSortOrderChange: (SortOrder) -> Unit,
    onViewModeChange: (ViewMode) -> Unit,
    onDocumentClick: (DocumentMeta) -> Unit,
    onMoveDocumentsToFolder: (Set<String>, String?) -> Unit,
    onDeleteDocuments: (Set<String>) -> Unit,
    onSettingsClick: () -> Unit,
    onTrashClick: () -> Unit,
    onImportFilesClick: () -> Unit,
    /** Bản 1.0: nút camera mở 3 lựa chọn — Dịch / Scan tự động / Scan thủ công. */
    onScanAuto: () -> Unit,
    onScanManual: () -> Unit,
    onCameraTranslate: () -> Unit,
    onComingSoon: () -> Unit,
    onConvertFiles: () -> Unit = {},
    onTranslateFiles: () -> Unit = {},
) {
    var showCaptureSheet by remember { mutableStateOf(false) }
    var selectMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(setOf<String>()) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showToolsSheet by remember { mutableStateOf(false) }
    var showMoveSheet by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showDeleteFolderId by remember { mutableStateOf<String?>(null) }

    fun exitSelectMode() {
        selectMode = false
        selectedIds = emptySet()
    }

    fun toggleSelected(id: String) {
        selectedIds = if (selectedIds.contains(id)) selectedIds - id else selectedIds + id
    }

    Scaffold(
        topBar = {
            if (selectMode) {
                TopAppBar(
                    title = { Text(stringResource(R.string.select_mode_selected_count, selectedIds.size)) },
                    navigationIcon = {
                        IconButton(onClick = { exitSelectMode() }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
                        }
                    },
                    actions = {
                        IconButton(onClick = { if (selectedIds.isNotEmpty()) showMoveSheet = true }) {
                            Icon(Icons.Filled.DriveFolderUpload, contentDescription = stringResource(R.string.select_mode_move_to_folder))
                        }
                        IconButton(onClick = {
                            if (selectedIds.isNotEmpty()) {
                                onDeleteDocuments(selectedIds)
                                exitSelectMode()
                            }
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.home_title)) },
                    navigationIcon = {
                        IconButton(onClick = onSettingsClick) {
                            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.cd_settings))
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { showMoreMenu = true }) {
                                Icon(Icons.Filled.MoreHoriz, contentDescription = stringResource(R.string.cd_more_menu))
                            }
                            DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_select)) },
                                    onClick = { showMoreMenu = false; selectMode = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_new_folder)) },
                                    leadingIcon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
                                    onClick = { showMoreMenu = false; showNewFolderDialog = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_import_files)) },
                                    leadingIcon = { Icon(Icons.Filled.FileUpload, contentDescription = null) },
                                    onClick = { showMoreMenu = false; onImportFilesClick() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_expense_report)) },
                                    leadingIcon = { Icon(Icons.Filled.Receipt, contentDescription = null) },
                                    trailingIcon = { LockedBadge() },
                                    onClick = { showMoreMenu = false; onComingSoon() },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (viewMode == ViewMode.GRID) stringResource(R.string.menu_view_list)
                                            else stringResource(R.string.menu_view_grid)
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (viewMode == ViewMode.GRID) Icons.Filled.ViewList else Icons.Filled.GridView,
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        showMoreMenu = false
                                        onViewModeChange(if (viewMode == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_sort_by)) },
                                    leadingIcon = { Icon(Icons.Filled.Sort, contentDescription = null) },
                                    onClick = { showMoreMenu = false; showSortMenu = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_priority_support)) },
                                    leadingIcon = { Icon(Icons.Filled.SupportAgent, contentDescription = null) },
                                    trailingIcon = { LockedBadge() },
                                    onClick = { showMoreMenu = false; onComingSoon() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_trash_bin)) },
                                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                    onClick = { showMoreMenu = false; onTrashClick() },
                                )
                            }
                            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                                SortOptionItem(stringResource(R.string.sort_date_modified), sortOrder == SortOrder.DATE_MODIFIED) {
                                    showSortMenu = false; onSortOrderChange(SortOrder.DATE_MODIFIED)
                                }
                                SortOptionItem(stringResource(R.string.sort_date_created), sortOrder == SortOrder.DATE_CREATED) {
                                    showSortMenu = false; onSortOrderChange(SortOrder.DATE_CREATED)
                                }
                                SortOptionItem(stringResource(R.string.sort_name), sortOrder == SortOrder.NAME) {
                                    showSortMenu = false; onSortOrderChange(SortOrder.NAME)
                                }
                            }
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (!selectMode) {
                HomeBottomBar(
                    onToolsClick = { showToolsSheet = true },
                    onCameraClick = { showCaptureSheet = true },
                    onGalleryClick = onImportFilesClick,
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (!selectMode) {
                    SearchBar(query = searchQuery, onQueryChange = onSearchQueryChange)
                    SmartFiltersRow(onClick = onComingSoon)
                    FoldersRow(
                        folders = folders,
                        currentFolderId = currentFolderId,
                        onOpenFolder = onOpenFolder,
                        onNewFolder = { showNewFolderDialog = true },
                        onLongPressFolder = { showDeleteFolderId = it },
                    )
                }

                if (documents.isEmpty() && !isProcessing) {
                    EmptyState(hasQuery = searchQuery.isNotBlank(), hasFolder = currentFolderId != null)
                } else if (viewMode == ViewMode.GRID) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(documents, key = { it.id }) { doc ->
                            DocumentGridCard(
                                doc = doc,
                                thumbnailFile = getThumbnailFile(doc.id),
                                selectMode = selectMode,
                                selected = selectedIds.contains(doc.id),
                                onClick = {
                                    if (selectMode) toggleSelected(doc.id) else onDocumentClick(doc)
                                },
                                onLongClick = {
                                    if (!selectMode) { selectMode = true; toggleSelected(doc.id) }
                                },
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(vertical = 8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(documents, key = { it.id }) { doc ->
                            DocumentListRow(
                                doc = doc,
                                thumbnailFile = getThumbnailFile(doc.id),
                                selectMode = selectMode,
                                selected = selectedIds.contains(doc.id),
                                onClick = {
                                    if (selectMode) toggleSelected(doc.id) else onDocumentClick(doc)
                                },
                                onLongClick = {
                                    if (!selectMode) { selectMode = true; toggleSelected(doc.id) }
                                },
                            )
                        }
                    }
                }
            }

            if (isProcessing) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.7f)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.scanning_in_progress),
                            modifier = Modifier.padding(top = 16.dp)
                        )
                    }
                }
            }
        }
    }

    if (showToolsSheet) {
        ToolsBottomSheet(
            onDismiss = { showToolsSheet = false },
            onDocumentClick = { showToolsSheet = false; onScanAuto() },
            onConvertClick = { showToolsSheet = false; onConvertFiles() },
            onTranslateClick = { showToolsSheet = false; onTranslateFiles() },
            onComingSoon = { showToolsSheet = false; onComingSoon() },
        )
    }

    if (showCaptureSheet) {
        CaptureModeSheet(
            onDismiss = { showCaptureSheet = false },
            onTranslate = { showCaptureSheet = false; onCameraTranslate() },
            onScanAuto = { showCaptureSheet = false; onScanAuto() },
            onScanManual = { showCaptureSheet = false; onScanManual() },
        )
    }

    if (showNewFolderDialog) {
        NewFolderDialog(
            onDismiss = { showNewFolderDialog = false },
            onConfirm = { name -> showNewFolderDialog = false; onCreateFolder(name) },
        )
    }

    showDeleteFolderId?.let { folderId ->
        AlertDialog(
            onDismissRequest = { showDeleteFolderId = null },
            title = { Text(stringResource(R.string.delete_folder_title)) },
            text = { Text(stringResource(R.string.delete_folder_body)) },
            confirmButton = {
                TextButton(onClick = { onDeleteFolder(folderId); showDeleteFolderId = null }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteFolderId = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    if (showMoveSheet) {
        MoveToFolderSheet(
            folders = folders,
            onDismiss = { showMoveSheet = false },
            onSelect = { folderId ->
                onMoveDocumentsToFolder(selectedIds, folderId)
                showMoveSheet = false
                exitSelectMode()
            },
        )
    }
}

@Composable
private fun SortOptionItem(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { if (selected) Icon(Icons.Filled.CheckCircle, contentDescription = null) },
        onClick = onClick,
    )
}

@Composable
private fun LockedBadge() {
    Icon(
        Icons.Filled.Lock,
        contentDescription = stringResource(R.string.coming_soon_badge),
        modifier = Modifier.size(16.dp),
        tint = MaterialTheme.colorScheme.outline,
    )
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text(stringResource(R.string.home_search_hint)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
    )
}

@Composable
private fun SmartFiltersRow(onClick: () -> Unit) {
    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        AssistChip(
            onClick = onClick,
            label = { Text(stringResource(R.string.home_smart_filters)) },
            trailingIcon = { LockedBadge() },
            colors = AssistChipDefaults.assistChipColors(),
        )
    }
}

@Composable
private fun FoldersRow(
    folders: List<FolderMeta>,
    currentFolderId: String?,
    onOpenFolder: (String?) -> Unit,
    onNewFolder: () -> Unit,
    onLongPressFolder: (String) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = currentFolderId == null,
                onClick = { onOpenFolder(null) },
                label = { Text(stringResource(R.string.folder_all_documents)) },
                leadingIcon = { Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
        items(folders, key = { it.id }) { folder ->
            FilterChip(
                selected = currentFolderId == folder.id,
                onClick = { onOpenFolder(folder.id) },
                label = { Text(folder.name) },
                leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.combinedClickable(
                    onClick = { onOpenFolder(folder.id) },
                    onLongClick = { onLongPressFolder(folder.id) },
                ),
            )
        }
        item {
            AssistChip(
                onClick = onNewFolder,
                label = { Text(stringResource(R.string.menu_new_folder)) },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

@Composable
private fun EmptyState(hasQuery: Boolean, hasFolder: Boolean) {
    val text = when {
        hasQuery -> stringResource(R.string.empty_search)
        hasFolder -> stringResource(R.string.empty_folder)
        else -> stringResource(R.string.empty_state)
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.Description,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = text,
            modifier = Modifier.padding(top = 16.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun rememberThumbnail(file: File, docId: String): Bitmap? {
    val state = produceState<Bitmap?>(initialValue = null, docId) {
        value = withContext(Dispatchers.IO) {
            runCatching { if (file.exists()) BitmapFactory.decodeFile(file.path) else null }.getOrNull()
        }
    }
    return state.value
}

@Composable
private fun ThumbnailBox(file: File, docId: String, modifier: Modifier = Modifier) {
    val bitmap = rememberThumbnail(file, docId)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Filled.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

private fun formatDate(epochMillis: Long): String =
    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("vi", "VN")).format(Date(epochMillis))

@Composable
private fun DocumentGridCard(
    doc: DocumentMeta,
    thumbnailFile: File,
    selectMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box {
            ThumbnailBox(
                file = thumbnailFile,
                docId = doc.id,
                modifier = Modifier.fillMaxWidth().aspectRatio(0.75f).padding(8.dp),
            )
            if (selectMode) {
                Icon(
                    if (selected) Icons.Filled.CheckCircle else Icons.Filled.Close,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(22.dp),
                )
            }
        }
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                text = doc.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = formatDate(doc.modifiedAtEpochMillis) + "  •  " + stringResource(R.string.pages_count, doc.pageCount),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DocumentListRow(
    doc: DocumentMeta,
    thumbnailFile: File,
    selectMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThumbnailBox(file = thumbnailFile, docId = doc.id, modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(doc.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Row {
                Text(formatDate(doc.modifiedAtEpochMillis), style = MaterialTheme.typography.bodySmall)
                Text(
                    "  •  " + stringResource(R.string.pages_count, doc.pageCount),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (selectMode) {
            Icon(
                if (selected) Icons.Filled.CheckCircle else Icons.Filled.Close,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun HomeBottomBar(
    onToolsClick: () -> Unit,
    onCameraClick: () -> Unit,
    onGalleryClick: () -> Unit,
) {
    Surface(shadowElevation = 8.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onToolsClick) {
                Icon(Icons.Filled.GridView, contentDescription = stringResource(R.string.cd_tools_button))
            }

            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary)
                    .clickable(onClick = onCameraClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.CameraAlt,
                    contentDescription = stringResource(R.string.cd_camera_button),
                    tint = MaterialTheme.colorScheme.onSecondary,
                    modifier = Modifier.size(30.dp),
                )
            }

            IconButton(onClick = onGalleryClick) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = stringResource(R.string.cd_gallery_button))
            }
        }
    }
}

@Composable
private fun ToolsBottomSheet(
    onDismiss: () -> Unit,
    onDocumentClick: () -> Unit,
    onConvertClick: () -> Unit,
    onTranslateClick: () -> Unit,
    onComingSoon: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.tools_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ToolItem(Icons.Filled.Description, stringResource(R.string.tools_document), locked = false, onClick = onDocumentClick)
                ToolItem(Icons.Filled.TextFields, stringResource(R.string.tools_text), locked = true, onClick = onComingSoon)
                ToolItem(Icons.Filled.MenuBook, stringResource(R.string.tools_book), locked = true, onClick = onComingSoon)
                ToolItem(Icons.Filled.QrCodeScanner, stringResource(R.string.tools_qr_code), locked = true, onClick = onComingSoon)
            }
            Text(
                stringResource(R.string.tools_convert_section),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ToolItem(Icons.Filled.SwapHoriz, stringResource(R.string.tools_convert_office), locked = false, onClick = onConvertClick)
                ToolItem(Icons.Filled.AutoAwesome, stringResource(R.string.tools_convert_cloud), locked = false, onClick = onConvertClick)
                ToolItem(Icons.Filled.Translate, stringResource(R.string.tools_translate), locked = false, onClick = onTranslateClick)
                Spacer(Modifier.width(76.dp))
            }
        }
    }
}

/** Bản 1.0: 3 lựa chọn khi bấm nút camera — Dịch / Scan tự động / Scan thủ công. */
@Composable
private fun CaptureModeSheet(
    onDismiss: () -> Unit,
    onTranslate: () -> Unit,
    onScanAuto: () -> Unit,
    onScanManual: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(bottom = 28.dp)) {
            CaptureOption(Icons.Filled.Translate, stringResource(R.string.capture_translate), stringResource(R.string.capture_translate_desc), onTranslate)
            CaptureOption(Icons.Filled.AutoAwesome, stringResource(R.string.capture_scan_auto), stringResource(R.string.capture_scan_auto_desc), onScanAuto)
            CaptureOption(Icons.Filled.CameraAlt, stringResource(R.string.capture_scan_manual), stringResource(R.string.capture_scan_manual_desc), onScanManual)
        }
    }
}

@Composable
private fun CaptureOption(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, desc: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
        Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ToolItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, locked: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(76.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            if (locked) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = stringResource(R.string.coming_soon_badge),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(16.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun NewFolderDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_folder_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text(stringResource(R.string.new_folder_hint)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun MoveToFolderSheet(
    folders: List<FolderMeta>,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.select_mode_move_to_folder),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.folder_uncategorized)) },
                leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                onClick = { onSelect(null) },
            )
            folders.forEach { folder ->
                DropdownMenuItem(
                    text = { Text(folder.name) },
                    leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                    onClick = { onSelect(folder.id) },
                )
            }
        }
    }
}
