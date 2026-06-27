# Contract: ClickAccessibilityService

核心长生命周期组件。承载悬浮窗、手势派发、调度循环与中断监听。

## 声明（AndroidManifest + res/xml）

- `<service>` with `android.permission.BIND_ACCESSIBILITY_SERVICE`，`android.accessibilityservice.AccessibilityService` action。
- `res/xml/accessibility_service_config.xml`：`canPerformGestures="true"`，`accessibilityFlags` 含 `flagDefault`，`description` 说明用途。
- Manifest 权限：`SYSTEM_ALERT_WINDOW`、`READ_PHONE_STATE`（来电检测）、`FOREGROUND_SERVICE`（如需）、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（可选）。

## 对外能力（被配置界面/悬浮层调用）

```kotlin
fun showOverlay()                       // 添加准星+控制层悬浮窗
fun hideOverlayAndExit()                // 退出按钮：移除悬浮窗并停止（FR-011）
fun startClicking(config: ClickConfig, target: ClickTarget)  // FR-008
fun stopClicking(reason: StopReason)    // 用户/锁屏/来电/旋转
fun updateTarget(x: Float, y: Float)    // 拖拽实时更新落点（FR-006）

enum class StopReason { USER, SCREEN_OFF, INCOMING_CALL, CONFIG_CHANGE }
```

## 手势派发契约（FR-008/FR-012）

- `dispatchGesture` 在 `target` 坐标派发 tap：`Path.moveTo(x,y)` + `StrokeDescription(path, 0, pressDurationMs)`。
- **穿透自身**：派发前对控制按钮悬浮窗临时加 `FLAG_NOT_TOUCHABLE`，回调完成后恢复（R2）。准星视觉层恒为 `FLAG_NOT_TOUCHABLE`。
- 成功率以 `dispatchGesture` 回调 `onCompleted` 统计（SC-006）。

## 调度循环契约（FR-013）

- 在服务协程作用域运行：`start` → （可选立即点）→ loop：`delay(scheduler.delayUntilNext(next))` → 派发 → `next = scheduler.onClickCompleted(interval)`。
- `stopClicking` 取消协程；若停止原因为来电且正处于"临近派发"，必须取消该次 tap（FR-018）。

## 中断监听契约

| 信号 | 监听方式 | 动作 |
|------|----------|------|
| 锁屏/息屏 | `BroadcastReceiver(ACTION_SCREEN_OFF)` | `stopClicking(SCREEN_OFF)`（FR-020） |
| 来电 | `TelephonyCallback`/`PhoneStateListener` | state≠IDLE：STOP→`stopClicking(INCOMING_CALL)`；CONTINUE→保持（FR-018/019） |
| 旋转/尺寸变化 | `onConfigurationChanged` | `stopClicking(CONFIG_CHANGE)` + 提示重新确认位置（FR-027） |

- 所有中断停止后**不自动恢复**（FR-028）。
