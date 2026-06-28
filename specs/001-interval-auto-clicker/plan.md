# Implementation Plan: 自动连点器（Interval Auto-Clicker）

**Branch**: `001-interval-auto-clicker` | **Date**: 2026-06-27 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/001-interval-auto-clicker/spec.md`

## Summary

一个 Android 全局自动连点器：用户在配置界面设置点击间隔（默认 5000ms）、按下时长、是否首次立即点击、来电动作；通过覆盖在其他应用之上的可拖动悬浮界面（准星 + 拖拽手柄 + 退出 + 开始/停止按钮）指定屏幕点击位置，并按节奏自动点击下方目标应用（如相机快门）。技术上以一个**无障碍服务（AccessibilityService）**作为核心运行时：它既通过 `dispatchGesture()` 向目标应用派发点击（穿透自身悬浮窗），又承载悬浮界面与调度循环；调度采用"每次执行后基于单调时钟重新计算下一次"的自重排方式以避免任务堆积。配置持久化用 DataStore，准星位置不持久化。来电、锁屏/息屏、屏幕旋转作为中断信号停止定时；首启检查并引导必需权限（悬浮窗、无障碍），可选引导后台/省电豁免。

## Technical Context

**Language/Version**: Kotlin 2.2.10（Java 11 desugar 目标）

**Primary Dependencies**: 配置/权限界面用 Jetpack Compose（BOM 2026.02.01, Material3）；**悬浮层用自绘 View**（非 Compose，见 research R15）；AndroidX Activity/Lifecycle、AccessibilityService + WindowManager（`TYPE_ACCESSIBILITY_OVERLAY` 悬浮窗与 `dispatchGesture` 手势派发）、Jetpack DataStore（Preferences）、kotlinx-coroutines（调度循环）

**Storage**: DataStore Preferences（持久化点击配置含日志开关；准星位置不持久化）

**Testing**: JUnit4 单元测试（纯 Kotlin 的调度器、倒计时、边缘布局、配置校验）；无障碍服务与跨应用手势依赖真机/系统授权，以手动验证为主。

**Target Platform**: Android（minSdk 26 / targetSdk 36，**compileSdk 37** —— 既有依赖 core-ktx 1.19.0 等要求 37）

**Project Type**: 单模块 Android 应用（`:app`），package `com.gogoend.intervalclicker`

**Performance Goals**: 间隔精度 ±5%（SC-001）；倒计时动画与界面流畅、无延迟累积导致的批量齐发（SC-002/FR-013）；点击触达成功率 ≥99%（SC-006）

**Constraints**: 注入点击必须真正作用于下方目标——在 Android 16+ 需"派发瞬间移除悬浮窗 + 非零长度路径"（FR-012，见 research R12/R13）；运行期间不可改配置（FR-026）；锁屏/旋转/来电按规则停止（FR-018/020/027/028，**US4 尚未实现**）；后台存活为 best-effort（FR-021）

**权限说明（修订）**: 因悬浮层使用 `TYPE_ACCESSIBILITY_OVERLAY`，**不需要 `SYSTEM_ALERT_WINDOW`**；核心可用性仅取决于无障碍服务是否真正连接（`serviceReady`，见 research R16）。

**Scale/Scope**: 单用户、单点击位置、本地运行、无网络/无账户；界面：配置、权限引导、诊断与日志、悬浮控制层（两窗口）+ 1 个无障碍服务

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

项目宪法 `.specify/memory/constitution.md` 当前仍为未填充模板（占位符，未批准），**未定义任何具体原则或门禁**。因此本次规划没有可校验的硬性门禁。

- **初次评估（Phase 0 前）**: PASS（无定义门禁）。采用通用工程实践：核心逻辑（调度、倒计时、校验）与 Android 框架解耦以便单元测试；权限/隐私遵循最小化（仅悬浮窗、无障碍、READ_PHONE_STATE 用于来电检测，本地存储、无网络）。
- **再次评估（Phase 1 后）**: PASS（设计未引入需要论证的复杂度违规）。
- **建议**: 后续运行 `/speckit-constitution` 正式确立项目原则后再回看本节。

## Project Structure

### Documentation (this feature)

```text
specs/001-interval-auto-clicker/
├── plan.md              # 本文件（/speckit-plan 输出）
├── research.md          # Phase 0 输出：关键技术决策
├── data-model.md        # Phase 1 输出：实体与状态机
├── quickstart.md        # Phase 1 输出：构建/运行/手动验证
├── contracts/           # Phase 1 输出：内部组件契约
│   ├── click-scheduler.md
│   ├── accessibility-service.md
│   └── overlay-ui.md
├── checklists/
│   └── requirements.md  # 规格质量清单（已存在）
└── tasks.md             # Phase 2 输出（由 /speckit-tasks 生成，本命令不创建）
```

### Source Code (repository root)

沿用现有单模块结构 `app/`，在 `com.gogoend.intervalclicker` 下按职责分包：

实际落地结构（单模块 `app/`，package `com.gogoend.intervalclicker`）：

```text
app/src/main/java/com/gogoend/intervalclicker/
├── MainActivity.kt                  # 配置页 + 权限引导 + 诊断与日志页（Compose，全部在此）
├── ui/
│   ├── theme/                       # 既有主题
│   └── overlay/                     # 悬浮层（自绘 View）
│       ├── CrosshairView.kt         # 准星 + 中心开始/停止按钮（落点处，派发瞬间临时移除）
│       ├── ControlBarView.kt        # 控制条：拖拽手柄 + 退出（偏离落点，常驻不闪）
│       └── OverlayLayout.kt         # 纯逻辑：arrangeControls 边缘布局（可单测）
├── service/
│   └── ClickAccessibilityService.kt # 核心：两窗口管理、dispatchGesture、调度循环、serviceReady
├── scheduler/
│   ├── ClickScheduler.kt            # 纯逻辑：自重排调度，避免堆积（可单测）
│   ├── CountdownModel.kt            # 纯逻辑：扇形剩余时间→角度映射（可单测）
│   └── ClickSession.kt              # SessionState / StopReason / ClickSession
├── data/
│   ├── ClickConfig.kt               # 配置模型 + 校验（含最小间隔、loggingEnabled）
│   ├── ClickTarget.kt               # 准星位置（不持久化）
│   └── ConfigRepository.kt          # DataStore 读写
├── logging/
│   └── ClickLogger.kt               # 每会话日志文件（外部专属目录），含坐标
└── permission/
    └── PermissionChecker.kt         # 无障碍(以 serviceReady 为准)/电池状态检查 + 跳转 Intent

app/src/main/AndroidManifest.xml     # 声明 AccessibilityService、READ_PHONE_STATE、FileProvider（SYSTEM_ALERT_WINDOW 已声明但非必需）
app/src/main/res/xml/                # accessibility_service_config.xml、file_paths.xml

app/src/test/java/com/gogoend/intervalclicker/      # JUnit4：ClickScheduler、CountdownModel、OverlayLayout
```

**Structure Decision**: 单模块 Android 应用。核心可测逻辑（`scheduler/`、`ui/overlay/OverlayLayout`、`data/` 校验）与 Android 框架解耦，可在纯 JVM 单元测试验证；无障碍服务作为唯一长生命周期组件承载悬浮窗、手势派发与（规划中的）中断监听。

> **实现差异（相对 Phase 0/1 初始设计，详见 research R12–R16）**：
> 1. 悬浮层为**自绘 View + 两窗口**（非 Compose、非单窗口）；
> 2. 窗口类型 `TYPE_ACCESSIBILITY_OVERLAY`（非 `TYPE_APPLICATION_OVERLAY`），**不需 SYSTEM_ALERT_WINDOW**；
> 3. 点击派发：派发瞬间移除准星窗 + 沉降 + 非零长度路径 + 用 `getLocationOnScreen` 取真实落点；
> 4. 退出按钮 = 隐藏悬浮窗（不 `disableSelf`）；配置页有显示/隐藏切换、独立"诊断与日志"页；
> 5. 无障碍授权判定以 `serviceReady`（服务实际连接）为准；
> 6. **US4（来电/锁屏/旋转中断）尚未实现**；compileSdk 提升至 37。

## Complexity Tracking

> 无宪法门禁违规需要论证，故本节为空。
