package com.github.kevinvane.daytimeline.r8test;

import com.github.kevinvane.daytimeline.library.core.MinuteOfDay;
import com.github.kevinvane.daytimeline.library.core.TimelineEvent;

/**
 * **Java 业务方**实现组件数据契约的守护用例（OQ-7 / 风险 R12）。
 *
 * 存在意义有两层：
 *
 * 1. **编译期**：`MinuteOfDay` 曾是 `@JvmInline value class`，Kotlin 会对返回值类型为
 *    值类型的接口方法名做编译期混淆（`getStart-ZruiD9E()`），Java 根本无法实现该接口
 *    （`javac` 报「不是抽象类或接口中方法的覆盖」）。本文件一旦编译不过，就说明有人
 *    把类型改回了 value class——它比任何注释都更早发现这类回退。
 * 2. **混淆后**：本类被 `ConsumerApiSmoke` 引用，并以 release 变体（`isMinifyEnabled = true`）
 *    跑 `:r8test:connectedReleaseAndroidTest`——R8 若裁掉/改错 Java 侧要用的成员，测试立刻失败。
 *
 * 注意：Kotlin 接口的默认实现（`expiredOverride` / `content` / `color`）在字节码层仍是
 * 抽象方法 + `DefaultImpls` 静态类（未开 `-Xjvm-default=all`），所以 Java 实现方需要
 * 自己给出与 Kotlin 侧一致的缺省值；这是 Java 接入的一点摩擦，记录在此以免后人困惑。
 */
public final class JavaBusinessEvent implements TimelineEvent {

    private final String id;
    private final CharSequence content;
    private final int startMinute;
    private final int endMinute;

    public JavaBusinessEvent(String id, CharSequence content, int startMinute, int endMinute) {
        this.id = id;
        this.content = content;
        this.startMinute = startMinute;
        this.endMinute = endMinute;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public MinuteOfDay getStart() {
        return MinuteOfDay.ofMinute(startMinute);
    }

    @Override
    public MinuteOfDay getEnd() {
        return MinuteOfDay.ofMinute(endMinute);
    }

    /** Kotlin 侧缺省为 null，保持一致。 */
    @Override
    public Boolean getExpiredOverride() {
        return null;
    }

    @Override
    public CharSequence getContent() {
        return content;
    }

    /** Kotlin 侧缺省为 null，表示使用组件默认块配色。 */
    @Override
    public Integer getColor() {
        return null;
    }
}
