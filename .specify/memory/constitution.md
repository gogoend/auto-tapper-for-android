<!--
Sync Impact Report
- Version change: (template, unversioned) → 1.0.0
- Bump rationale: 首次正式批准，由模板占位符填充为具体原则（初始版本计 1.0.0）。
- Principles defined:
  - I. 可测的纯逻辑核心（Testable Pure Core）
  - II. 隐私与最小权限（Privacy & Least Privilege）
  - III. 规格驱动与文档同步（Spec-Driven, Docs in Sync）
  - IV. 平台兼容与真机验证（Platform Compatibility & On-Device Verification）
  - V. 可靠的调度与后台行为（Reliable Scheduling & Background Behavior）
- Added sections: 技术约束与质量标准；开发流程与质量门；Governance
- Removed sections: 无（全部由模板占位符具体化）
- Templates reviewed:
  - .specify/templates/plan-template.md ✅ 兼容（"Constitution Check" 为通用占位，已对齐）
  - .specify/templates/spec-template.md ✅ 兼容（未新增强制章节）
  - .specify/templates/tasks-template.md ✅ 兼容（测试任务为可选，符合原则 I 的"关键不变量须单测"）
  - .specify/templates/commands/*.md ✅ 无需改动
  - 运行指导：specs/001-interval-auto-clicker/{plan,research,quickstart}.md 已与本宪法一致
- Deferred TODOs: 无
-->

# Auto Tapper Constitution

本宪法约束 Auto Tapper（自动连点器）的设计与实现取舍。条款使用 MUST/SHOULD 表达强制力；
冲突时以本文件为最高准则，变更须遵循 Governance。

## Core Principles

### I. 可测的纯逻辑核心（Testable Pure Core）

关键算法与判定逻辑 MUST 与 Android 框架解耦为纯 Kotlin，并以 JUnit 单元测试覆盖其
**不变量**：无堆积调度（FR-013）、稳态间隔精度（SC-001）、扇形倒计时映射（SC-005）、
配置校验与最小间隔钳制（FR-024/030）、控制条边缘/inset 布局（FR-014）。
新增或修改上述逻辑的提交 MUST 附带或更新对应单测，且 `:app:testDebugUnitTest` MUST 通过。
*Rationale*：UI/无障碍/跨应用手势难以自动化，把正确性集中在可单测的纯逻辑里是最可靠的质量保障。

### II. 隐私与最小权限（Privacy & Least Privilege）

应用 MUST 本地运行：无网络请求、无账户体系、不收集或上传用户数据。
仅申请实现核心功能**必需**的授权（无障碍服务/模拟手势；来电检测所需 `READ_PHONE_STATE`）。
可选增强（后台运行、省电豁免）MUST NOT 被伪装为必需，也 MUST NOT 在缺失时阻断核心功能。
*Rationale*：连点器拥有"代替用户点击其他应用"的高权限，最小化授权与零数据外发是用户信任的底线。

### III. 规格驱动与文档同步（Spec-Driven, Docs in Sync）

功能变更 MUST 经由 spec-kit 流程（spec → plan → tasks）并保持文档与实现一致：
任何改变行为/接口/权限的实现，MUST 同步回写 spec.md / plan.md / research.md / tasks.md。
合并前 `/speckit-analyze` MUST 无 CRITICAL/HIGH 不一致，需求覆盖 SHOULD 为 100%。
*Rationale*：本项目反复迭代且踩过平台坑，文档漂移会让后续决策建立在错误前提上。

### IV. 平台兼容与真机验证（Platform Compatibility & On-Device Verification）

实现 MUST 显式考虑 Android 版本/厂商差异（如 Android 16+ 对注入点击与被遮挡触摸的收紧、
快捷方式启用无障碍、厂商私有后台开关），并在 research.md 记录决策与坑（R 系列）。
跨应用手势、悬浮窗、无障碍、后台存活等无法单测的行为 MUST 以**真机**验证为准（quickstart 验收清单）。
MUST NOT 依赖 root 或非公开/不稳定的私有 API 作为核心路径。
*Rationale*：模拟器通过不代表真机通过；本项目的核心能力恰好高度依赖设备与系统版本行为。

### V. 可靠的调度与后台行为（Reliable Scheduling & Background Behavior）

调度 MUST 无任务堆积：延迟/错过的点击按当前时刻重排、丢弃过期点击，绝不补偿性齐发（FR-013）。
中断（来电、锁屏/息屏、屏幕旋转）MUST 按既定规则停止，且停止后 MUST NOT 自动恢复（FR-018/020/027/028）。
后台/锁屏存活为 best-effort，MUST NOT 宣称为强保证；其增强依赖用户可选系统设置。
*Rationale*：无人值守长时间运行是核心场景，可预期、不失控的行为比"尽量多点"更重要。

## 技术约束与质量标准

- 技术栈：Kotlin + Jetpack Compose（配置/权限/诊断/关于界面）；悬浮层用自绘 View；
  AccessibilityService + `dispatchGesture` 为核心运行时；悬浮窗用 `TYPE_ACCESSIBILITY_OVERLAY`
  （不需要 `SYSTEM_ALERT_WINDOW`）。
- 平台：单模块 `:app`，minSdk 26 / targetSdk 36 / compileSdk 37；包名 `com.gogoend.autotapper`。
- 存储：仅用 DataStore 持久化点击配置；准星位置不持久化。
- 可用性判定：无障碍是否启用 MUST 以"服务实际连接（serviceReady）"为准，而非仅匹配系统设置字符串。

## 开发流程与质量门

- 每次提交前：`:app:assembleDebug` MUST 通过；涉及纯逻辑的改动 `:app:testDebugUnitTest` MUST 通过。
- 行为/权限/平台相关改动：MUST 在真机安装验证（参照 quickstart「验收对照」T038 清单）。
- 文档门：行为变更 MUST 同步 spec/plan/tasks/research；定期以 `/speckit-analyze` 校验一致性。
- 提交信息使用约定式前缀（feat/fix/docs/refactor/chore/style），并说明"做了什么、为什么"。

## Governance

本宪法为项目最高准则，优先于其他约定；与之冲突的实现或文档 MUST 调整为符合本宪法
（而非弱化或忽略原则）。

- **修订流程**：原则的新增/移除/重定义须更新本文件，并在 Sync Impact Report 记录差异与受影响模板/文档。
- **版本策略（语义化）**：MAJOR=不兼容的治理/原则移除或重定义；MINOR=新增原则或实质性扩充；
  PATCH=措辞澄清与非语义修订。
- **合规审查**：`/speckit-analyze` 与代码评审 MUST 核对是否符合上述原则；偏离 MUST 在 plan.md 的
  Complexity Tracking（或 PR 描述）中说明理由，否则不予合并。

**Version**: 1.0.0 | **Ratified**: 2026-06-28 | **Last Amended**: 2026-06-28
