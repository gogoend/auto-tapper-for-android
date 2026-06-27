package com.gogoend.intervalclicker

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gogoend.intervalclicker.data.CallAction
import com.gogoend.intervalclicker.data.ClickConfig
import com.gogoend.intervalclicker.data.ConfigRepository
import com.gogoend.intervalclicker.logging.ClickLogger
import com.gogoend.intervalclicker.permission.PermissionChecker
import com.gogoend.intervalclicker.scheduler.StopReason
import com.gogoend.intervalclicker.service.ClickAccessibilityService
import com.gogoend.intervalclicker.ui.theme.IntervalClickerTheme
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import java.io.File

data class PermSnapshot(
    val canOverlay: Boolean,
    val accessibility: Boolean,
    val battery: Boolean,
)

class MainActivity : ComponentActivity() {

    private val repo by lazy { ConfigRepository(applicationContext) }
    private var perms by mutableStateOf(PermSnapshot(false, false, false))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshPerms()
        setContent {
            IntervalClickerTheme {
                AppRoot(repo = repo, perms = perms)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPerms()
    }

    private fun refreshPerms() {
        perms = PermSnapshot(
            canOverlay = PermissionChecker.canDrawOverlay(this),
            accessibility = PermissionChecker.isAccessibilityEnabled(this),
            battery = PermissionChecker.isIgnoringBatteryOptimizations(this),
        )
    }
}

@Composable
private fun AppRoot(repo: ConfigRepository, perms: PermSnapshot) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by repo.configFlow.collectAsState(initial = ClickConfig())
    val isRunning by ClickAccessibilityService.isRunning.collectAsState()

    Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("自动连点器", style = MaterialTheme.typography.headlineSmall)

            if (!perms.accessibility) {
                PermissionGate(
                    onOpenAccessibility = { context.startActivity(PermissionChecker.accessibilitySettingsIntent()) },
                )
            } else {
                ConfigContent(
                    config = config,
                    isRunning = isRunning,
                    batteryOk = perms.battery,
                    onConfigChange = { updated ->
                        scope.launch { repo.save(updated) }
                        ClickAccessibilityService.instance?.updateConfig(updated)
                    },
                    onShowOverlay = {
                        scope.launch { repo.save(config) }
                        ClickAccessibilityService.instance?.showOverlay(config)
                    },
                    onStopForEdit = {
                        ClickAccessibilityService.instance?.stopClicking(StopReason.USER)
                    },
                    onOpenBattery = {
                        context.startActivity(PermissionChecker.batteryOptimizationSettingsIntent())
                    },
                )
            }
        }
    }
}

@Composable
private fun PermissionGate(
    onOpenAccessibility: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("需要授权后才能使用", style = MaterialTheme.typography.titleMedium)
            Text("必须开启本应用的无障碍服务，才能执行自动点击：")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("✗ 无障碍服务")
                Button(onClick = onOpenAccessibility) { Text("去开启") }
            }
            Text("开启后返回本页面即可继续。悬浮层由无障碍服务绘制，无需单独的悬浮窗权限。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ConfigContent(
    config: ClickConfig,
    isRunning: Boolean,
    batteryOk: Boolean,
    onConfigChange: (ClickConfig) -> Unit,
    onShowOverlay: () -> Unit,
    onStopForEdit: () -> Unit,
    onOpenBattery: () -> Unit,
) {
    val context = LocalContext.current
    var showStopDialog by remember { mutableStateOf(false) }

    if (isRunning) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("定时点击进行中", style = MaterialTheme.typography.titleMedium)
                Text("运行期间不可修改配置。")
                Button(onClick = { showStopDialog = true }) { Text("修改配置（需先停止）") }
            }
        }
    }

    val editable = !isRunning

    Stepper(
        label = "点击间隔",
        valueText = "${config.intervalMs} ms",
        enabled = editable,
        onMinus = { onConfigChange(config.copy(intervalMs = (config.intervalMs - 500).coerceAtLeast(config.minIntervalMs)).normalized()) },
        onPlus = { onConfigChange(config.copy(intervalMs = (config.intervalMs + 500).coerceAtMost(60_000)).normalized()) },
    )
    Text("最小间隔：${config.minIntervalMs} ms", style = MaterialTheme.typography.bodySmall)

    Stepper(
        label = "按下时长",
        valueText = "${config.pressDurationMs} ms",
        enabled = editable,
        onMinus = { onConfigChange(config.copy(pressDurationMs = (config.pressDurationMs - 10).coerceAtLeast(1)).normalized()) },
        onPlus = { onConfigChange(config.copy(pressDurationMs = (config.pressDurationMs + 10).coerceAtMost(2000)).normalized()) },
    )

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("开启后立即点击一次")
        Switch(
            checked = config.fireImmediately,
            enabled = editable,
            onCheckedChange = { onConfigChange(config.copy(fireImmediately = it)) },
        )
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("来电时：${if (config.onIncomingCall == CallAction.STOP) "停止定时" else "继续运行"}")
        OutlinedButton(
            enabled = editable,
            onClick = {
                val next = if (config.onIncomingCall == CallAction.STOP) CallAction.CONTINUE else CallAction.STOP
                onConfigChange(config.copy(onIncomingCall = next))
            },
        ) { Text("切换") }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("记录点击日志")
        Switch(
            checked = config.loggingEnabled,
            enabled = editable,
            onCheckedChange = { onConfigChange(config.copy(loggingEnabled = it)) },
        )
    }

    Spacer(Modifier.height(8.dp))
    Button(onClick = onShowOverlay, modifier = Modifier.fillMaxWidth()) {
        Text("显示悬浮窗")
    }
    OutlinedButton(
        onClick = { ClickAccessibilityService.instance?.testTapCenter() },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("诊断：3 秒后点屏幕中心（不显示悬浮窗）") }
    Text(
        "排查用：先打开目标 App，回到本页点此按钮，3 秒内切回目标 App，观察屏幕正中是否被点中。",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "在悬浮窗上点击中心圆形按钮开始/停止；拖动上方手柄移动落点；点击 X 退出。",
        style = MaterialTheme.typography.bodySmall,
    )
    LogSection(context = context, isRunning = isRunning)

    if (!batteryOk) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("建议（可选）：允许后台运行 / 关闭省电限制", style = MaterialTheme.typography.titleSmall)
                Text("不设置不影响基本使用，但长时间锁屏/后台运行时可能被系统中断。", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onOpenBattery) { Text("去设置（可选）") }
            }
        }
    }

    if (showStopDialog) {
        AlertDialog(
            onDismissRequest = { showStopDialog = false },
            title = { Text("停止定时？") },
            text = { Text("修改配置需要先停止当前定时点击。是否停止？") },
            confirmButton = {
                TextButton(onClick = { onStopForEdit(); showStopDialog = false }) { Text("停止并修改") }
            },
            dismissButton = {
                TextButton(onClick = { showStopDialog = false }) { Text("继续运行") }
            },
        )
    }
}

@Composable
private fun Stepper(
    label: String,
    valueText: String,
    enabled: Boolean,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(valueText, style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onMinus, enabled = enabled) { Text("−") }
            OutlinedButton(onClick = onPlus, enabled = enabled) { Text("+") }
        }
    }
}

@Composable
private fun LogSection(context: Context, isRunning: Boolean) {
    var refresh by remember { mutableIntStateOf(0) }
    // isRunning 变化（会话开始/结束）或手动刷新时重新枚举日志文件
    val files = remember(refresh, isRunning) { ClickLogger.listLogFiles(context) }
    var selected by remember(files) { mutableStateOf(files.firstOrNull()) }
    var expanded by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("点击日志", style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = { refresh++ }) { Text("刷新") }
            }
            Text("目录：${ClickLogger.logsDir(context).absolutePath}", style = MaterialTheme.typography.bodySmall)

            if (files.isEmpty()) {
                Text("暂无日志文件", style = MaterialTheme.typography.bodyMedium)
            } else {
                Box {
                    OutlinedButton(onClick = { expanded = true }) {
                        Text(selected?.name ?: "选择日志文件")
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        files.forEach { f ->
                            DropdownMenuItem(
                                text = { Text(f.name) },
                                onClick = { selected = f; expanded = false },
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = selected != null,
                        onClick = { selected?.let { shareLog(context, it) } },
                    ) { Text("分享/查看") }
                    OutlinedButton(
                        enabled = selected != null,
                        onClick = { selected?.let { it.delete(); refresh++ } },
                    ) { Text("删除") }
                }
                OutlinedButton(onClick = {
                    files.forEach { it.delete() }
                    refresh++
                }) { Text("清空全部日志") }
            }
        }
    }
}

private fun shareLog(context: Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "分享/查看日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
