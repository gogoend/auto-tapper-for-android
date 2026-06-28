---
description: "Task list for 自动连点器 (Interval Auto-Clicker) implementation"
---

# Tasks: 自动连点器（Interval Auto-Clicker）

**Input**: Design documents from `specs/001-interval-auto-clicker/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: 仅对纯逻辑组件（调度器、倒计时、边缘布局、配置校验）包含单元测试——这些是关键不变量（尤其 FR-013 无堆积、SC-005 倒计时一致性）的最可靠验证方式，且 plan/quickstart 已明确其测试策略。UI/无障碍/跨应用手势以手动验收为主（见 quickstart.md）。

**Organization**: 任务按用户故事分组，每个故事可独立实现与验证。

> **实现落地差异（务必参见 plan.md「实现差异」与 research R12–R16）**：悬浮层最终为**自绘 View + 两窗口**（`ui/overlay/CrosshairView.kt`、`ControlBarView.kt`），窗口类型 `TYPE_ACCESSIBILITY_OVERLAY`（非 `TYPE_APPLICATION_OVERLAY`、无需 `SYSTEM_ALERT_WINDOW`）；配置/权限/诊断界面均在 `MainActivity.kt`（非独立 `ui/config`、`ui/onboarding` 文件）；点击派发采用"移除准星窗 + 沉降 + 非零路径 + getLocationOnScreen"；退出=隐藏；无障碍判定以 serviceReady 为准。下文勾选项的文件名/技术描述以此为准修订。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: 所属用户故事（US1–US5）
- 包名根 `com.gogoend.autotapper`，源码根 `app/src/main/java/com/gogoend/autotapper/`，单测根 `app/src/test/java/com/gogoend/autotapper/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 依赖与包结构

- [X] T001 在 `gradle/libs.versions.toml` 添加 kotlinx-coroutines-android 与 androidx.datastore:datastore-preferences 的 version/library 条目
- [X] T002 在 `app/build.gradle.kts` 的 dependencies 中引入 T001 的 coroutines 与 datastore 工件
- [X] T003 [P] 创建包目录骨架：`service/`、`scheduler/`、`data/`、`permission/`、`ui/config/`、`ui/onboarding/`、`ui/overlay/`（在 `app/src/main/java/com/gogoend/autotapper/` 下）

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 所有用户故事共享的核心基础设施

**⚠️ CRITICAL**: 本阶段完成前，任何用户故事不可开工

- [X] T004 [P] 定义 `ClickConfig`（intervalMs/pressDurationMs/fireImmediately/onIncomingCall）、`CallAction` 枚举，及 `validate()`/最小间隔钳制（`minIntervalMs = max(200, pressDurationMs+50)`）在 `data/ClickConfig.kt`（依据 data-model.md / FR-024）
- [X] T005 [P] 定义 `ClickTarget`（x/y，默认屏幕中心，不持久化）在 `data/ClickTarget.kt`
- [X] T006 实现 `ConfigRepository`（DataStore Preferences 读写 ClickConfig，FR-025）在 `data/ConfigRepository.kt`（依赖 T004）
- [X] T007 [P] 实现 `PermissionChecker`（canDrawOverlays、无障碍是否启用、READ_PHONE_STATE、电池优化豁免；提供跳转 Intent）在 `permission/PermissionChecker.kt`
- [X] T008 配置 `app/src/main/AndroidManifest.xml`：声明 `SYSTEM_ALERT_WINDOW`、`READ_PHONE_STATE` 权限与 AccessibilityService（`BIND_ACCESSIBILITY_SERVICE`）；新增 `app/src/main/res/xml/accessibility_service_config.xml`（`canPerformGestures=true`）
- [X] T009 实现 `ClickAccessibilityService` 骨架 + WindowManager 悬浮窗宿主（`TYPE_APPLICATION_OVERLAY`，ComposeView 配 Lifecycle/SavedState/ViewModelStore owners）在 `service/ClickAccessibilityService.kt`（依赖 T008）
- [X] T010 [P] 定义 `SessionState` 枚举与 `ClickSession` 运行态持有者（state/nextFireElapsed/clickCount）在 `scheduler/ClickSession.kt`（依据 data-model.md 状态机）

**Checkpoint**: 基础就绪——用户故事可开始

---

## Phase 3: User Story 1 - 在指定位置定时自动点击 (Priority: P1) 🎯 MVP

**Goal**: 设置间隔→准星对准目标→开始，按节奏在目标位置自动点击；可停止；支持首次立即点击。

**Independent Test**: 手动授予悬浮窗+无障碍权限后，设间隔 5000ms，准星对准可见目标，点开始 → 每 5 秒点击一次；点停止 → 不再点击（quickstart 路径 3–5）。

### Tests for User Story 1

- [X] T011 [P] [US1] 单元测试 `ClickScheduler`：fireImmediately、`delayUntilNext`、**无堆积**（错过多周期后基于 now 重设而非补发，FR-013）在 `app/src/test/java/com/gogoend/autotapper/ClickSchedulerTest.kt`

### Implementation for User Story 1

- [X] T012 [P] [US1] 实现 `ClickScheduler`（注入时钟、start/onClickCompleted/delayUntilNext/remainingFraction，自重排）在 `scheduler/ClickScheduler.kt`（contracts/click-scheduler.md）
- [X] T013 [US1] 在服务中实现手势派发：`dispatchGesture` 在 target 处 tap（duration=pressDurationMs），派发瞬间临时给控制按钮窗口加 `FLAG_NOT_TOUCHABLE` 后恢复（穿透自身，FR-012）于 `service/ClickAccessibilityService.kt`（依赖 T009）
- [X] T014 [US1] 在服务中实现调度循环与 `startClicking/stopClicking`：协程 loop（delay→派发→onClickCompleted），fireImmediately 处理，启动时准星重置为屏幕中心于 `service/ClickAccessibilityService.kt`（依赖 T012, T013）
- [X] T015 [P] [US1] 实现准星 Composable（中心十字 + 圆形轮廓，所在窗口 `FLAG_NOT_TOUCHABLE`）在 `ui/overlay/Crosshair.kt`（参照 spec UI 原型）
- [X] T016 [US1] 实现圆形"开始"按钮（半透明、覆盖十字、不超轮廓）、基础"停止"按钮与退出按钮 Composable 在 `ui/overlay/OverlayControls.kt`（依赖 T015）
- [X] T017 [US1] 接线：悬浮层开始/停止/退出 → `startClicking`/`stopClicking(USER)`/`hideOverlayAndExit`（FR-008/010/011）在 `service/ClickAccessibilityService.kt` 与 `ui/overlay/`（依赖 T014, T016）
- [X] T018 [US1] 实现最小配置界面（间隔输入 + 是否立即点击）并在 `MainActivity.kt` 承载、读写 `ConfigRepository` 在 `ui/config/ConfigScreen.kt`（依赖 T006）
- [X] T019 [US1] 实现"运行中尝试改配置→弹窗确认是否停止"（是→stop 并可编辑，否→保持运行不改，FR-026）在 `ui/config/ConfigScreen.kt`（依赖 T017, T018）

**Checkpoint**: US1 可独立运行——MVP 达成

---

## Phase 4: User Story 5 - 首启权限与保活引导 (Priority: P1)

**Goal**: 缺必需权限时引导授权且核心功能不可用；可选地提示后台/省电豁免（忽略不阻断）。

**Independent Test**: 未授权设备首启 → 被引导至系统设置且无法开始；已授必需权限但未开省电 → 弹可选提示，关闭后仍可正常配置与开始（quickstart 路径 1–2）。

### Implementation for User Story 5

- [X] T020 [US5] 实现首启权限门禁：检测悬浮窗+无障碍（PermissionChecker），缺失则展示引导并跳转系统设置、禁用"开始"入口直至授予（FR-022）在 `ui/onboarding/PermissionGateScreen.kt` 与 `MainActivity.kt`（依赖 T007）
- [X] T021 [US5] 实现可选后台/省电豁免提示：检测未豁免时弹窗说明后果并提供跳转本应用系统设置入口，可忽略不阻断核心功能（FR-023）在 `ui/onboarding/BackgroundHintDialog.kt`（依赖 T007）

**Checkpoint**: 首启引导完整；权限就绪即可使用 US1

---

## Phase 5: User Story 2 - 实时调整点击位置 (Priority: P2)

**Goal**: 拖动悬浮层实时改变点击落点；拖到屏幕边缘时各控件重排保持可见可点。

**Independent Test**: 拖动手柄移动准星到屏幕中央/四边/四角，确认落点随准星变化，且所有控件完整可见可点（SC-004）。

### Tests for User Story 2

- [X] T022 [P] [US2] 单元测试 `arrangeControls`：四边/四角输入下各控件包围盒落在屏幕可见区内（FR-014/SC-004）在 `app/src/test/java/com/gogoend/autotapper/OverlayLayoutTest.kt`

### Implementation for User Story 2

- [X] T023 [P] [US2] 实现纯布局函数 `arrangeControls(center, screenSize, insets): ControlLayout`（边缘自适应）在 `ui/overlay/OverlayLayout.kt`（contracts/overlay-ui.md）
- [X] T024 [US2] 实现拖拽手柄 Composable，拖动更新整个悬浮层位置并实时 `updateTarget(x,y)`（FR-006）在 `ui/overlay/DragHandle.kt` 与 `service/ClickAccessibilityService.kt`（依赖 T009, T017）
- [X] T025 [US2] 在悬浮层布局中应用 `arrangeControls`（拖到边缘重排控件）在 `ui/overlay/OverlayControls.kt`（依赖 T023, T024）

**Checkpoint**: US1 + US2 各自可独立工作

---

## Phase 6: User Story 4 - 中断与生命周期处理 (Priority: P2)

**Goal**: 来电（按设置停/继续，停止模式下不点电话界面）、锁屏停止、旋转停止并提示；停止后不自动恢复。

**Independent Test**: 运行中模拟来电（默认停止）验证停止且不点电话界面；改继续验证仍点击；锁屏验证停止；旋转验证停止并提示（quickstart 验收表）。

### Implementation for User Story 4

- [X] T026 [P] [US4] 注册 `BroadcastReceiver(ACTION_SCREEN_OFF)` → `stopClicking(SCREEN_OFF)`，解锁不自动恢复（FR-020/FR-028）在 `service/ClickAccessibilityService.kt`
- [X] T027 [US4] 实现来电检测：API31+ `TelephonyCallback`、API26–30 `PhoneStateListener`；state≠IDLE 时按 onIncomingCall STOP→停止并取消临近待派发点击 / CONTINUE→保持（FR-018/019）在 `service/ClickAccessibilityService.kt`（依赖 T014）
- [X] T028 [US4] 处理 `onConfigurationChanged`（旋转/尺寸变化）→ `stopClicking(CONFIG_CHANGE)` 并提示用户重新确认准星位置（FR-027）在 `service/ClickAccessibilityService.kt`（依赖 T014）
- [X] T029 [US4] 配置界面新增"来电动作"（STOP/CONTINUE）并在需要时发起 `READ_PHONE_STATE` 运行时授权请求、缺失时提示后果（R5）在 `ui/config/ConfigScreen.kt`（依赖 T018）

**Checkpoint**: 中断处理稳健，长时间运行可靠

---

## Phase 7: User Story 3 - 点击节奏可视化与精细配置 (Priority: P3)

**Goal**: 扇形倒计时（满间隔≈360°→0°，钟表式收缩）与按下时长配置；UI 强制最小间隔。

**Independent Test**: 运行中观察扇形随时间均匀收缩到 0 并在归零触发点击；修改按下时长后单次点击时长随之变化（spec US3 场景）。

### Tests for User Story 3

- [X] T030 [P] [US3] 单元测试 `CountdownModel.remainingFraction`：满间隔≈1.0、临近≈0.0、钳制 [0,1]（SC-005）在 `app/src/test/java/com/gogoend/autotapper/CountdownModelTest.kt`

### Implementation for User Story 3

- [X] T031 [P] [US3] 实现 `CountdownModel.remainingFraction(nextFireElapsed, intervalMs)` 在 `scheduler/CountdownModel.kt`
- [X] T032 [US3] 将"停止"按钮升级为扇形渲染（Compose `Canvas.drawArc`，`withFrameNanos` 帧驱动，半径不超轮廓、半透明覆盖十字，FR-009）在 `ui/overlay/OverlayControls.kt`（依赖 T031, T016）
- [X] T033 [US3] 配置界面新增"按下时长"并在 UI 强制最小间隔（间隔 ≥ minIntervalMs，FR-024）在 `ui/config/ConfigScreen.kt`（依赖 T018）

**Checkpoint**: 全部用户故事独立可用

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: 跨故事完善

- [X] T034 [P] 填写无障碍服务用途说明字符串、应用图标与文案于 `app/src/main/res/values/strings.xml` 及相关资源
- [X] T035 [P] 补充边缘 insets 单测（状态栏/导航栏/刘海区域下 `arrangeControls` 行为）在 `app/src/test/java/com/gogoend/autotapper/OverlayLayoutTest.kt`
- [~] T036 间隔精度验证（SC-001）。**逻辑层已覆盖**：`ClickSchedulerTest.steadyStateIntervalWithinFivePercent` 模拟调度循环验证稳态间隔 ±5% 且无漂移。**待办（真机）**：设 5s 跑 10 分钟，用诊断日志比对相邻 CLICK 时间戳。
- [X] T037 代码清理与重构（统一 service 与 UI 的状态流、移除样板）跨 `service/`、`ui/`
- [~] T038 手动验收。**已就绪**：quickstart.md「验收对照（T038）」清单已按当前实现刷新，可逐项勾选。**待办（真机）**：实际跑一遍并勾选。

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，立即开始
- **Foundational (Phase 2)**: 依赖 Setup；**阻塞所有用户故事**
- **User Stories (Phase 3–7)**: 均依赖 Foundational 完成
  - 优先级顺序：US1 (P1) → US5 (P1) → US2 (P2) → US4 (P2) → US3 (P3)
  - 充足人力下，Foundational 完成后各故事可并行
- **Polish (Phase 8)**: 依赖目标故事完成

### User Story Dependencies

- **US1 (P1)**: 仅依赖 Foundational；MVP 核心
- **US5 (P1)**: 仅依赖 Foundational（PermissionChecker）；与 US1 解耦（开发期可手动授权先测 US1）
- **US2 (P2)**: 依赖 Foundational；扩展 US1 的悬浮层（T017）
- **US4 (P2)**: 依赖 Foundational；扩展 US1 的调度（T014）
- **US3 (P3)**: 依赖 Foundational；升级 US1 的停止按钮（T016）与配置界面（T018）

### Within Each User Story

- 单测（如有）先于对应实现
- 模型 → 服务 → 接线/UI
- 故事完成再进入下一优先级

### Parallel Opportunities

- Setup：T003 可与 T001/T002 之后并行
- Foundational：T004、T005、T007、T010 标 [P] 可并行；T006 依赖 T004，T009 依赖 T008
- US1：T011/T012 与 T015 可并行；T013/T014 串行（同一服务文件）
- 各故事内：标 [P] 的单测与纯逻辑、不同文件的 Composable 可并行

---

## Parallel Example: User Story 1

```bash
# 纯逻辑与其单测、独立 Composable 并行：
Task: "T011 单元测试 ClickScheduler in app/src/test/.../ClickSchedulerTest.kt"
Task: "T012 实现 ClickScheduler in scheduler/ClickScheduler.kt"
Task: "T015 实现准星 Composable in ui/overlay/Crosshair.kt"
# 注意：T013/T014/T017 同改 ClickAccessibilityService.kt，需串行
```

---

## Implementation Strategy

### MVP First (User Story 1)

1. 完成 Phase 1 Setup
2. 完成 Phase 2 Foundational（关键，阻塞一切）
3. 完成 Phase 3 US1
4. **停下并验证**：手动授权后独立验证 US1 定时点击
5. 可演示 MVP

### Incremental Delivery

1. Setup + Foundational → 基础就绪
2. + US1 → 验证 → 演示（MVP）
3. + US5 → 完整首启引导（无需手动授权）
4. + US2 → 拖动与边缘自适应
5. + US4 → 来电/锁屏/旋转稳健性
6. + US3 → 扇形倒计时与按下时长
7. 每个故事增量交付且不破坏既有

---

## Notes

- [P] = 不同文件、无未完成依赖
- 同改 `ClickAccessibilityService.kt` 的任务（T013/T014/T017/T024/T026/T027/T028）须串行
- 验证单测先失败再实现纯逻辑
- 建议每完成一个任务或逻辑组就提交
- 任一 Checkpoint 可停下独立验证
- 跨应用手势、悬浮窗、无障碍以真机手动验收为主（quickstart.md）
