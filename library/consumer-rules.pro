# DayTimeline 组件的混淆保留规则（PRD §12.5 / T7）
#
# 组件以 AAR 形式发布，业务方开启混淆后功能必须正常。
# 本文件通过 library/build.gradle.kts 的 consumerProguardFiles 打包进 AAR，
# 对接入方**自动生效**，业务方无需任何配置。
#
# ⚠️ 禁止在此处使用 `-dontwarn` 或全局 `-keep class **` 粗暴掩盖问题：
# 那会让「业务方混淆后功能正常」这一条失去验证意义。

# ---- 自定义 View：Android 框架通过 XML 反射实例化，混淆后类名会变导致 inflate 失败 ----
#
# 这里对成员用 `*;` 全量保留，而非逐个列举，原因是 Kotlin 的**默认参数**：
# 带默认值的函数会合成一个 `xxx$default` 静态桥接方法，业务方调用
# `submitEvents(list)` 时编译产物走的就是它。逐个列举公开方法会漏掉这类
# 合成成员，混淆后表现为 NoSuchMethodError: submitEvents$default(...)。
# 这也是 :r8test 在混淆变体上跑仪器测试时实际抓到的第二个缺陷。
#
# DayTimelineView 是本 AAR 唯一的对外入口类，全量保留它的成员开销可接受。
-keep public class com.github.kevinvane.daytimeline.library.DayTimelineView {
    # @JvmOverloads + 默认参数合成的构造器，**Kotlin 调用方实际走的就是它们**
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public <init>(android.content.Context, android.util.AttributeSet, int, int);
    public <init>(android.content.Context, android.util.AttributeSet, int, int, kotlin.jvm.internal.DefaultConstructorMarker);
    *;
}

# ---- 对外契约接口：业务方实现后传入，泛型擦除后需保留签名 ----
#
# `$DefaultImpls` 也要保：Kotlin 接口的**默认方法体**会被编译进同名的
# `Xxx$DefaultImpls` 合成类，业务方只覆盖部分回调时运行期仍会加载它。
# 只 `-keep interface` 保住了接口本身，这个合成类照样被裁掉，表现为
# NoClassDefFoundError: Xxx$DefaultImpls。（:r8test 实测抓到的第三个缺陷。）
-keep public interface com.github.kevinvane.daytimeline.library.core.TimelineEvent { *; }
-keep public interface com.github.kevinvane.daytimeline.library.api.TimelineListener { *; }
-keep public interface com.github.kevinvane.daytimeline.library.api.GridPainter { *; }
-keep public interface com.github.kevinvane.daytimeline.library.api.EventBlockPainter { *; }
-keep public interface com.github.kevinvane.daytimeline.library.DayTimelineView$EditController { *; }

-keep class com.github.kevinvane.daytimeline.library.core.TimelineEvent$* { *; }
-keep class com.github.kevinvane.daytimeline.library.api.TimelineListener$* { *; }
-keep class com.github.kevinvane.daytimeline.library.api.GridPainter$* { *; }
-keep class com.github.kevinvane.daytimeline.library.api.EventBlockPainter$* { *; }
-keep class com.github.kevinvane.daytimeline.library.DayTimelineView$* { *; }

# ---- 表单契约（AD-22 / PRD §7.7.1）----
# EditDraft / EditResult 是数据类，业务方在自己代码里读 .range / .content /
# .isCreating / .event。同上：出现在 EditController 的公开方法签名里，
# 类名必须保留，不能用 -keepclassmembers。
-keep class com.github.kevinvane.daytimeline.library.api.EditDraft { *; }
-keep class com.github.kevinvane.daytimeline.library.api.EditResult { *; }

# ---- 详情快照（AD-23）----
# EventDetail 同理：业务方要读 .range / .content / .color / .startMinute，
# 且 DayTimelineView.detailOf() 的返回类型就是它。
-keep class com.github.kevinvane.daytimeline.library.api.EventDetail { *; }
-keep class com.github.kevinvane.daytimeline.library.api.EventDetailKt { *; }

# ---- 定制上下文：业务方在 paint() 中读取字段，字段名被混淆会破坏其实现 ----
#
# ⚠️ 这里必须用 `-keep class` 而不是 `-keepclassmembers class`：
# 后者只保留**成员**，R8 仍然会重命名**类本身**。而这三个类型出现在
# `EventBlockPainter.paint(...)` / `GridPainter.paint(...)` 的**公开方法签名**里，
# 业务方实现时要按名字写出来——类名被改就等于对外 API 变了。
# 这条是 :r8test 模块（PRD §12.5 / T7）首次运行时实际抓到的缺陷。
-keep class com.github.kevinvane.daytimeline.library.api.GridContext { *; }
-keep class com.github.kevinvane.daytimeline.library.api.BlockContext { *; }
-keep class com.github.kevinvane.daytimeline.library.api.TimelineColors { *; }

# ---- 值类型：公开数据契约，业务方（含 Java 调用方）要读字段、调工厂 ----
#
# MinuteOfDay 自 OQ-7 决议起是普通类（不再是 @JvmInline value class），
# 它的成员 `minuteOfDay` / `hour` / `minute` / `of` / `ofMinute` / `parse`
# 都是**对外可调可读的公开 API**，必须与 EditDraft / EventDetail 同规格保留。
#
# 此前这里是 `-keepnames class`（只保类名、不顾成员），理由是 value class
#「擦除为 int，保留名称便于日志」。该规则对单遍 R8 的业务方恰好够用——
# 业务方代码与库在同一个 R8 pass 里，成员改名两边一致。但它挡不住
# **跨 APK 边界**：androidTest APK 是独立的一次 R8，测试代码按原名调用库成员，
# 库成员在 app APK 里已被改名，运行即 NoSuchMethodError。
# :r8test 的 javaCallerCanImplementContractAfterMinification 实测抓到过这一条。
#
# ⚠️ 这里同样必须用 `-keep class ... { *; }`：`-keepnames` 与
# `-keepclassmembers` 都不同时满足「成员不被删」与「成员不被改名」。
-keep class com.github.kevinvane.daytimeline.library.core.MinuteOfDay { *; }

# ---- 数据异常（DataIssue）以数据类形式回调给业务方做日志记录 ----
-keepclassmembers class com.github.kevinvane.daytimeline.library.core.DataIssue { *; }

# ---- 资源：自定义 View 引用了本库资源，混淆不应影响，但保留 attr 便于崩溃栈定位 ----
-keepclassmembers class com.github.kevinvane.daytimeline.library.R$* { *; }
