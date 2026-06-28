package com.gogoend.intervalclicker.permission

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import com.gogoend.intervalclicker.service.ClickAccessibilityService

/**
 * 权限与系统设置状态检查（data-model.md / FR-022 / FR-023）。
 * 必需：悬浮窗 + 无障碍；可选：电池优化豁免。
 */
object PermissionChecker {

    fun canDrawOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun isAccessibilityEnabled(context: Context): Boolean {
        // 最可靠的信号：服务是否已被系统绑定/连接（无论通过开关还是"辅助功能快捷方式"启用）。
        if (ClickAccessibilityService.serviceReady.value) return true

        // 退路：读取系统设置项并按长/短两种组件名格式匹配（避免格式不一致导致误判）。
        val component = ComponentName(context, ClickAccessibilityService::class.java)
        val expectedFull = component.flattenToString()
        val expectedShort = component.flattenToShortString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
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

    fun overlaySettingsIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun appDetailsSettingsIntent(context: Context): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun batteryOptimizationSettingsIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
