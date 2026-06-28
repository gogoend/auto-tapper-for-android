package com.gogoend.autotapper.permission

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import com.gogoend.autotapper.service.ClickAccessibilityService

/**
 * 权限与系统设置状态检查（data-model.md / FR-022 / FR-023）。
 * 必需：无障碍服务（以实际连接 serviceReady 为准）；可选：电池优化豁免。
 * 悬浮层用 TYPE_ACCESSIBILITY_OVERLAY，无需 SYSTEM_ALERT_WINDOW。
 */
object PermissionChecker {

    fun isAccessibilityEnabled(context: Context): Boolean {
        // 最可靠的信号：服务是否已被系统绑定/连接（无论通过开关、"辅助功能快捷方式"或辅助功能按钮启用）。
        if (ClickAccessibilityService.serviceReady.value) return true

        // 退路：扫描多个相关系统设置项（普通开关 + 快捷方式 + 辅助功能按钮目标），
        // 并按长/短两种组件名格式匹配（避免格式或键不一致导致误判）。
        val component = ComponentName(context, ClickAccessibilityService::class.java)
        val expectedFull = component.flattenToString()
        val expectedShort = component.flattenToShortString()
        return ACCESSIBILITY_SETTINGS_KEYS.any { key ->
            settingsListContains(context, key, expectedFull, expectedShort)
        }
    }

    private fun settingsListContains(
        context: Context,
        key: String,
        expectedFull: String,
        expectedShort: String,
    ): Boolean {
        val value = Settings.Secure.getString(context.contentResolver, key) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(value)
        while (splitter.hasNext()) {
            val entry = splitter.next()
            if (entry.equals(expectedFull, ignoreCase = true) ||
                entry.equals(expectedShort, ignoreCase = true)
            ) {
                return true
            }
        }
        return false
    }

    // 仅检查"已启用服务"列表：被指派到快捷方式/辅助功能按钮 ≠ 服务正在运行，
    // 那两类目标键不能作为"已授权"依据（否则会出现界面显示可用但服务未连接、显示悬浮窗无效）。
    // 服务是否真正可用以 serviceReady（实际连接）为准。
    private val ACCESSIBILITY_SETTINGS_KEYS = listOf(
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    )

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 必需权限是否齐备——为 false 时核心功能不可用（FR-022）。
     * 悬浮层使用 TYPE_ACCESSIBILITY_OVERLAY（由无障碍服务添加的可信系统窗口），
     * 不需要 SYSTEM_ALERT_WINDOW，因此核心可用性仅取决于无障碍服务是否启用。
     */
    fun isCoreUsable(context: Context): Boolean = isAccessibilityEnabled(context)

    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun batteryOptimizationSettingsIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 应用详情页——用于引导用户开启"允许后台运行/自启动"等厂商私有开关（无标准入口）。 */
    fun appDetailsSettingsIntent(context: Context): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
