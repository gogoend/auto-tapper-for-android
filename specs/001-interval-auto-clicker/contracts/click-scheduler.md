# Contract: ClickScheduler（纯逻辑，可单测）

调度核心，与 Android 框架解耦，便于 JUnit4 单测。负责"无堆积"的下一次点击计时与倒计时换算。

## 接口

```kotlin
class ClickScheduler(
    private val clock: () -> Long,          // 注入 SystemClock.elapsedRealtime（测试可替换）
)

// 开始一个会话；返回首次点击是否应立即发生
fun start(intervalMs: Long, fireImmediately: Boolean): StartPlan
data class StartPlan(val fireNow: Boolean, val nextFireElapsed: Long)

// 一次点击执行完成后调用，计算下一目标时刻（自重排，丢弃过期）
fun onClickCompleted(intervalMs: Long): Long   // 返回新的 nextFireElapsed

// 给定下一目标时刻，返回当前应 delay 的毫秒（≥0；已过期返回 0）
fun delayUntilNext(nextFireElapsed: Long): Long

// 倒计时换算：剩余比例 [0,1]（用于扇形角度 = fraction*360）
fun remainingFraction(nextFireElapsed: Long, intervalMs: Long): Float
```

## 行为契约（→ 单元测试用例）

1. `fireImmediately=true` → `start` 返回 `fireNow=true`，`nextFireElapsed = now + interval`。
2. `fireImmediately=false` → `fireNow=false`，`nextFireElapsed = now + interval`。
3. **无堆积**：若 `onClickCompleted` 时 `now` 已超过上一个目标时刻多个周期，新的 `nextFireElapsed` 必须基于 `now` 重设（= `now + interval`），**不得**累计补发多次（FR-013）。
4. `delayUntilNext`：`nextFireElapsed` 在未来 → 返回正剩余；已过期 → 返回 0（立即执行一次，但不补发历史）。
5. `remainingFraction`：剩余 = 满间隔 → ≈1.0（≈360°）；临近点击 → ≈0.0（≈0°）；钳制到 `[0,1]`（SC-005）。
6. 使用单调时钟，不受系统时间被修改影响。

## 不变量

- 任意时刻最多一个未来 `nextFireElapsed`。
- 相邻两次实际点击间隔在稳态下与 `intervalMs` 偏差 ≤ ±5%（SC-001，受执行耗时影响在此校正）。
