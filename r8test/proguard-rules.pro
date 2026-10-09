# r8test 模块自身的混淆规则。
#
# 刻意**只**放测试基础设施需要的规则，绝不重复 library/consumer-rules.pro 的内容——
# 那些规则必须由 AAR 自动带进来，否则这个模块就验证不到真实场景了。
#
# 本模块是**验证夹具**，不是发布产物，体积无关紧要，所以对 androidx 与
# kotlin-stdlib 整体 keep。第一版只 keep 了 androidx.test.**，结果 R8 依次裁掉了
# androidx.tracing.Trace 与 kotlin.LazyKt，仪器测试进程启动即 NoClassDefFoundError。
# 这正是「在混淆变体上跑仪器测试」的固有成本：测试基建本身大量依赖反射与懒加载。

-keep class androidx.** { *; }
-keep interface androidx.** { *; }
-dontwarn androidx.**

-keep class kotlin.** { *; }
-keep interface kotlin.** { *; }
-dontwarn kotlin.**

# 仪器测试入口由 JUnit 反射创建。
-keep class com.github.kevinvane.daytimeline.r8test.** { *; }

# 组件的对外契约由 library 的 consumer-rules.pro 负责，这里**刻意不重复**。
# 若发现某个契约类在混淆后失联，正确做法是去修 consumer-rules.pro——
# 在这里加 keep 会掩盖真正的问题。