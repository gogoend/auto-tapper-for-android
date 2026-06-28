# Contract: ClickAccessibilityService

核心长生命周期组件。承载悬浮窗（两窗口）、手势派发、调度循环与中断监听。

> 注：本契约已按实际实现修订（见 research R12–R16）。US4 中断监听已实现。

## 声明（AndroidManifest + res/xml）

- `<service>` with `android.permission.BIND_ACCESSIBILITY_SERVICE`，`android.accessibilityservice.AccessibilityService` action。
- `res/xml/accessibility_service_config.xml`：`canPerformGestures="true"`，`accessibilityFlags` 含 `flagDefault`，`description` 说明用途。
- Manifest 权限：`READ_PHONE_STATE`（来电检测，规划）、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（可选）。**不需要 `SYSTEM_ALERT_WINDOW`**——悬浮窗用 `TYPE_ACCESSIBILITY_OVERLAY`（可信系统窗口）。另声明 `FileProvider` 用于分享日志。

## 连接状态（授权判定的权威来源）

- `companion object` 暴露 `serviceReady: StateFlow<Boolean>`、`instance`、`isRunning`、`overlayShown`。
- `onServiceConnected()` 置 `serviceReady=true`、`instance=this`；`onUnbind/onDestroy` 置假并清理。
- 界面以 `serviceReady` 判定无障碍是否真正可用（见 R16），不依赖快捷方式/按钮目标键。

## 对外能力（被配置界面/悬浮层调用）

```kotlin
fun showOverlay(config: ClickConfig)    // 添加准星窗 + 控制条窗（居中/偏移），overlayShown=true
fun hideOverlay()                       // 退出/隐藏：停止点击 + 移除两窗口，但保持服务存活（FR-011 修订）
fun startClicking()                     // FR-008（使用 currentConfig）
fun stopClicking(reason: StopReason)    // 用户/锁屏/来电/旋转/退出
fun updateConfig(config: ClickConfig)
fun testTapCenter()                     // 诊断：移除悬浮窗后向屏幕中心派发一次

// OverlayView/控制条回调：onStartStopTap() / onExitTap() / onDrag(dx,dy)
enum class StopReason { USER, SCREEN_OFF, INCOMING_CALL, CONFIG_CHANGE, EXIT }
```

## 手势派发契约（FR-008/FR-012，Android 16+ 关键）

- 落点坐标用**准星视图 `getLocationOnScreen()` 的实际屏幕中心**（规避 inset 错位，R14）。
- `Path` 必须**非零长度**：`moveTo(x,y)` + `lineTo(x+1,y+1)`（R12），`StrokeDescription(path, 0, pressDurationMs)`。
- **避免 obscured 丢弃**：派发前用 `removeViewImmediate` **同步移除准星窗** → 等待 `OVERLAY_SETTLE_MS`(~90ms) → `dispatchGesture` → `suspendCancellableCoroutine` 等 `onCompleted/onCancelled` → 重新 `addView` 准星窗（R13）。控制条窗偏离落点、不参与移除。

## 调度循环契约（FR-013）

- 在服务协程作用域运行：`start` →（可选立即点）→ loop：到点则 `performTap()`（挂起，含移除/派发/恢复）并 `next = scheduler.onClickCompleted(interval)`；否则按帧更新扇形 `setFraction` 后 `delay`。
- 基于 `SystemClock.elapsedRealtime`，过期不补发（无堆积）。
- `stopClicking` 取消协程。

## 中断监听契约（US4，已实现）

| 信号 | 监听方式 | 动作 |
|------|----------|------|
| 锁屏/息屏 | `BroadcastReceiver(ACTION_SCREEN_OFF)` | `stopClicking(SCREEN_OFF)`（FR-020） |
| 来电 | `TelephonyCallback`/`PhoneStateListener` | state≠IDLE：STOP→`stopClicking(INCOMING_CALL)` 并取消临近 tap；CONTINUE→保持（FR-018/019） |
| 旋转/尺寸变化 | `onConfigurationChanged` | `stopClicking(CONFIG_CHANGE)` + 提示重新确认位置（FR-027） |

- 所有中断停止后**不自动恢复**（FR-028）。
