package io.acanthis.app.app

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
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.acanthis.app.app.theme.AcanthisTheme
import io.acanthis.app.core.network.AcanthisApiClient
import io.acanthis.app.core.network.ApiException
import io.acanthis.app.core.network.NovelSourceDescriptor
import io.acanthis.app.core.platform.platformName
import io.acanthis.app.core.preferences.ServerConfig
import io.acanthis.app.core.preferences.ServerConfigStore
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private enum class MainTab(
    val label: String,
    val icon: ImageVector,
) {
    Bookshelf("书架", Icons.AutoMirrored.Rounded.MenuBook),
    Explore("发现", Icons.Rounded.Explore),
    Settings("设置", Icons.Rounded.Settings),
}

private sealed interface ConnectionTestState {
    data object Idle : ConnectionTestState
    data object Testing : ConnectionTestState
    data class Success(
        val serverTimestamp: String,
        val sources: List<NovelSourceDescriptor>,
    ) : ConnectionTestState

    data class Failure(val message: String) : ConnectionTestState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcanthisApp(
    configStore: ServerConfigStore = koinInject(),
    apiClient: AcanthisApiClient = koinInject(),
) {
    AcanthisTheme {
        val serverConfig by configStore.configFlow.collectAsStateWithLifecycle(
            initialValue = ServerConfig(),
        )
        var currentTab by rememberSaveable { mutableStateOf(MainTab.Bookshelf) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = when (currentTab) {
                                MainTab.Bookshelf -> "Acanthis · 书架"
                                MainTab.Explore -> "Acanthis · 发现"
                                MainTab.Settings -> "Acanthis · 设置"
                            },
                        )
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    MainTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = currentTab == tab,
                            onClick = { currentTab = tab },
                            icon = { Icon(imageVector = tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            },
        ) { innerPadding ->
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                when (currentTab) {
                    MainTab.Bookshelf -> PlaceholderTabScreen(
                        title = "书架（M3 对接 /v1/sync/state）",
                        description = "将支持本地 Room 书架缓存与 Acanthis 双向增量云同步，与 Web 端互通阅读进度。",
                        serverConfig = serverConfig,
                        onOpenSettings = { currentTab = MainTab.Settings },
                    )

                    MainTab.Explore -> PlaceholderTabScreen(
                        title = "发现（M1 对接 /v1/linovelib/explore）",
                        description = "将动态消费服务端 /explore/schema，支持频道推荐、榜单、分类筛选与关键词搜索。",
                        serverConfig = serverConfig,
                        onOpenSettings = { currentTab = MainTab.Settings },
                    )

                    MainTab.Settings -> SettingsScreen(
                        serverConfig = serverConfig,
                        configStore = configStore,
                        apiClient = apiClient,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaceholderTabScreen(
    title: String,
    description: String,
    serverConfig: ServerConfig,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!serverConfig.isConfigured) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "尚未配置 Acanthis 服务器",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = "请先前往设置页填写 api-server 地址并验证连通性（Android 模拟器访问本机可使用 http://10.0.2.2:5301）。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Button(onClick = onOpenSettings) {
                        Text("去配置服务器")
                    }
                }
            }
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "当前服务器",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = serverConfig.apiBaseUrl,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "同步设备名：${serverConfig.deviceLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    serverConfig: ServerConfig,
    configStore: ServerConfigStore,
    apiClient: AcanthisApiClient,
) {
    val scope = rememberCoroutineScope()
    var baseUrlInput by rememberSaveable { mutableStateOf("") }
    var tokenInput by rememberSaveable { mutableStateOf("") }
    var deviceLabelInput by rememberSaveable { mutableStateOf("") }
    var initializedFromStore by rememberSaveable { mutableStateOf(false) }
    var tokenVisible by rememberSaveable { mutableStateOf(false) }
    var saveFeedback by remember { mutableStateOf<String?>(null) }
    var testState by remember { mutableStateOf<ConnectionTestState>(ConnectionTestState.Idle) }

    LaunchedEffect(serverConfig) {
        if (!initializedFromStore || (baseUrlInput.isEmpty() && serverConfig.apiBaseUrl.isNotEmpty())) {
            baseUrlInput = serverConfig.apiBaseUrl
            tokenInput = serverConfig.authToken
            deviceLabelInput = serverConfig.deviceLabel
            initializedFromStore = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "服务器连接配置",
            style = MaterialTheme.typography.titleMedium,
        )

        OutlinedTextField(
            value = baseUrlInput,
            onValueChange = {
                baseUrlInput = it
                saveFeedback = null
            },
            label = { Text("API 服务器地址 (Base URL)") },
            placeholder = { Text("http://10.0.2.2:5301") },
            supportingText = { Text("支持填写根地址或带 /v1 后缀，Android 模拟器连宿主机请使用 10.0.2.2") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = tokenInput,
            onValueChange = {
                tokenInput = it
                saveFeedback = null
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
                saveFeedback = null
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
                    scope.launch {
                        configStore.save(
                            apiBaseUrl = baseUrlInput,
                            authToken = tokenInput,
                            deviceLabel = deviceLabelInput,
                        )
                        saveFeedback = "配置已保存到本地 DataStore"
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("保存配置")
            }

            OutlinedButton(
                enabled = testState !is ConnectionTestState.Testing,
                onClick = {
                    scope.launch {
                        testState = ConnectionTestState.Testing
                        val targetUrl = baseUrlInput.trim()
                        val targetToken = tokenInput.trim()
                        if (targetUrl.isEmpty()) {
                            testState = ConnectionTestState.Failure("请先填写 API 服务器地址")
                            return@launch
                        }
                        testState = try {
                            val health = apiClient.checkHealth(targetUrl)
                            val sources = apiClient.fetchSources(targetUrl, targetToken)
                            configStore.save(
                                apiBaseUrl = targetUrl,
                                authToken = targetToken,
                                deviceLabel = deviceLabelInput,
                            )
                            ConnectionTestState.Success(
                                serverTimestamp = health.timestamp,
                                sources = sources,
                            )
                        } catch (e: ApiException) {
                            ConnectionTestState.Failure(
                                "接口错误 HTTP ${e.statusCode} (code=${e.apiCode}): ${e.message}",
                            )
                        } catch (e: Exception) {
                            ConnectionTestState.Failure(
                                e.message ?: "连接失败，请检查地址或网络状态",
                            )
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                if (testState is ConnectionTestState.Testing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                }
                Text("测试连接")
            }
        }

        if (saveFeedback != null) {
            Text(
                text = saveFeedback.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        when (val state = testState) {
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
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
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
                        modifier = Modifier.padding(16.dp),
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

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "运行环境：${platformName()} · M0 脚手架",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
