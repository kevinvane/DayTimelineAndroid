# 死代码检查豁免清单（PRD §12.4 / T5 / D20）

技术方案 §5.2 要求：死代码检查必须用**显式豁免清单**，而不是全局关闭规则，
且「豁免清单本身要进代码走查」。本文件就是那份清单。

## 使用规则

1. **先证明存在反射入口**，再登记豁免。凭「感觉会用」不算。
2. 豁免必须写明**入口在哪**（哪个 XML、哪个反射调用点）。
3. 能用更窄的方式解决就不要豁免——例如把成员改成 `internal` 并被同模块引用，
   或直接删掉。
4. 新增条目前请确认它不是「上一轮忘了删」的伪装。

## 机制

`library/build.gradle.kts` 的 `verifyNoDeadCode` 任务会扫描 detekt XML 报告里
是否命中以下规则，命中即构建失败：

```
UnusedPrivateMember / UnusedPrivateProperty / UnusedPrivateClass
UnusedParameter / UnusedImports / UnusedReturnValue
```

本清单目前**为空**，即零豁免。若将来需要豁免，请在 detekt 配置中用
`@Suppress` 就地标注（带本文件链接），并在下方登记一行——
比在全局配置里写白名单更容易走查。

## 当前条目

（无）

### 已知但尚未触发豁免的情况

| 情况 | 现状 | 处理 |
|---|---|---|
| `DayTimelineView` 的 XML 注入属性（`app:dtXxx`） | `ConfigFromAttrs` 走 `obtainStyledAttributes`，由 AGP 生成 setter，detekt 能看到引用 | 未触发豁免 |
| `TimelineAccessibilityHelper` | 由 `ViewCompat.setAccessibilityDelegate` 传入，有静态引用 | 未触发豁免 |

若后续 Android 版本或 R8 混淆导致误报，**先考虑改代码（加静态引用），
再考虑登记豁免**。