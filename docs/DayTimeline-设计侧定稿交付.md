# 设计侧定稿交付

原则：**不改名、不合项、不拆项；17 项 × 深浅两套 = 34 个值全部给设计终稿；背景纯色；第 14 项透明度写进 hex；深浅色名称完全一致。**

> 说明：这组值按“日程时间轴”产品语义定稿：
> - 浅色偏清爽、低饱和、层级靠明度和色相区分；
> - 深色不是简单反相，而是把蓝色提亮、降饱和，保证暗底可读；
> - 已过态仍然弱化，但用蓝灰而非紫灰，保持整体冷色统一；
> - 主强调色从紫色改为蓝色，进行中/未到/选中/编辑手柄统一走蓝色家族；
> - 当前时间线仍用红，避免和蓝色强调语义混淆。

---

## DayTimeline 蓝色系设计定稿色值（34 项）

| # | 资源名 | 浅色定稿 | 深色定稿 | 设计说明 |
|---|---|---|---|---|
| 1 | `day_timeline_background` | `#FFFFFF` | `#101318` | 浅色纯白；深色近黑带一点蓝灰，避免纯黑死板。必须纯色。 |
| 2 | `day_timeline_grid_line` | `#E4E8EE` | `#343A44` | 浅色弱于文字但可见；深色比背景亮一档，仍弱于文字。 |
| 3 | `day_timeline_axis_label` | `#55606E` | `#D3DAE3` | 浅色正常对比；深色提亮，保证时间刻度可读。 |
| 4 | `day_timeline_now` | `#D92D20` | `#FF8A80` | 当前时间线红，浅色高辨识；深色提亮珊瑚红，暗底可读。 |
| 5 | `day_timeline_block_bg_past` | `#F1F4F7` | `#1B2027` | 已过块背景弱化，浅色蓝灰白，深色比背景略亮但低饱和。 |
| 6 | `day_timeline_block_bg_ongoing` | `#DCEBFF` | `#1E4F8F` | 进行中块背景突出，浅色淡蓝；深色中深蓝承载白字。 |
| 7 | `day_timeline_block_bg_upcoming` | `#EEF2F6` | `#262C35` | 未到块常规中性，浅色浅蓝灰，深色深蓝灰。 |
| 8 | `day_timeline_block_text_past` | `#949CA6` | `#8A929C` | 已过块文字低对比，体现失效；深浅都明显弱于常规文字。 |
| 9 | `day_timeline_block_text` | `#1D232B` | `#EDF1F5` | 进行中/未到常规文字；深色提亮到近白。 |
| 10 | `day_timeline_block_accent_past` | `#C4CBD4` | `#454C56` | 已过色条弱化，浅色蓝灰，深色灰蓝。 |
| 11 | `day_timeline_block_accent` | `#2F6FED` | `#8AB8FF` | 进行中/未到色条高辨识；浅色品牌蓝，深色提亮淡蓝。 |
| 12 | `day_timeline_block_stroke` | `#E8ECF1` | `#454C56` | 默认不描边，仅自定义绘制/业务方使用；浅色极淡，深色微亮。 |
| 13 | `day_timeline_selected` | `#1D5FD6` | `#A8CBFF` | 选中态强调，与常规态明显可辨；浅色更深蓝，深色更亮蓝。 |
| 14 | `day_timeline_edit_layer_bg` | `#66FFFFFF` | `#66101318` | 编辑层半透明；透明度写进取值，浅色 40% 白，深色 40% 近黑。 |
| 15 | `day_timeline_edit_layer_text` | `#1B2129` | `#F2F5F8` | 编辑层文字与底色强对比。 |
| 16 | `day_timeline_edit_layer_time` | `#2A66E0` | `#A8CBFF` | 编辑态起止时间与底色协调；深色提亮。 |
| 17 | `day_timeline_edit_handle` | `#2A66E0` | `#A8CBFF` | 编辑手柄高辨识；深色提亮，保证暗底可见。 |

---

## 交付格式（可直接替换）

### 浅色

```xml
<color name="day_timeline_background">#FFFFFF</color>
<color name="day_timeline_grid_line">#E4E8EE</color>
<color name="day_timeline_axis_label">#55606E</color>
<color name="day_timeline_now">#D92D20</color>
<color name="day_timeline_block_bg_past">#F1F4F7</color>
<color name="day_timeline_block_bg_ongoing">#DCEBFF</color>
<color name="day_timeline_block_bg_upcoming">#EEF2F6</color>
<color name="day_timeline_block_text_past">#949CA6</color>
<color name="day_timeline_block_text">#1D232B</color>
<color name="day_timeline_block_accent_past">#C4CBD4</color>
<color name="day_timeline_block_accent">#2F6FED</color>
<color name="day_timeline_block_stroke">#E8ECF1</color>
<color name="day_timeline_selected">#1D5FD6</color>
<color name="day_timeline_edit_layer_bg">#66FFFFFF</color>
<color name="day_timeline_edit_layer_text">#1B2129</color>
<color name="day_timeline_edit_layer_time">#2A66E0</color>
<color name="day_timeline_edit_handle">#2A66E0</color>
```

### 深色

```xml
<color name="day_timeline_background">#101318</color>
<color name="day_timeline_grid_line">#343A44</color>
<color name="day_timeline_axis_label">#D3DAE3</color>
<color name="day_timeline_now">#FF8A80</color>
<color name="day_timeline_block_bg_past">#1B2027</color>
<color name="day_timeline_block_bg_ongoing">#1E4F8F</color>
<color name="day_timeline_block_bg_upcoming">#262C35</color>
<color name="day_timeline_block_text_past">#8A929C</color>
<color name="day_timeline_block_text">#EDF1F5</color>
<color name="day_timeline_block_accent_past">#454C56</color>
<color name="day_timeline_block_accent">#8AB8FF</color>
<color name="day_timeline_block_stroke">#454C56</color>
<color name="day_timeline_selected">#A8CBFF</color>
<color name="day_timeline_edit_layer_bg">#66101318</color>
<color name="day_timeline_edit_layer_text">#F2F5F8</color>
<color name="day_timeline_edit_layer_time">#A8CBFF</color>
<color name="day_timeline_edit_handle">#A8CBFF</color>
```

---

## 设计侧确认

- ✅ 17 项全部覆盖，无改名、无合并、无拆分。
- ✅ 深浅两套名称完全一致。
- ✅ 第 1 项 `day_timeline_background` 为纯色，满足 R3 背景遮挡前提。
- ✅ 第 14 项透明度已写进 hex：浅色 `#66FFFFFF`，深色 `#66101318`。
- ✅ 主强调色为蓝色系：`#2F6FED / #1D5FD6 / #2A66E0` 为浅色蓝，`#8AB8FF / #A8CBFF` 为深色提亮蓝。
- ✅ 当前时间线保留红色 `#D92D20 / #FF8A80`，避免与蓝色强调语义混淆。
- ✅ 所有值均为设计定稿值，不再沿用 Material 3 baseline 占位值。
- ✅ 组件代码无需改动，仅替换 34 个十六进制值即可。
