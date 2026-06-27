# Phase 1 Data Model: 自动连点器

来源：spec.md 的 Key Entities 与功能需求。本应用为本地单用户、无网络，"数据模型"即内存中的运行态对象与少量持久化配置。

## Entity: ClickConfig（点击配置 — 持久化）

| 字段 | 类型 | 默认 | 校验 / 说明 |
|------|------|------|-------------|
| `intervalMs` | Long | 5000 | ≥ `minIntervalMs`（FR-001/FR-024）。UI 强制下限 |
| `pressDurationMs` | Long | 60 | > 0；平台轻触量级（R9）。`< intervalMs` |
| `fireImmediately` | Boolean | true | 开始后是否立即点一次（FR-003/FR-016） |
| `onIncomingCall` | enum `CallAction` | `STOP` | `STOP` / `CONTINUE`（FR-017） |

- 派生：`minIntervalMs = max(200, pressDurationMs + 50)`（R9）。
- 持久化：DataStore Preferences（FR-025）。整体可序列化为 4 个键。
- 校验规则集中在 `ClickConfig.validate()`：返回是否合法 + 规范化后的值（钳制到下限）。

`CallAction`：`STOP`（停止定时）｜`CONTINUE`（继续运行）。

## Entity: ClickTarget（点击位置 — 不持久化）

| 字段 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `x` | Float | 屏幕宽/2 | 准星中心屏幕绝对坐标（px） |
| `y` | Float | 屏幕高/2 | 同上 |

- 运行中可由拖拽实时更新（FR-006），更新后续点击落点（US2 场景 3）。
- 不持久化，每次启动重置为屏幕中心（FR-025）。

## Entity: ClickSession（点击会话 — 运行态，内存）

| 字段 | 类型 | 说明 |
|------|------|------|
| `state` | enum `SessionState` | 见状态机 |
| `nextFireElapsed` | Long | 下一次点击的目标时刻（`SystemClock.elapsedRealtime` 基准，R4） |
| `clickCount` | Int | 已执行点击次数 |
| `remainingFraction` | Float (派生) | `(nextFireElapsed - now) / intervalMs`，→ 扇形角度（FR-009/SC-005） |

### SessionState 状态机

```text
        ┌──────────────────────────────────────────────┐
        │                                              │
        ▼                                              │
   [STOPPED] ──(点击"开始" 且必需权限就绪)──▶ [RUNNING] ──(点击"停止")──┘
        ▲                                       │
        │                                       │ 中断信号：
        │                                       │  • 锁屏/息屏 (FR-020)
        │                                       │  • 来电且 onIncomingCall=STOP (FR-018)
        │                                       │  • 屏幕旋转/尺寸变化 (FR-027)
        └───────────────────────────────────────┘
              （停止后不自动恢复，需手动重新"开始" — FR-028）

  RUNNING 时来电且 onIncomingCall=CONTINUE → 保持 RUNNING（FR-019）
```

- **STOPPED → RUNNING**：仅当悬浮窗 + 无障碍权限就绪（否则引导授权，FR-022）。进入时若 `fireImmediately` 则立刻派发一次并将 `nextFireElapsed = now + interval`；否则 `nextFireElapsed = now + interval`（FR-016）。
- **RUNNING → STOPPED**：用户点停止，或任一中断信号。中断停止时若有"临近/正在派发"的点击且来电场景，需取消该次（FR-018）。
- 配置仅在 STOPPED 可编辑；RUNNING 时尝试编辑 → 弹窗确认是否停止（FR-026）。

## Entity: PermissionStatus（权限与系统设置状态 — 运行态查询）

| 字段 | 类型 | 必需性 | 说明 |
|------|------|--------|------|
| `canDrawOverlay` | Boolean | 必需 | `Settings.canDrawOverlays()`（FR-022） |
| `accessibilityEnabled` | Boolean | 必需 | 本无障碍服务是否启用（FR-022） |
| `readPhoneStateGranted` | Boolean | 条件必需 | 来电检测需要；缺失则来电动作不生效（R5） |
| `ignoringBatteryOpt` | Boolean | 可选 | 电池优化豁免（FR-023，best-effort） |

- `isCoreUsable = canDrawOverlay && accessibilityEnabled`。为 false 时核心功能（开始）不可用并持续引导。
