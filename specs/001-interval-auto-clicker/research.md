# Phase 0 Research: 自动连点器

关键技术决策。规格无遗留 `[NEEDS CLARIFICATION]`；以下解决实现层面的技术未知项。

## R1. 如何向其他应用派发点击（核心可行性）

- **Decision**: 使用 `AccessibilityService` + `dispatchGesture(GestureDescription)` 在屏幕坐标处派发 tap（`Path` + `StrokeDescription(startTime=0, duration=按下时长)`）。无障碍服务是 Android 上唯一无 root 即可向任意前台应用注入点击的稳定机制。
- **Rationale**: 满足 FR-008/FR-012；`dispatchGesture` 在系统层注入，作用于坐标下方的前台应用（相机等），不经过本应用自身的悬浮窗触摸分发。
- **Alternatives considered**: `Instrumentation.sendPointerSync`（仅同应用内有效）；root + `input tap`（需 root，排除）；MediaProjection（仅截屏，不能注入点击）。

## R2. 悬浮界面与"穿透自身按钮"

- **Decision**: 用 `WindowManager` 添加 `TYPE_APPLICATION_OVERLAY` 窗口承载 Compose（`ComposeView` 配合自建 `LifecycleOwner`/`SavedStateRegistryOwner`/`ViewModelStoreOwner`）。准星视觉层使用 `FLAG_NOT_TOUCHABLE`（永不拦截）。开始/停止按钮需可点击；**在每次自动派发点击的瞬间，临时给控制按钮窗口加 `FLAG_NOT_TOUCHABLE`，派发完成后移除**，从而保证该次注入落到下方目标而非按钮自身。
- **Rationale**: 满足 FR-005/FR-007/FR-009/FR-012；分离"不可触摸的视觉层"与"可触摸的控制层"避免误触；瞬时 pass-through 窗口极短，用户误触概率可忽略。
- **Alternatives considered**: 单一窗口整体可触摸（会拦截注入点击）；把按钮移出准星中心（违反规格"位于准星位置覆盖中心十字"）。
- **Open implementation note**: 由 `AccessibilityService` 持有该悬浮窗（服务为长生命周期组件），而非额外 Activity/Service。

## R3. 运行时承载组件与后台存活

- **Decision**: 以 `ClickAccessibilityService` 作为唯一运行时核心，承载悬浮窗、调度循环、来电/锁屏/旋转监听与手势派发。后台/锁屏存活依赖无障碍服务自身的高存活性；可选地引导用户开启"忽略电池优化/允许后台运行"以进一步降低被回收概率（FR-021 best-effort）。
- **Rationale**: 无障碍服务由系统绑定、存活性高且天然在后台运行；合并职责避免额外前台服务与 IPC 复杂度。配置 Activity 与服务通过轻量方式通信（服务静态引用/`LocalBroadcast` 或绑定）。
- **Alternatives considered**: 独立前台服务（Foreground Service）+ 无障碍服务双组件（更复杂，且仍需无障碍才能注入点击）；WorkManager（不适合亚秒级前台交互）。

## R4. 无堆积的定时调度

- **Decision**: 在服务的协程作用域内运行自重排循环：每次执行点击后，基于 `SystemClock.elapsedRealtime()`（单调时钟）计算到"下一个目标时刻"的剩余时间并 `delay()`；若某次因系统繁忙已错过目标时刻，则**丢弃过期点击、以当前时刻为基准重设下一目标**，绝不补发。倒计时角度由 `(下一目标时刻 - now) / 间隔` 实时计算。
- **Rationale**: 满足 FR-013/SC-001/SC-002；自重排（schedule-after-complete）从根本上杜绝 fixed-rate 的追赶式齐发；单调时钟不受系统时间调整影响。
- **Alternatives considered**: `Handler.postAtTime` 固定速率（会堆积补发）；`Timer/ScheduledExecutorService.scheduleAtFixedRate`（同样追赶）；`AlarmManager` 精确闹钟（需 `SCHEDULE_EXACT_ALARM`，且锁屏唤醒与"锁屏停止"冲突）。

## R5. 来电检测

- **Decision**: 监听通话状态——API 31+ 用 `TelephonyManager.registerTelephonyCallback(CallStateListener)`，API 26–30 用 `PhoneStateListener.onCallStateChanged`，需 `READ_PHONE_STATE` 运行时权限。状态非 `IDLE`（RINGING/OFFHOOK）即视为来电：动作=停止则停止定时并取消任何临近的待派发点击（FR-018）；动作=继续则保持调度（FR-019）。
- **Rationale**: 官方通话状态 API 最可靠。配合"取消临近点击"满足 FR-018 中"恰好跳到电话界面且本应点击时不点击"。
- **Alternatives considered**: 通过无障碍窗口变化检测拨号盘包名（不可靠、厂商差异大）；`READ_CALL_LOG`（过度授权）。
- **Note**: 若用户拒绝 `READ_PHONE_STATE`，来电检测不可用——应在配置项旁提示该后果（来电动作将无法生效），但不阻断核心点击。

## R6. 锁屏/息屏检测

- **Decision**: 服务内注册 `BroadcastReceiver` 监听 `Intent.ACTION_SCREEN_OFF`（息屏）作为停止信号；满足 FR-020。`ACTION_USER_PRESENT`/解锁不自动恢复（FR-028）。
- **Rationale**: 广播即时、可靠；与"不自动恢复"一致。
- **Alternatives considered**: 轮询 `KeyguardManager`/`PowerManager.isInteractive`（耗电、不及时）。

## R7. 屏幕旋转 / 分辨率变化

- **Decision**: 在服务中通过 `onConfigurationChanged` 或监听 `Configuration` 变化检测旋转/尺寸变化；运行中发生则停止定时并提示用户重新确认准星位置（FR-027）。横竖屏分别记忆位置为后续增强，不在本版本。
- **Rationale**: 旋转后绝对坐标失真；停止+提示是本版本最稳妥且明确的行为。
- **Alternatives considered**: 按比例重映射（本版本不做，列为后续）。

## R8. 配置持久化

- **Decision**: Jetpack DataStore（Preferences）持久化 `ClickConfig`（间隔、按下时长、是否立即点击、来电动作）；准星位置不持久化，每次启动重置为默认（屏幕中心）。满足 FR-025。
- **Rationale**: DataStore 为现代推荐方案，协程友好、无 SharedPreferences 的 ANR 风险。
- **Alternatives considered**: SharedPreferences（可用但较旧）；Room（对单一配置过重）。

## R9. 默认"按下时长"与最小间隔

- **Decision**: 默认按下时长取平台轻触量级（约 `ViewConfiguration.getTapTimeout()`，量级数十毫秒，规划取一个常量如 60ms 作默认起点，实现时校准）。最小允许间隔 = 按下时长 + 合理缓冲（建议默认下限 200ms，且始终 ≥ 按下时长 + 50ms），由配置界面 UI 强制（FR-024）。
- **Rationale**: 保证单次点击可完整结束后再进入下一周期，避免重叠（Edge Case）。
- **Alternatives considered**: 不设下限（可能导致点击重叠/堆积）。

## R10. 扇形倒计时渲染

- **Decision**: 悬浮层用 Compose `Canvas.drawArc` 绘制扇形，半径不超出准星圆形轮廓、半透明覆盖中心十字；以 `withFrameNanos` 帧驱动，依据 R4 的"下一目标时刻"实时换算角度（满间隔≈360°→0°）。满足 FR-009/SC-005。
- **Rationale**: 帧驱动 + 基于单调时钟的剩余时间，使视觉与真实调度一致。
- **Alternatives considered**: 固定步进动画（与真实剩余时间易漂移）。

## R11. 权限与首启引导

- **Decision**: 启动时检查：① 悬浮窗 `Settings.canDrawOverlays()`；② 无障碍服务是否启用（读 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 匹配本服务）。任一必需项缺失→引导至对应系统设置且核心功能不可用（FR-022）。可选项：电池优化 `PowerManager.isIgnoringBatteryOptimizations()`、后台运行/省电策略——未满足时弹可选提示并提供跳转本应用系统设置入口，文案说明后果，但不阻断（FR-023）。
- **Rationale**: 这些系统授权无法静默获取，必须跳转系统界面由用户开启。
- **Alternatives considered**: 无（系统强制）。

## 新增依赖小结

- `androidx.datastore:datastore-preferences`
- `org.jetbrains.kotlinx:kotlinx-coroutines-android`
- （测试）现有 JUnit4 即可覆盖纯逻辑；Compose 测试沿用现有 BOM 工件。
