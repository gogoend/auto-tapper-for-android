# Contract: Overlay UI（悬浮层，自绘 View / 两窗口）

覆盖在其他应用之上的悬浮控制层。由无障碍服务通过 WindowManager 以 `TYPE_ACCESSIBILITY_OVERLAY` 承载。

> 注：实现为**自绘 View**（非 Compose），分为两个窗口（见 research R15）。

## 窗口划分

- **准星窗 CrosshairView**（落点处）：十字 + 圆形轮廓 + 中心开始/停止按钮。窗口可触摸（仅中心按钮区域消费触摸）；**每次派发点击的瞬间被临时移除**（避免遮挡注入点击）。
- **控制条窗 ControlBarView**（偏离落点）：拖拽手柄 + 退出按钮。常驻、不参与移除（不闪烁），也不会被注入点击误触。

## 元素（任何状态恒有 — FR-005）

- **准星**：中心十字 + 圆形轮廓（参照 spec 的 UI 原型 SVG）。
- **中心开始/停止按钮**（在准星正中，符合 PRD）：
  - STOPPED：半透明**圆形**"开始"，覆盖中心十字、半径不超圆形轮廓 → 点击 `onStartStopTap()` → `startClicking`（FR-007/FR-008）。
  - RUNNING：半透明**扇形**"停止"，角度=剩余时间（满间隔≈360°→0°，钟表式收缩）→ 点击 `onStartStopTap()` → `stopClicking(USER)`（FR-009/FR-010）。扇形由 `CountdownModel.sweepAngleDegrees()` 帧驱动（SC-005）。
- **拖拽手柄**（控制条）：拖动同时移动准星窗与控制条窗 → 实时更新 `ClickTarget`，准星中心即落点（FR-006）。
- **退出按钮**（控制条）：`onExitTap()` → `hideOverlay()`——隐藏悬浮窗并停止计时，但**保留服务存活**以便再次显示（FR-011 修订）。

## 屏幕边缘自适应（FR-014 / SC-004）

- 控制条窗放在准星上/下方**可用空间更大**的一侧；夹取到安全区内（支持上/下 inset），保证完整可见可点、且不与落点竖直重叠。
- 纯布局函数 `placeControlBar(target, crosshairSize, controlW/H, screenW/H, gap, topInset, bottomInset): LPoint` 可单测（已覆盖四角/四边/中心与 inset）。

## 运行中改配置（FR-026）

- 配置在 RUNNING 态由配置页拦截：编辑控件禁用，"修改配置（需先停止）"→ 弹窗"是否停止定时以修改配置？"：是→`stopClicking(USER)` 并进入可编辑；否→保持运行、不改。

## 显示/隐藏（FR-029）

- 配置页提供切换按钮，文案随状态：隐藏→"显示悬浮窗"；显示且未计时→"隐藏悬浮窗"；显示且计时中→"隐藏悬浮窗并停止计时"。与悬浮窗退出按钮状态一致（`overlayShown`）。
