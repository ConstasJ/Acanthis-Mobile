package io.acanthis.app.feature.bookshelf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.acanthis.app.core.database.BookshelfCategoryEntity
import io.acanthis.app.core.ui.SearchableMultiSelectDialog
import io.acanthis.app.core.ui.SelectableOption
import io.acanthis.app.data.LibraryRepository

@Composable
fun ShelfManagerDialog(
    categories: List<BookshelfCategoryEntity>,
    onDismiss: () -> Unit,
    onCreateCategory: (name: String, onError: (String) -> Unit) -> Unit,
    onRenameCategory: (id: String, name: String, onError: (String) -> Unit) -> Unit,
    onDeleteCategory: (id: String) -> Unit,
    onReorderCategories: (orderedIds: List<String>) -> Unit,
) {
    var newShelfName by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf<String?>(null) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editingName by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<BookshelfCategoryEntity?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("管理书架") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = newShelfName,
                        onValueChange = {
                            newShelfName = it
                            inputError = null
                        },
                        placeholder = { Text("新建书架名称（1-32字）") },
                        singleLine = true,
                        isError = inputError != null,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            val trimmed = newShelfName.trim()
                            if (trimmed.length !in LibraryRepository.MIN_CATEGORY_NAME_LENGTH..LibraryRepository.MAX_CATEGORY_NAME_LENGTH) {
                                inputError = "名称需为 1-32 个字符"
                            } else {
                                onCreateCategory(trimmed) { err -> inputError = err }
                                newShelfName = ""
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "新建书架",
                        )
                    }
                }

                inputError?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                HorizontalDivider()

                if (categories.isEmpty()) {
                    Text(
                        text = "暂无自定义书架。「全部」与「默认」为内置视图，不可删除。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        itemsIndexed(
                            items = categories,
                            key = { _, item -> item.id },
                        ) { index, cat ->
                            if (editingId == cat.id) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    OutlinedTextField(
                                        value = editingName,
                                        onValueChange = {
                                            editingName = it
                                            inputError = null
                                        },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(
                                        onClick = {
                                            val trimmed = editingName.trim()
                                            if (trimmed.length !in LibraryRepository.MIN_CATEGORY_NAME_LENGTH..LibraryRepository.MAX_CATEGORY_NAME_LENGTH) {
                                                inputError = "名称需为 1-32 个字符"
                                            } else {
                                                onRenameCategory(cat.id, trimmed) { err -> inputError = err }
                                                editingId = null
                                            }
                                        },
                                    ) {
                                        Icon(Icons.Rounded.Check, contentDescription = "保存重命名")
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = cat.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(
                                        enabled = index > 0,
                                        onClick = {
                                            val ids = categories.map { it.id }.toMutableList()
                                            val prev = ids[index - 1]
                                            ids[index - 1] = ids[index]
                                            ids[index] = prev
                                            onReorderCategories(ids)
                                        },
                                    ) {
                                        Icon(
                                            Icons.Rounded.ArrowUpward,
                                            contentDescription = "上移",
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    IconButton(
                                        enabled = index < categories.lastIndex,
                                        onClick = {
                                            val ids = categories.map { it.id }.toMutableList()
                                            val next = ids[index + 1]
                                            ids[index + 1] = ids[index]
                                            ids[index] = next
                                            onReorderCategories(ids)
                                        },
                                    ) {
                                        Icon(
                                            Icons.Rounded.ArrowDownward,
                                            contentDescription = "下移",
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            editingId = cat.id
                                            editingName = cat.name
                                        },
                                    ) {
                                        Icon(
                                            Icons.Rounded.Edit,
                                            contentDescription = "重命名",
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    IconButton(onClick = { pendingDelete = cat }) {
                                        Icon(
                                            Icons.Rounded.DeleteOutline,
                                            contentDescription = "删除书架",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成")
            }
        },
    )

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除书架「${target.name}」") },
            text = { Text("删除该自定义书架不会删除其中的书籍，仅移除分组；未归属其他书架的书会自动回到「默认」视图。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteCategory(target.id)
                        pendingDelete = null
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
fun ShelfAssignDialog(
    novelTitle: String,
    categories: List<BookshelfCategoryEntity>,
    initialCategoryIds: List<String>,
    onDismiss: () -> Unit,
    onCreateCategory: (name: String, onError: (String) -> Unit) -> Unit,
    onConfirm: (selectedCategoryIds: List<String>) -> Unit,
    dialogTitle: String = "管理所属书架",
    subtitleText: String = "《$novelTitle》",
    confirmLabel: String = "保存",
) {
    var selectedIds by remember(initialCategoryIds) { mutableStateOf(initialCategoryIds.toSet()) }
    var quickCreateName by remember { mutableStateOf("") }
    var createError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = dialogTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (subtitleText.isNotBlank()) {
                    Text(
                        text = subtitleText,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "不勾选任何自定义书架时，书籍会保留在「默认」中。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (categories.isEmpty()) {
                    Text(
                        text = "尚未创建自定义书架，可在下方直接新建。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        categories.forEach { cat ->
                            val checked = cat.id in selectedIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedIds = if (checked) {
                                            selectedIds - cat.id
                                        } else {
                                            selectedIds + cat.id
                                        }
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { isChecked ->
                                        selectedIds = if (isChecked) {
                                            selectedIds + cat.id
                                        } else {
                                            selectedIds - cat.id
                                        }
                                    },
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = cat.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedTextField(
                        value = quickCreateName,
                        onValueChange = {
                            quickCreateName = it
                            createError = null
                        },
                        placeholder = { Text("快捷新建书架") },
                        singleLine = true,
                        isError = createError != null,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            val trimmed = quickCreateName.trim()
                            if (trimmed.length !in LibraryRepository.MIN_CATEGORY_NAME_LENGTH..LibraryRepository.MAX_CATEGORY_NAME_LENGTH) {
                                createError = "名称需为 1-32 个字符"
                            } else {
                                onCreateCategory(trimmed) { err -> createError = err }
                                quickCreateName = ""
                            }
                        },
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "新建书架")
                    }
                }
                createError?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val activeIds = categories.mapTo(HashSet()) { it.id }
                    onConfirm(selectedIds.filter { it in activeIds })
                },
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
fun GenreFilterDialog(
    availableGenres: List<GenreCountItem>,
    selectedGenres: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    SearchableMultiSelectDialog(
        title = "按题材标签筛选（多选交集）",
        options = remember(availableGenres) {
            availableGenres.map { genre ->
                SelectableOption(
                    value = genre.name,
                    label = genre.name,
                    count = genre.count,
                )
            }
        },
        selectedValues = selectedGenres,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
        searchPlaceholder = "搜索题材标签…",
        emptyMessage = "当前书架暂无可用题材标签，下拉同步后将自动补全作品标签。",
        confirmLabel = "应用",
    )
}
