# 设计侧定稿交付

原则：**不改名、不合项、不拆项；17 项 × 深浅两套 = 34 个值全部给设计终稿；背景纯色；第 14 项透明度写进 hex；深浅色名称完全一致。**

> 说明：这组值按“日程时间轴”产品语义定稿：  
> - 浅色偏清爽、低饱和、层级靠明度和色相区分；  
> - 深色不是简单反相，而是重新调亮文字/强调色，保证可读；  
> - 已过态统一弱化，进行中态用品牌紫强调，未到态保持常规中性；  
> - 当前时间线用高辨识红，深色提亮为珊瑚红，避免刺眼但保持警示性。

---

## DayTimeline 设计定稿色值（34 项）

| # | 资源名 | 浅色定稿 | 深色定稿 | 设计说明 |
|---|---|---|---|---|
| 1 | `day_timeline_background` | `#FFFFFF` | `#121014` | 浅色纯白，保证日程块和网格干净；深色近黑带一点暖紫，避免纯黑死板。必须纯色。 |
| 2 | `day_timeline_grid_line` | `#E8E5EA` | `#3A3740` | 浅色弱于文字但可见；深色比背景亮一档，仍弱于文字。 |
| 3 | `day_timeline_axis_label` | `#5A5560` | `#D8D3DC` | 浅色正常对比；深色提亮，保证时间刻度可读。 |
| 4 | `day_timeline_now` | `#D92D20` | `#FF8A80` | 当前时间红线/圆点/文字，浅色高辨识红；深色提亮珊瑚红，暗底可读。 |
| 5 | `day_timeline_block_bg_past` | `#F3F1F4` | `#1E1B22` | 已过块背景弱化，浅色近灰白，深色比背景略亮但低饱和。 |
| 6 | `day_timeline_block_bg_ongoing` | `#EDE3FF` | `#5B3FA3` | 进行中块背景突出，浅色淡紫；深色用中深紫承载白字。 |
| 7 | `day_timeline_block_bg_upcoming` | `#F0EEF2` | `#2A2730` | 未到块常规中性，浅色浅灰紫，深色深灰紫。 |
| 8 | `day_timeline_block_text_past` | `#9A949E` | `#8F8994` | 已过块文字低对比，体现失效；深浅都明显弱于常规文字。 |
| 9 | `day_timeline_block_text` | `#211E24` | `#F0ECF2` | 进行中/未到常规文字；深色提亮到近白。 |
| 10 | `day_timeline_block_accent_past` | `#C9C4CC` | `#4A4550` | 已过色条弱化，浅色浅灰，深色灰紫。 |
| 11 | `day_timeline_block_accent` | `#7A4FD6` | `#C9A7FF` | 进行中/未到色条高辨识；浅色品牌紫，深色提亮淡紫。 |
| 12 | `day_timeline_block_stroke` | `#ECE9EF` | `#4A4550` | 默认不描边，仅自定义绘制/业务方使用；浅色极淡，深色微亮。 |
| 13 | `day_timeline_selected` | `#5B2FC9` | `#D6B8FF` | 选中态强调，与常规态明显可辨；浅色更深紫，深色更亮紫。 |
| 14 | `day_timeline_edit_layer_bg` | `#66FFFFFF` | `#66121014` | 编辑层半透明；透明度写进取值，浅色 40% 白，深色 40% 近黑。 |
| 15 | `day_timeline_edit_layer_text` | `#1F1B24` | `#F5F1F7` | 编辑层文字与底色强对比。 |
| 16 | `day_timeline_edit_layer_time` | `#6B3FD4` | `#D8B8FF` | 编辑态起止时间与底色协调；深色提亮。 |
| 17 | `day_timeline_edit_handle` | `#6B3FD4` | `#D8B8FF` | 编辑手柄高辨识；深色提亮，保证暗底可见。 |

---

## 交付格式（可直接替换）

### 浅色

```xml
<color name="day_timeline_background">#FFFFFF</color>
<color name="day_timeline_grid_line">#E8E5EA</color>
<color name="day_timeline_axis_label">#5A5560</color>
<color name="day_timeline_now">#D92D20</color>
<color name="day_timeline_block_bg_past">#F3F1F4</color>
<color name="day_timeline_block_bg_ongoing">#EDE3FF</color>
<color name="day_timeline_block_bg_upcoming">#F0EEF2</color>
<color name="day_timeline_block_text_past">#9A949E</color>
<color name="day_timeline_block_text">#211E24</color>
<color name="day_timeline_block_accent_past">#C9C4CC</color>
<color name="day_timeline_block_accent">#7A4FD6</color>
<color name="day_timeline_block_stroke">#ECE9EF</color>
<color name="day_timeline_selected">#5B2FC9</color>
<color name="day_timeline_edit_layer_bg">#66FFFFFF</color>
<color name="day_timeline_edit_layer_text">#1F1B24</color>
<color name="day_timeline_edit_layer_time">#6B3FD4</color>
<color name="day_timeline_edit_handle">#6B3FD4</color>
```

### 深色

```xml
<color name="day_timeline_background">#121014</color>
<color name="day_timeline_grid_line">#3A3740</color>
<color name="day_timeline_axis_label">#D8D3DC</color>
<color name="day_timeline_now">#FF8A80</color>
<color name="day_timeline_block_bg_past">#1E1B22</color>
<color name="day_timeline_block_bg_ongoing">#5B3FA3</color>
<color name="day_timeline_block_bg_upcoming">#2A2730</color>
<color name="day_timeline_block_text_past">#8F8994</color>
<color name="day_timeline_block_text">#F0ECF2</color>
<color name="day_timeline_block_accent_past">#4A4550</color>
<color name="day_timeline_block_accent">#C9A7FF</color>
<color name="day_timeline_block_stroke">#4A4550</color>
<color name="day_timeline_selected">#D6B8FF</color>
<color name="day_timeline_edit_layer_bg">#66121014</color>
<color name="day_timeline_edit_layer_text">#F5F1F7</color>
<color name="day_timeline_edit_layer_time">#D8B8FF</color>
<color name="day_timeline_edit_handle">#D8B8FF</color>
```

---

## 设计侧确认

- ✅ 17 项全部覆盖，无改名、无合并、无拆分。  
- ✅ 深浅两套名称完全一致。  
- ✅ 第 1 项 `day_timeline_background` 为纯色，满足 R3 背景遮挡前提。  
- ✅ 第 14 项透明度已写进 hex：浅色 `#66FFFFFF`，深色 `#66121014`。  
- ✅ 所有值均为设计定稿值，不再沿用 Material 3 baseline 占位值。  
- ✅ 组件代码无需改动，仅替换 34 个十六进制值即可。