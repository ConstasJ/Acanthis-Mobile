package io.acanthis.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.acanthis.app.core.platform.currentTimeMillis
import io.acanthis.app.core.platform.platformName
import io.acanthis.app.data.SyncStatus
import org.koin.compose.viewmodel.koinViewModel

private enum class PendingCacheClearAction {
    READING_ONLY,
    ALL_INCLUDING_IMAGES,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val serverConfig = uiState.serverConfig

    var baseUrlInput by rememberSaveable { mutableStateOf("") }
    var tokenInput by rememberSaveable { mutableStateOf("") }
    var deviceLabelInput by rememberSaveable { mutableStateOf("") }
    var initializedFromStore by rememberSaveable { mutableStateOf(false) }
    var tokenVisible by rememberSaveable { mutableStateOf(false) }
    var pendingClearAction by remember { mutableStateOf<PendingCacheClearAction?>(null) }

    LaunchedEffect(Unit) {
        viewModel.refreshImageCacheSize()
    }

    LaunchedEffect(serverConfig) {
        if (!initializedFromStore || (baseUrlInput.isEmpty() && serverConfig.apiBaseUrl.isNotEmpty())) {
            baseUrlInput = serverConfig.apiBaseUrl
            tokenInput = serverConfig.authToken
            deviceLabelInput = serverConfig.deviceLabel
            initializedFromStore = true
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("设置") })

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1. Server configuration card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "服务器连接配置",
                        style = MaterialTheme.typography.titleMedium,
                    )

                    OutlinedTextField(
                        value = baseUrlInput,
                        onValueChange = {
                            baseUrlInput = it
                            viewModel.clearFeedback()
                        },
                        label = { Text("API 服务器地址 (Base URL)") },
                        placeholder = { Text("http://10.0.2.2:5301") },
                        supportingText = {
                            Text("支持填写根地址或带 /v1 后缀，切换地址会自动重置同步游标")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = {
                            tokenInput = it
                            viewModel.clearFeedback()
                        },
                        label = { Text("访问令牌 (Bearer Token，可选)") },
                        placeholder = { Text("服务端未开启 auth.token 时可留空") },
                        singleLine = true,
                        visualTransformation = if (tokenVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { tokenVisible = !tokenVisible }) {
                                Icon(
                                    imageVector = if (tokenVisible) {
                                        Icons.Rounded.VisibilityOff
                                    } else {
                                        Icons.Rounded.Visibility
                                    },
                                    contentDescription = if (tokenVisible) "隐藏令牌" else "显示令牌",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = deviceLabelInput,
                        onValueChange = {
                            deviceLabelInput = it
                            viewModel.clearFeedback()
                        },
                        label = { Text("设备标识 (同步进度用)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = {
                                viewModel.saveConfig(
                                    apiBaseUrl = baseUrlInput,
                                    authToken = tokenInput,
                                    deviceLabel = deviceLabelInput,
                                )
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("保存配置")
                        }

                        OutlinedButton(
                            enabled = uiState.connectionTestState !is ConnectionTestState.Testing,
                            onClick = {
                                viewModel.testConnection(
                                    apiBaseUrl = baseUrlInput,
                                    authToken = tokenInput,
                                    deviceLabel = deviceLabelInput,
                                )
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            if (uiState.connectionTestState is ConnectionTestState.Testing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                            }
                            Text("测试连接")
                        }
                    }

                    uiState.feedbackMessage?.let { feedback ->
                        Text(
                            text = feedback,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    when (val state = uiState.connectionTestState) {
                        ConnectionTestState.Idle,
                        ConnectionTestState.Testing -> Unit

                        is ConnectionTestState.Success -> {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.CheckCircle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            text = "连接成功 · 配置已自动保存",
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                    }
                                    Text(
                                        text = "服务端时间：${state.serverTimestamp}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        text = "可用书源（${state.sources.size}）：" +
                                            state.sources.joinToString("、") { "${it.name}(${it.id})" },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }

                        is ConnectionTestState.Failure -> {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ErrorOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                    Text(
                                        text = state.message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2. Cloud sync card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Sync,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "云同步",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }

                    val statusLabel = when (uiState.syncState.status) {
                        SyncStatus.IDLE -> "空闲"
                        SyncStatus.SYNCING -> "同步中…"
                        SyncStatus.ERROR -> "同步失败"
                    }
                    Text(
                        text = "同步状态：$statusLabel",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "待推送变更：${uiState.pendingDirtyCount} 条",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "上次同步：${formatRelativeSyncTime(uiState.syncState.lastSyncedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    uiState.syncState.lastError?.let { errorMsg ->
                        Text(
                            text = "最近错误：$errorMsg",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Button(
                        onClick = { viewModel.syncNow() },
                        enabled = serverConfig.isConfigured && uiState.syncState.status != SyncStatus.SYNCING,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (uiState.syncState.status == SyncStatus.SYNCING) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                        }
                        Text("立即同步")
                    }
                }
            }

            // 3. Local cache card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Storage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "本地缓存",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }

                    Text(
                        text = "正文缓存：${uiState.chapterCacheStats.chapterCount} 章 · ${formatByteSize(uiState.chapterCacheStats.chapterBytes)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "图片磁盘缓存：${formatByteSize(uiState.imageCacheBytes)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "已阅读章节默认永久保留在本地，清理缓存不会影响书架与阅读进度。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    OutlinedButton(
                        onClick = { pendingClearAction = PendingCacheClearAction.READING_ONLY },
                        enabled = !uiState.isClearingCache,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CleaningServices,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("清理正文与目录缓存")
                    }

                    OutlinedButton(
                        onClick = {
                            pendingClearAction = PendingCacheClearAction.ALL_INCLUDING_IMAGES
                        },
                        enabled = !uiState.isClearingCache,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CleaningServices,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("清理全部缓存（含图片）")
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "运行环境：${platformName()} · Phase 1 MVP",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    pendingClearAction?.let { action ->
        val (title, description) = when (action) {
            PendingCacheClearAction.READING_ONLY ->
                "清理正文与目录缓存" to "将删除本地所有已缓存的小说目录与章节正文，不影响书架和阅读进度。"

            PendingCacheClearAction.ALL_INCLUDING_IMAGES ->
                "清理全部缓存（含图片）" to "将删除本地所有小说目录、章节正文以及封面与插图磁盘缓存，不影响书架和阅读进度。"
        }
        AlertDialog(
            onDismissRequest = { pendingClearAction = null },
            title = { Text(title) },
            text = { Text(description) },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (action) {
                            PendingCacheClearAction.READING_ONLY -> viewModel.clearReadingCachesOnly()
                            PendingCacheClearAction.ALL_INCLUDING_IMAGES -> viewModel.clearAllCaches()
                        }
                        pendingClearAction = null
                    },
                ) {
                    Text("确认清理", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingClearAction = null }) {
                    Text("取消")
                }
            },
        )
    }
}

private fun formatByteSize(bytes: Long): String {
    if (bytes <= 0L) {
        return "0.00 MB"
    }
    val kb = bytes / 1024.0
    if (kb < 1024.0) {
        val tenths = ((kb * 10.0) + 0.5).toLong()
        return "${tenths / 10}.${tenths % 10} KB"
    }
    val mb = kb / 1024.0
    val hundredths = ((mb * 100.0) + 0.5).toLong()
    val whole = hundredths / 100
    val frac = (hundredths % 100).toString().padStart(2, '0')
    return "$whole.$frac MB"
}

private fun formatRelativeSyncTime(epochMillis: Long?): String {
    if (epochMillis == null || epochMillis <= 0L) {
        return "尚未同步"
    }
    val diffMs = (currentTimeMillis() - epochMillis).coerceAtLeast(0L)
    return when {
        diffMs < 60_000L -> "刚刚"
        diffMs < 3_600_000L -> "${diffMs / 60_000L} 分钟前"
        diffMs < 86_400_000L -> "${diffMs / 3_600_000L} 小时前"
        else -> "${diffMs / 86_400_000L} 天前"
    }
}
