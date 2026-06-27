# Implementation Plan: 自动连点器（Interval Auto-Clicker）

**Branch**: `001-interval-auto-clicker` | **Date**: 2026-06-27 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/001-interval-auto-clicker/spec.md`

## Summary

一个 Android 全局自动连点器：用户在配置界面设置点击间隔（默认 5000ms）、按下时长、是否首次立即点击、来电动作；通过覆盖在其他应用之上的可拖动悬浮界面（准星 + 拖拽手柄 + 退出 + 开始/停止按钮）指定屏幕点击位置，并按节奏自动点击下方目标应用（如相机快门）。技术上以一个**无障碍服务（AccessibilityService）**作为核心运行时：它既通过 `dispatchGesture()` 向目标应用派发点击（穿透自身悬浮窗），又承载悬浮界面与调度循环；调度采用"每次执行后基于单调时钟重新计算下一次"的自重排方式以避免任务堆积。配置持久化用 DataStore，准星位置不持久化。来电、锁屏/息屏、屏幕旋转作为中断信号停止定时；首启检查并引导必需权限（悬浮窗、无障碍），可选引导后台/省电豁免。

## Technical Context

**Language/Version**: Kotlin 2.2.10（Java 11 desugar 目标）

**Primary Dependencies**: Jetpack Compose（BOM 2026.02.01, Material3）、AndroidX Activity/Lifecycle、AccessibilityService + WindowManager（系统悬浮窗与手势派发）、Jetpack DataStore（Preferences）、kotlinx-coroutines（调度循环）

**Storage**: DataStore Preferences（仅持久化点击配置；准星位置不持久化）

**Testing**: JUnit4 单元测试（纯 Kotlin 的调度器与倒计时数学、配置校验）；Compose UI 测试与少量 Espresso 仪器测试（配置/权限界面）。无障碍服务与跨应用手势依赖真机/系统授权，以手动验证为主。

**Target Platform**: Android（minSdk 26 / targetSdk 36，compileSdk 36）

**Project Type**: 单模块 Android 应用（`:app`），package `com.gogoend.intervalclicker`

**Performance Goals**: 间隔精度 ±5%（SC-001）；倒计时动画与界面流畅、无延迟累积导致的批量齐发（SC-002/FR-013）；点击触达成功率 ≥99%（SC-006）

**Constraints**: 必须穿透自身半透明悬浮按钮、点击作用于下方目标（FR-012）；运行期间不可改配置（FR-026）；锁屏/旋转/来电按规则停止（FR-018/020/027/028）；后台存活为 best-effort（FR-021），依赖用户开启可选系统设置

**Scale/Scope**: 单用户、单点击位置、本地运行、无网络/无账户；约 3 个界面（配置、权限引导、悬浮控制层）+ 1 个无障碍服务

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

```text
app/src/main/java/com/gogoend/intervalclicker/
├── MainActivity.kt                  # 配置界面 + 权限引导宿主（已存在，待扩展）
├── ui/
│   ├── theme/                       # 既有主题
│   ├── config/                      # 配置界面 Composable + 状态
│   ├── onboarding/                  # 首启权限/省电引导 Composable
│   └── overlay/                     # 悬浮层 Composable：准星、扇形开始/停止、拖拽手柄、退出
├── service/
│   └── ClickAccessibilityService.kt # 核心：悬浮窗管理、dispatchGesture、调度循环、来电/锁屏/旋转监听
├── scheduler/
│   ├── ClickScheduler.kt            # 纯逻辑：自重排调度，避免堆积（可单测）
│   └── CountdownModel.kt            # 纯逻辑：扇形剩余时间→角度映射（可单测）
├── data/
│   ├── ClickConfig.kt               # 配置数据模型 + 校验（含最小间隔）
│   └── ConfigRepository.kt          # DataStore 读写
└── permission/
    └── PermissionChecker.kt         # 悬浮窗/无障碍/省电状态检查 + 跳转 Intent

app/src/main/AndroidManifest.xml     # 声明 AccessibilityService、SYSTEM_ALERT_WINDOW、READ_PHONE_STATE 等
app/src/main/res/xml/                # accessibility_service_config.xml

app/src/test/java/com/gogoend/intervalclicker/      # JUnit4：ClickScheduler、CountdownModel、ClickConfig 校验
app/src/androidTest/java/com/gogoend/intervalclicker/ # Compose/Espresso：配置与权限界面
```

**Structure Decision**: 单模块 Android 应用，保持现有 `:app` 模块与 `com.gogoend.intervalclicker` 包名。核心可测逻辑（`scheduler/`、`data/` 校验）与 Android 框架（`service/`、`ui/`）分离，使调度与倒计时数学可在纯 JVM 单元测试中验证；无障碍服务作为唯一长生命周期组件统一承载悬浮窗、手势派发与中断监听。

## Complexity Tracking

> 无宪法门禁违规需要论证，故本节为空。
