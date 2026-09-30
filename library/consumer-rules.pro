# DayTimeline 组件的混淆保留规则（PRD §12.5 / T7）
#
# 组件以 AAR 形式发布，业务方开启混淆后功能必须正常。
# 本文件通过 library/build.gradle.kts 的 consumerProguardFiles 打包进 AAR，
# 对接入方**自动生效**，业务方无需任何配置。
#
# ⚠️ 禁止在此处使用 `-dontwarn` 或全局 `-keep class **` 粗暴掩盖问题：
# 那会让「业务方混淆后功能正常」这一条失去验证意义。

# ---- 自定义 View：Android 框架通过 XML 反射实例化，混淆后类名会变导致 inflate 失败 ----
-keep public class com.github.kevinvane.daytimeline.library.DayTimelineView {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
    *** get*();
}

# ---- 对外契约接口：业务方实现后传入，泛型擦除后需保留签名 ----
-keep public interface com.github.kevinvane.daytimeline.library.core.TimelineEvent { *; }
-keep public interface com.github.kevinvane.daytimeline.library.api.TimelineListener { *; }
-keep public interface com.github.kevinvane.daytimeline.library.api.GridPainter { *; }
-keep public interface com.github.kevinvane.daytimeline.library.api.EventBlockPainter { *; }
-keep public interface com.github.kevinvane.daytimeline.library.DayTimelineView$EditController { *; }

# ---- 定制上下文：业务方在 paint() 中读取字段，字段名被混淆会破坏其实现 ----
-keepclassmembers class com.github.kevinvane.daytimeline.library.api.GridContext { *; }
-keepclassmembers class com.github.kevinvane.daytimeline.library.api.BlockContext { *; }
-keepclassmembers class com.github.kevinvane.daytimeline.library.api.TimelineColors { *; }

# ---- 值类型：@JvmInline 展开为底层类型，保留名称便于日志与调试 ----
-keepnames class com.github.kevinvane.daytimeline.library.core.MinuteOfDay

# ---- 数据异常（DataIssue）以数据类形式回调给业务方做日志记录 ----
-keepclassmembers class com.github.kevinvane.daytimeline.library.core.DataIssue { *; }

# ---- 资源：自定义 View 引用了本库资源，混淆不应影响，但保留 attr 便于崩溃栈定位 ----
-keepclassmembers class com.github.kevinvane.daytimeline.library.R$* { *; }
