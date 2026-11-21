package io.acanthis.app.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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

data class SelectableOption(
    val value: String,
    val label: String,
    val count: Int? = null,
)

@Composable
fun SearchableMultiSelectDialog(
    title: String,
    options: List<SelectableOption>,
    selectedValues: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
    searchPlaceholder: String = "搜索标签…",
    emptyMessage: String = "暂无可选项",
    emptySearchMessage: String = "未找到匹配项",
    confirmLabel: String = "确定",
) {
    var query by remember { mutableStateOf("") }
    var draft by remember(selectedValues) { mutableStateOf(selectedValues) }

    val filteredOptions = remember(options, query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            options
        } else {
            options.filter { option ->
                option.label.contains(trimmed, ignoreCase = true) ||
                    option.value.contains(trimmed, ignoreCase = true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            if (options.isEmpty()) {
                Text(
                    text = emptyMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OptionSearchField(
                        query = query,
                        onQueryChange = { query = it },
                        placeholder = searchPlaceholder,
                    )

                    HorizontalDivider()

                    if (filteredOptions.isEmpty()) {
                        Text(
                            text = emptySearchMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp),
                        ) {
                            items(
                                items = filteredOptions,
                                key = { it.value },
                            ) { option ->
                                val isChecked = option.value in draft
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            draft = if (isChecked) {
                                                draft - option.value
                                            } else {
                                                draft + option.value
                                            }
                                        }
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = { checked ->
                                            draft = if (checked) {
                                                draft + option.value
                                            } else {
                                                draft - option.value
                                            }
                                        },
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = option.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    option.count?.let { count ->
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "$count",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            TextButton(onClick = { onConfirm(draft) }) {
                val label = if (draft.isNotEmpty()) "$confirmLabel (${draft.size})" else confirmLabel
                Text(label)
            }
        },
        dismissButton = {
            Row {
                if (draft.isNotEmpty()) {
                    TextButton(onClick = { draft = emptySet() }) {
                        Text("清空")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        },
    )
}

@Composable
fun SearchableSingleSelectDialog(
    title: String,
    options: List<SelectableOption>,
    selectedValue: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    searchPlaceholder: String = "搜索选项…",
    emptyMessage: String = "暂无可选项",
    emptySearchMessage: String = "未找到匹配项",
) {
    var query by remember { mutableStateOf("") }

    val filteredOptions = remember(options, query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            options
        } else {
            options.filter { option ->
                option.label.contains(trimmed, ignoreCase = true) ||
                    option.value.contains(trimmed, ignoreCase = true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            if (options.isEmpty()) {
                Text(
                    text = emptyMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OptionSearchField(
                        query = query,
                        onQueryChange = { query = it },
                        placeholder = searchPlaceholder,
                    )

                    HorizontalDivider()

                    if (filteredOptions.isEmpty()) {
                        Text(
                            text = emptySearchMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 340.dp),
                        ) {
                            items(
                                items = filteredOptions,
                                key = { it.value },
                            ) { option ->
                                val isSelected = option.value == selectedValue
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelect(option.value) }
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { onSelect(option.value) },
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = option.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    option.count?.let { count ->
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "$count",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                Text("取消")
            }
        },
    )
}

@Composable
private fun OptionSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(placeholder) },
        singleLine = true,
        shape = RoundedCornerShape(20.dp),
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "清空搜索",
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
