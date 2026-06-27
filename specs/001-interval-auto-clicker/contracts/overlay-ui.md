# Contract: Overlay UI（悬浮层 Compose）

覆盖在其他应用之上的可拖动控制层。由无障碍服务通过 WindowManager 承载。

## 元素（任何状态恒有 — FR-005）

- **准星 Crosshair**：中心十字 + 圆形轮廓（参照 spec 的 UI 原型 SVG）。视觉层 `FLAG_NOT_TOUCHABLE`。
- **拖拽手柄 DragHandle**：拖动整个悬浮层 → 实时更新 `ClickTarget`，准星中心即落点（FR-006）。
- **退出按钮 ExitButton**：`hideOverlayAndExit()`，立即退出并移除悬浮窗（FR-011）。

## 中心控制按钮（随状态切换）

| 状态 | 形态 | 行为 |
|------|------|------|
| STOPPED | 圆形"开始"，半透明，覆盖中心十字、半径不超出圆形轮廓 | 点击 → `startClicking`（FR-007/FR-008） |
| RUNNING | 扇形"停止"，同样半透明、覆盖中心十字、不超轮廓；角度=剩余时间（满间隔≈360°→0°，钟表式收缩） | 点击 → `stopClicking(USER)`（FR-009/FR-010） |

- 扇形角度由 `CountdownModel.remainingFraction()` 帧驱动渲染（R10/SC-005）。

## 屏幕边缘自适应（FR-014 / SC-004）

- 当悬浮层被拖至屏幕边缘/角落，DragHandle、ExitButton、开始/停止按钮需重排，保证全部在可见区域内且可点击（避免被屏幕边界裁切）。
- 契约：给定准星中心坐标与屏幕尺寸，布局算法输出各控件位置，保证 `0 ≤ 控件包围盒 ≤ 屏幕可见区`。这是一个可单测的纯布局函数 `arrangeControls(center, screenSize, insets): ControlLayout`。

## 运行中改配置（FR-026）

- 配置入口在 RUNNING 态被触发时 → 弹窗"是否停止定时以修改配置？"：是→`stopClicking(USER)` 并进入可编辑；否→保持运行、不改。
