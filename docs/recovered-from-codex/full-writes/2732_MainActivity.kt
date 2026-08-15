package com.syncclipboard

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.syncclipboard.service.ClipboardMonitorService
import com.syncclipboard.ui.theme.SyncClipboardTheme
import com.syncclipboard.util.ClipboardTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ClipboardMonitorService.isMonitorEnabled(this) && !ClipboardMonitorService.isRunning.value) {
            ClipboardMonitorService.start(this)
        }
        setContent {
            SyncClipboardTheme {
                MainScreen()
            }
        }
    }
}

private val pageTitles = listOf("剪贴板同步", "捕获记录", "设置")

private val cardContentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)

@Composable
private fun MainScreen() {
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 3 })

    Scaffold(
        topBar = { TopAppBar(title = pageTitles[pagerState.currentPage]) },
        bottomBar = {
            NavigationBar(mode = NavigationBarDisplayMode.IconAndText) {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    icon = MiuixIcons.Normal.Home,
                    label = "首页"
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    icon = MiuixIcons.Normal.Notes,
                    label = "记录"
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 2,
                    onClick = { scope.launch { pagerState.animateScrollToPage(2) } },
                    icon = MiuixIcons.Normal.Settings,
                    label = "设置"
                )
            }
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) { page ->
            when (page) {
                0 -> HomePage()
                1 -> RecordsPage()
                else -> SettingsPage()
            }
        }
    }
}

@Composable
private fun HomePage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var testStatus by remember { mutableStateOf("点击上方按钮,5 秒后读取剪贴板验证后台监听") }
    val serviceRunning by ClipboardMonitorService.isRunning.collectAsState()
    val captured by ClipboardMonitorService.captured.collectAsState()
    val currentText = remember(captured) {
        captured.firstOrNull()?.text ?: ClipboardTest.readClipboard(context) ?: "(空)"
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth(), contentPadding = cardContentPadding) {
                Text("当前剪贴板")
                Spacer(Modifier.height(8.dp))
                Text(
                    text = currentText,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(), contentPadding = cardContentPadding) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("持续监听剪贴板")
                            Text(if (serviceRunning) "运行中:后台监听中" else "未运行")
                        }
                        Switch(
                            checked = serviceRunning,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    requestNotificationPermissionIfNeeded(context, permissionLauncher)
                                    ClipboardMonitorService.start(context)
                                } else {
                                    ClipboardMonitorService.stop(context)
                                }
                            }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            testStatus = "自测已启动:请在 5 秒内切到其他应用…"
                            scope.launch {
                                delay(5000)
                                val text = ClipboardTest.readClipboard(context)
                                testStatus = if (!text.isNullOrEmpty()) {
                                    "读取成功(后台可读):\n$text"
                                } else {
                                    "读取结果为空 — hook 可能未生效,请检查 LSPosed 日志"
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("后台读剪贴板自测(5 秒后)")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(testStatus)
                }
            }
        }
    }
}

@Composable
private fun RecordsPage() {
    val captured by ClipboardMonitorService.captured.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (captured.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth(), contentPadding = cardContentPadding) {
                    Text("暂无捕获记录")
                }
            }
        } else {
            items(captured) { clip ->
                Card(modifier = Modifier.fillMaxWidth(), contentPadding = cardContentPadding) {
                    Text(formatTime(clip.time))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = clip.text,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsPage() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth(), contentPadding = cardContentPadding) {
                Text("使用说明")
                Spacer(Modifier.height(8.dp))
                Text("1. 在 LSPosed 中启用本模块\n2. 作用域勾选「系统框架」\n3. 重启手机后生效\n4. 返回首页做后台读取自测")
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(), contentPadding = cardContentPadding) {
                Text("关于")
                Spacer(Modifier.height(8.dp))
                Text("剪贴板同步 v0.1.0\n基于 Miuix + LSPosed 构建")
            }
        }
    }
}

private fun requestNotificationPermissionIfNeeded(
    context: android.content.Context,
    launcher: androidx.activity.compose.ManagedActivityResultLauncher<String, Boolean>
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private fun formatTime(time: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(time))