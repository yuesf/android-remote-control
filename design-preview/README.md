# 信号台 SIGNAL DECK · 设计稿交付说明

这是**设计提案预览**，不是对 App 源码的改动。本目录下所有文件均为新增，`app/src/` 下没有任何文件被修改。

## 一、怎么用

### 1. 看效果
直接用浏览器打开 `index.html`（双击即可，无需服务器、无需联网）。页面内嵌全部 CSS/JS，不依赖任何 CDN。

- 顶部 `浅色 / 深色 / 会话` 三个按钮可实时切换「首页 / 设备页」预览配色；「首页深色」与「会话页」按规范恒定锁定，不随切换变化。
- 预览区含 4 部手机 mockup：首页浅色（带被控常驻条）、设备页浅色（本机码 + 200×200 二维码 + 在线列表 + 被控能力 + 自检）、首页深色、会话页（锁屏黑屏提示）。
- 预览下方有「实现约束」条，逐条标注 fontScale 降级、完整状态文案、被控条细节、呼吸归属、按钮宽度锁定、二维码 quiet zone。
- 再往下依次为 Token 色板（浅色 / 深色 / 语义状态 / 会话专属四组，含 HEX）、字体层级表、组件状态（配对格四态 + 双重编码徽章）、状态与文案矩阵。

### 2. 落地到 Android 工程

| 本目录文件 | 目标路径 | 说明 |
| --- | --- | --- |
| `tokens/colors.xml` | `app/src/main/res/values/colors.xml` | 浅色 `md_*` + 语义 `sem_*` + 会话常量 `sess_*` |
| `tokens/colors-night.xml` | `app/src/main/res/values-night/colors.xml` | 同名 token 的深色值（新建 values-night 目录） |
| `tokens/themes.xml` | `app/src/main/res/values/themes.xml` | 主题、语义 attr、TextAppearance、动效时长片段，**合并**进现有文件 |

落地注意事项：

1. **命名分三档**：`md_*` 基础 Material 色、`sem_*` 语义状态色、`sess_*` 会话常量色。三套文件用**同名 token**，禁止 `_light/_dark` 后缀做运行时判断。
2. **layout / drawable 禁止硬编码 HEX**，一律 `?attr/xxx` 或 `@color/token`。语义色走 `?attr/semOnline` / `?attr/semLost` 等，不得拿 `colorError` 顶替「失联」。
3. **会话常量色只定义一次。** `sess_viewport`、`sess_controlbar`、`sess_badge_bg/on`、`sess_badge_off_bg/on` 永远深色，只在 `values/colors.xml`；`values-night/` 不要重复定义，资源合并时自动继承。
4. **深色派生值已由视觉侧确认（原派生值两处已替换）**：`md_secondary`=#A9B9BE（不是 #A3B3B8）、`md_on_secondary`=#1B282C、`md_secondary_container`=#2A3B40（不是 #232E31）、`md_on_secondary_container`=#C4D5D9；浅色配套 `md_secondary`=#47565C / `md_on_secondary`=#FFFFFF、`md_secondary_container`=#DFE8EA / `md_on_secondary_container`=#29383D。`sem_connecting` 深色=#4ED3D6 派生正确保持不变，三件套补齐：浅 #0B6B73 / #D6EEF0 / #06383D，深 #4ED3D6 / #14434A / #C9F4F5。被控条文字色 `sem_controlled_bar_on` 为视觉侧增量：浅 #7A2305 / 深 #FFB287。
   **使用边界**：`md_secondary*` 只用于中强调文字与图标，不得承载任何状态语义，状态一律走 `sem_*`；占位「–」的 40% 透明度基于 `onSurfaceVariant`，不是 secondary。
5. **主题合并不要丢文件。** 现有 `Theme.RemoteControl.Transparent`（屏幕采集授权空壳，必须全透明）不在本片段中，合并时保留原样。
6. **旧色迁移。** 现有 `brand_primary` / `brand_primary_dark` / `brand_surface` / `brand_outline` 可在所有引用切到新 token 后删除；迁移期可并存。
7. **字体。** 中文 `Noto Sans SC → sans-serif`，数字 `Roboto Mono → monospace`。需像素级一致时把字体文件放进 `res/font/` 并改 `fontFamily`；`DisplayCode` 与 `CodeMeta` 已写入 `fontFeatureSettings="tnum"`。
8. **窗口背景。** 所有 Activity 的 `windowBackground` 必须与 surface 同色，否则启动白闪；会话主题用 `@color/sess_viewport`。不要用 `configChanges` 绕 DayNight 重建。
9. **卡片边界。** elevation = 0，用 `outlineVariant` 1dp 描边定义边界；禁止大投影、渐变、弹簧。触控目标 ≥48dp。

### 3. 组件与响应式硬数值

- **配对码格**：四位各 64×80dp、gap 8dp、radius 12dp；字符 40sp/500 Mono + tnum。空态 `surfaceVariant` + 1.5dp `outline`；聚焦 2dp `primary` + 1dp 12% 光晕；已填 `primaryContainer` 底 + `onPrimaryContainer` 字；错误 2dp `error` + 下方 12sp 错误文案。连接按钮 48dp 高 / radius 12dp（非胶囊），「连接中…」换图标时**宽度锁定**。
- **状态徽章**：24dp 高、radius 12dp、11sp/600，色 + 图形 + 文字三要素齐全。在线实心绿点、离线空心灰点、连接中虚线弧、失联带斜杠点、被控橙底文字。后缀「在线·未授权」沿用单色 `sem_online`，**橙独占被控语义**。
- **fontScale > 1.3**：首页「最近连接」与设备页在线列表由一行两列降为**一行一列**，按钮撑满卡片宽度、状态文字恢复单行。这是**布局降级、不是字号降级**——11sp 绝不降到 10sp 以下，状态形状不可被挤掉；此规则优先于「大屏仍保持两列」。
- **完整状态文案**：设备页列表**每一行**用 ≥14sp 完整文案（如「在线，未授权」），卡片才允许短后缀（如「在线·受限」）；详情页只做能力细节展开。TalkBack 连后缀一起完整读「在线，未授权」，不受视觉截断影响。
- **被控常驻条**：三 tab 状态栏下 24dp，浅底 #FBE3D5 / 字 #7A2305 / 点 #A8340C，深底 #3A1E12 / 字 #FFB287 / 点 #FF9159；12sp/600 + 8dp 实心点；右侧 16dp `chevron_end` + 12sp「被控详情」，48×48dp 透明命中 slab 且不遮挡页面首个可点元素。0↔24dp / 240ms FastOutSlowIn **推开内容**而非悬浮遮挡；**不呼吸、不可滑动关闭**。
- **会话页**：非 DayNight、永久深色。进入时先叠 200ms 黑色 12% scrim，再 300ms 淡入；退出 240ms 淡出。呼吸只属于会话页 32dp 被控角标（alpha 0.82↔1 / 1600ms）。二维码 200×200dp + 12dp 白 quiet zone，**深色模式仍白底，绝不反色**。底部导航 72dp，选中态为图标下方 32×4dp 主色圆角指示条（非 Material 整块胶囊底），顶部 1dp 分割线、无阴影。
- **减少动态效果**：系统开启后所有循环动效静态化，但状态图形与文字保留、不丢失信息。

## 二、关键改动清单（相对现状）

- 主色由 `#185FA5` 蓝切换为 `#0B6B73` 青，建立浅 / 深 / 会话三套完整 token；会话页独立于系统深浅色。
- 状态从「单一颜色/文字」改为**双重编码**：在线实心绿点、离线空心灰点、连接中虚线弧、失联带斜杠点、被控橙底白点，每种状态都带文字。
- 状态文字从「在线」改为**两段式**：`在线·可被控` / `在线·未授权` / `在线·已关闭被控` / `离线`；设备页列表行用 ≥14sp 完整展开，卡片允许短后缀。
- 新增**被控常驻条**（三 tab 顶部，推开内容，不可关闭/滑动关闭）；呼吸仅保留在会话页被控角标。
- 配对码输入改为四位分段格（64×80dp、40sp Mono、四态）；连接按钮 48dp / radius12 / 右侧箭头，换图标宽度锁定。
- 设备页信息重组：本机码大字 + 二维码（200×200 + 12dp 白 quiet zone） + 在线设备列表 + 被控能力三件套 + 自检。
- 连接失败改为页面内常驻错误条，区分「码不存在」与「设备离线」；只保留「复制指引」，删除「提醒对方」（项目无此通道）。
- 补齐空态文案（无历史连接、无在线设备）与锁屏黑屏两段说明。
- 会话页固定深色全屏：三个 48dp 控制按钮 + 右上橙色被控角标 + 状态提示区；进出转场按 scrim/fade 时序。

## 三、验收要点（Phase 2 关键 8 条）

1. **信息架构完整**：三 tab 内容齐全——首页含标题 + 机型 + 信令状态行、配对码输入 + 连接按钮、最近连接一行两列；设备页含本机码 + 二维码 + 在线列表 + 被控能力三件套 + 自检；设置页含信令表单 + 标注「本地偏好」的锁屏保持/提示开关/超时 + 诊断。
2. **被控常驻条**：三 tab 任意页状态栏下出现 24dp 条「被控中·控制方：<设备名>」；右侧 16dp chevron_end + 12sp「被控详情」、48×48dp 命中且不遮挡首个可点元素；0↔24dp / 240ms 推开内容；不可关闭、不可滑动关闭、不随滚动、不呼吸。
3. **两段式状态文字**：设备状态为 `在线·可被控` / `在线·未授权` / `在线·已关闭被控` / `离线` 之一，禁止只显示「在线」；设备页列表每行 ≥14sp 完整展开（如「在线，未授权」），卡片才允许短后缀；TalkBack 读完整文案。
4. **失败文案可区分**：`配对码 K7M2 不存在，请向对方确认` 与 `设备 38 当前离线，对方可能未开机或应用未运行` 按原因分别展示；错误用页面内常驻条，不用 Toast；仅提供「复制指引」。
5. **配对码与连接按钮规格**：四位分段格 64×80dp、gap8、radius12、40sp 等宽 tnum；空 / 聚焦（2dp primary + 1dp 12% 光晕）/ 已填 / 错误（2dp error + 12sp 文案）四态齐全；连接按钮 48dp / radius12 / 14sp600 / 右箭头，换图标宽度锁定。
6. **状态双重编码**：在线实心绿、离线空心灰、连接中虚线弧、失联带斜杠点、被控橙底，每态图形 + 文字齐全且跨页一致；「橙」只表达被控；fontScale > 1.3 时一行两列降一行一列、11sp 不降级、形状不被挤掉。
7. **会话页**：非 DayNight 永久深色全屏，顶部返回 / 主页 / 多任务三个 48dp 按钮，右上橙色被控角标（仅此处 1600ms alpha 呼吸）；锁屏黑屏展示 `对方屏幕已锁定，请让对方在本机解锁` + `控制端无法远程解锁，这是系统安全限制`；二维码深色下仍白底。
8. **空态与无障碍**：无历史显示 `还没有连接过设备。在上方输入对方的四位配对码（字母+数字）开始。`；无在线显示 `暂无在线设备` + `对方上线后会自动出现在这里`；开启减少动态后循环动效静态化，但状态图形与文字仍保留、不丢失信息。

## 四、文件清单

```
design-preview/
├── index.html              # 自包含预览页（主题切换 / 4 部手机 mockup / 实现约束 / 色板 / 字阶 / 组件 / 文案）
├── README.md               # 本说明
└── tokens/
    ├── colors.xml          # md_* 浅色 + sem_* 语义 + sess_* 会话常量
    ├── colors-night.xml    # md_* / sem_* 深色值（sess_* 不重复）
    └── themes.xml          # Theme.RemoteControl / Session / 语义 attr / TextAppearance / 动效时长
```
