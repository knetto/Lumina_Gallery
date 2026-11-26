package com.luminagallery.drive.ui.screens.people

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Merge
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luminagallery.drive.data.repository.ai.PersonMergeSuggestionUi
import com.luminagallery.drive.data.repository.ai.PersonSummary
import com.luminagallery.drive.data.repository.ai.SmartIndexProgress
import com.luminagallery.drive.ui.components.LuminaSnackbarHost
import com.luminagallery.drive.ui.components.PersonAvatar
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PeopleScreen(
    viewModel: PeopleViewModel,
    onNavigateBack: () -> Unit,
    onOpenPerson: (personId: String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showOverflowMenu by remember { mutableStateOf(false) }
    var showCombineDialog by remember { mutableStateOf(false) }
    var showRebuildConfirmDialog by remember { mutableStateOf(false) }
    var showSingleFacesExpanded by remember { mutableStateOf(false) }
    var renameTargetPerson by remember { mutableStateOf<PersonSummary?>(null) }

    BackHandler(enabled = uiState.isSelectionMode) {
        viewModel.clearSelection()
    }

    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearSnackbar()
    }

    val activeList = if (uiState.showHiddenSection) uiState.hiddenPersons else uiState.visiblePersons

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { LuminaSnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = when {
                                uiState.isSelectionMode -> "${uiState.selectedPersonIds.size} selected"
                                uiState.showHiddenSection -> "Hidden people"
                                else -> "People"
                            },
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                        )
                        val subtitle = when {
                            uiState.isSelectionMode -> "Tap people to combine or manage"
                            uiState.showHiddenSection -> "${uiState.hiddenPersons.size} hidden"
                            else -> "${uiState.visiblePersons.size} detected · Local & Drive"
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (uiState.isSelectionMode) {
                                viewModel.clearSelection()
                            } else if (uiState.showHiddenSection) {
                                viewModel.toggleShowHidden()
                            } else {
                                onNavigateBack()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (uiState.isSelectionMode) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (!uiState.isSelectionMode && activeList.size >= 2) {
                        TextButton(onClick = { viewModel.enterSelectionMode() }) {
                            Icon(
                                imageVector = Icons.Rounded.Merge,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text("Combine", fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (!uiState.isSelectionMode) {
                        Box {
                            IconButton(onClick = { showOverflowMenu = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "More options")
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false },
                                shape = RoundedCornerShape(20.dp),
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (uiState.showHiddenSection) "Show active people"
                                            else "Hidden people (${uiState.hiddenPersons.size})"
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        viewModel.toggleShowHidden()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Scan entire library") },
                                    onClick = {
                                        showOverflowMenu = false
                                        viewModel.scanEntireLibrary()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Rebuild People & Objects") },
                                    onClick = {
                                        showOverflowMenu = false
                                        showRebuildConfirmDialog = true
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = if (uiState.isSelectionMode) 116.dp else 32.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                // Live scan status banner (shown while scanning or if remaining unindexed items exist)
                val prog = uiState.progress
                if (prog.isIndexing || prog.isDownloadingModels || prog.remainingPhotos > 0) {
                    item(key = "smart_scan_banner", span = { GridItemSpan(maxLineSpan) }) {
                        SmartScanProgressBanner(
                            progress = prog,
                            onScanAll = viewModel::scanEntireLibrary,
                            onPause = viewModel::pauseScan
                        )
                    }
                }

                // Interactive "Same person?" merge suggestion card (Google Photos / Apple Photos style)
                val topSuggestion = uiState.mergeSuggestions.firstOrNull()
                if (!uiState.showHiddenSection && !uiState.isSelectionMode && topSuggestion != null) {
                    item(key = "merge_suggestion_card", span = { GridItemSpan(maxLineSpan) }) {
                        MergeSuggestionCard(
                            suggestion = topSuggestion,
                            totalCount = uiState.mergeSuggestions.size,
                            onCombine = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.acceptMergeSuggestion(topSuggestion)
                            },
                            onDismiss = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.dismissMergeSuggestion(topSuggestion)
                            }
                        )
                    }
                }

                if (activeList.isEmpty()) {
                    item(key = "people_empty_state", span = { GridItemSpan(maxLineSpan) }) {
                        PeopleEmptyState(
                            isHiddenView = uiState.showHiddenSection,
                            isScanning = prog.isIndexing || prog.isDownloadingModels,
                            onScanNow = viewModel::scanEntireLibrary
                        )
                    }
                } else {
                    // Samsung Gallery / Apple Photos pattern: show named, pinned & recurring (2+ photos)
                    // people first, and keep 1-photo unnamed background faces neatly collapsible below!
                    val recurringPeople = if (uiState.showHiddenSection) {
                        activeList
                    } else {
                        val primary = activeList.filter { it.isNamed || it.isFavorite || it.photoCount >= 2 }
                        if (primary.isNotEmpty()) primary else activeList
                    }
                    val singlePhotoFaces = if (uiState.showHiddenSection || recurringPeople.size == activeList.size) {
                        emptyList()
                    } else {
                        activeList.filterNot { it.isNamed || it.isFavorite || it.photoCount >= 2 }
                    }

                    items(
                        items = recurringPeople,
                        key = { it.personId }
                    ) { person ->
                        val isSelected = person.personId in uiState.selectedPersonIds
                        PersonGridCard(
                            person = person,
                            isSelected = isSelected,
                            onClick = {
                                if (uiState.isSelectionMode) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    viewModel.togglePersonSelected(person.personId)
                                } else {
                                    onOpenPerson(person.personId)
                                }
                            },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.enterSelectionMode(person.personId)
                            },
                            modifier = Modifier.animateItem()
                        )
                    }

                    if (singlePhotoFaces.isNotEmpty()) {
                        item(key = "other_faces_toggle", span = { GridItemSpan(maxLineSpan) }) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { showSingleFacesExpanded = !showSingleFacesExpanded }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(
                                            text = "Other faces (${singlePhotoFaces.size})",
                                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Single-photo faces · tap to expand, name, or combine",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(
                                        imageVector = if (showSingleFacesExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        if (showSingleFacesExpanded) {
                            items(
                                items = singlePhotoFaces,
                                key = { it.personId }
                            ) { person ->
                                val isSelected = person.personId in uiState.selectedPersonIds
                                PersonGridCard(
                                    person = person,
                                    isSelected = isSelected,
                                    onClick = {
                                        if (uiState.isSelectionMode) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            viewModel.togglePersonSelected(person.personId)
                                        } else {
                                            onOpenPerson(person.personId)
                                        }
                                    },
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.enterSelectionMode(person.personId)
                                    },
                                    modifier = Modifier.animateItem()
                                )
                            }
                        }
                    }
                }
            }

            // Floating Combine / Manage bottom pill bar
            AnimatedVisibility(
                visible = uiState.isSelectionMode && uiState.selectedPersonIds.isNotEmpty(),
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                val count = uiState.selectedPersonIds.size
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shadowElevation = 10.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            0.8.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                            RoundedCornerShape(28.dp)
                        )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (count >= 2) {
                            Button(
                                onClick = { showCombineDialog = true },
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.weight(1.3f)
                            ) {
                                Icon(Icons.Rounded.Merge, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Combine ($count)", fontWeight = FontWeight.SemiBold)
                            }
                        } else if (count == 1) {
                            val single = activeList.firstOrNull { it.personId in uiState.selectedPersonIds }
                            FilledTonalButton(
                                onClick = { if (single != null) renameTargetPerson = single },
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.weight(1.1f)
                            ) {
                                Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Name", fontWeight = FontWeight.SemiBold)
                            }
                        }

                        FilledTonalButton(
                            onClick = { viewModel.setSelectedHidden(!uiState.showHiddenSection) },
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (uiState.showHiddenSection) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (uiState.showHiddenSection) "Unhide" else "Hide")
                        }
                    }
                }
            }
        }
    }

    // Combine Selected People Dialog
    if (showCombineDialog) {
        val selectedPeople = remember(activeList, uiState.selectedPersonIds) {
            activeList.filter { it.personId in uiState.selectedPersonIds }
        }
        val defaultName = remember(selectedPeople) {
            selectedPeople.firstOrNull { it.isNamed }?.name.orEmpty()
        }
        var combinedName by remember(defaultName) { mutableStateOf(defaultName) }

        AlertDialog(
            onDismissRequest = { showCombineDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            title = {
                Text("Combine ${selectedPeople.size} people?", fontWeight = FontWeight.SemiBold)
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Overlapping avatar stack preview
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        selectedPeople.take(4).forEachIndexed { idx, p ->
                            PersonAvatar(
                                cropPath = p.coverCropPath,
                                contentDescription = p.displayTitle,
                                size = 54.dp,
                                borderColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.offset(x = (-10 * idx).dp)
                            )
                        }
                    }
                    Text(
                        text = "All photos from these groups will be merged into one person, improving future face recognition automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    OutlinedTextField(
                        value = combinedName,
                        onValueChange = { combinedName = it },
                        label = { Text("Person's name (optional)") },
                        placeholder = { Text("e.g. Alex, Mom…") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCombineDialog = false
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.combineSelectedPersons(combinedName)
                    },
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Combine", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCombineDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Rename single selected person dialog (with automatic duplicate-name merge check)
    renameTargetPerson?.let { person ->
        var draft by remember(person.personId) { mutableStateOf(person.name) }
        var duplicatePromptName by remember(person.personId) { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { renameTargetPerson = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(26.dp),
            title = { Text(if (person.isNamed) "Rename person" else "Name this person") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = {
                            draft = it
                            duplicatePromptName = null
                        },
                        label = { Text("Name") },
                        placeholder = { Text("Enter name…") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (duplicatePromptName != null) {
                        Text(
                            text = "\"$duplicatePromptName\" already exists — tap Combine to merge both into one person.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            confirmButton = {
                if (duplicatePromptName != null) {
                    Button(
                        onClick = {
                            renameTargetPerson = null
                            viewModel.renamePerson(person.personId, draft, mergeIfDuplicate = true)
                        }
                    ) { Text("Combine") }
                } else {
                    TextButton(
                        onClick = {
                            scope.launch {
                                val dup = viewModel.findDuplicateNameTarget(draft, person.personId)
                                if (dup != null) {
                                    duplicatePromptName = dup
                                } else {
                                    renameTargetPerson = null
                                    viewModel.renamePerson(person.personId, draft, mergeIfDuplicate = false)
                                }
                            }
                        }
                    ) { Text("Save", fontWeight = FontWeight.SemiBold) }
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTargetPerson = null }) { Text("Cancel") }
            }
        )
    }

    if (showRebuildConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRebuildConfirmDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(26.dp),
            title = { Text("Rebuild People & Objects?") },
            text = {
                Text(
                    text = "This re-analyzes your local & Google Drive photos and videos cleanly on-device while automatically keeping your saved names and pinned people (locked folders are always skipped).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRebuildConfirmDialog = false
                        viewModel.rebuildAllSmartIndex()
                    },
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Rebuild", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRebuildConfirmDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PersonGridCard(
    person: PersonSummary,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(vertical = 6.dp, horizontal = 4.dp)
    ) {
        PersonAvatar(
            cropPath = person.coverCropPath,
            contentDescription = person.displayTitle,
            size = 90.dp,
            isSelected = isSelected,
            isFavorite = person.isFavorite
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (person.isNamed) person.name else "Add name",
            fontSize = 13.5.sp,
            fontWeight = if (person.isNamed) FontWeight.SemiBold else FontWeight.Medium,
            color = if (person.isNamed) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.primary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Text(
            text = if (person.photoCount == 1) "1 photo" else "${person.photoCount} photos",
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun MergeSuggestionCard(
    suggestion: PersonMergeSuggestionUi,
    totalCount: Int,
    onCombine: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                0.8.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                RoundedCornerShape(22.dp)
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Same person?",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (totalCount > 1) {
                    Text(
                        text = "1 of $totalCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    PersonAvatar(
                        cropPath = suggestion.target.coverCropPath,
                        contentDescription = suggestion.target.displayTitle,
                        size = 64.dp,
                        isFavorite = suggestion.target.isFavorite
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = suggestion.target.displayTitle,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${suggestion.target.photoCount} items",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CompareArrows,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    PersonAvatar(
                        cropPath = suggestion.source.coverCropPath,
                        contentDescription = suggestion.source.displayTitle,
                        size = 64.dp,
                        isFavorite = suggestion.source.isFavorite
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = suggestion.source.displayTitle,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${suggestion.source.photoCount} items",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilledTonalButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Not the same", fontSize = 13.sp)
                }
                Button(
                    onClick = onCombine,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.Merge, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Combine", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SmartScanProgressBanner(
    progress: SmartIndexProgress,
    onScanAll: () -> Unit,
    onPause: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                0.7.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                RoundedCornerShape(20.dp)
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = when {
                                progress.isDownloadingModels -> "Preparing on-device AI models…"
                                progress.isIndexing -> progress.statusText ?: "Analyzing photos & videos on-device…"
                                !progress.statusText.isNullOrBlank() -> progress.statusText
                                else -> "${progress.remainingPhotos} more items ready to scan"
                            },
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${progress.totalIndexedPhotos}/${progress.totalEligiblePhotos} indexed · Local & Drive · Locked skipped",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (progress.isIndexing) {
                    IconButton(onClick = onPause, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.Pause, contentDescription = "Pause scan", modifier = Modifier.size(18.dp))
                    }
                } else if (!progress.isDownloadingModels) {
                    FilledTonalButton(
                        onClick = onScanAll,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("Scan all", fontSize = 12.sp)
                    }
                }
            }

            if (progress.isIndexing || progress.isDownloadingModels) {
                val frac = if (progress.totalEligiblePhotos > 0) {
                    (progress.totalIndexedPhotos.toFloat() / progress.totalEligiblePhotos).coerceIn(0f, 1f)
                } else 0f
                if (progress.isDownloadingModels || frac <= 0.01f) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape)
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { frac },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape)
                    )
                }
            }
        }
    }
}

@Composable
private fun PeopleEmptyState(
    isHiddenView: Boolean,
    isScanning: Boolean,
    onScanNow: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isHiddenView) Icons.Rounded.VisibilityOff else Icons.Rounded.Face,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp)
            )
        }
        Text(
            text = when {
                isHiddenView -> "No hidden people"
                isScanning -> "Finding faces on your device…"
                else -> "No people grouped yet"
            },
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = if (isHiddenView) {
                "People you hide from the main People list will appear here so you can restore them anytime."
            } else {
                "Lumina recognizes faces and objects 100% locally across your phone and connected Google Drive folders — your photos never leave your device."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (!isHiddenView) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = "Locked & hidden folders are always skipped",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!isScanning) {
                Spacer(Modifier.height(4.dp))
                Button(onClick = onScanNow, shape = RoundedCornerShape(20.dp)) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Scan photos now", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
