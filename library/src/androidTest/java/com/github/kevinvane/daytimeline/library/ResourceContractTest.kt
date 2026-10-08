package com.github.kevinvane.daytimeline.library

import android.content.Context
import android.util.TypedValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 资源契约测试（**必须在真机/模拟器上跑**）。
 *
 * ## 为什么必须有这个测试
 *
 * JVM 单元测试**加载不了 Android 资源**，所以「资源类型与读取方式不匹配」这类缺陷
 * 对单测完全隐形。真实事故：`day_timeline_drag_threshold_ratio` 曾声明为
 * `<item format="float" type="dimen">`，AAPT2 按 `format` 编译成 `TYPE_FLOAT(0x4)`，
 * 而 `Resources.getDimension()` 只接受 `TYPE_DIMENSION(0x5)`，
 * 真机启动即抛 `Resources$NotFoundException: type #0x4 is not valid`。
 * 当时 lint 通过、107 个单测全绿、覆盖率门禁达标——**唯独没在真机上跑过**。
 *
 * 本测试遍历全部资源、用生产代码同样的方式读取，从结构上杜绝该类缺陷复发。
 */
@RunWith(AndroidJUnit4::class)
class ResourceContractTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * 所有 dimen 都必须能被 `getDimension()` 读取。
     *
     * 任何 `<item format="float"/>` 或 `format="fraction"` 混进 dimen 都会在此暴露。
     */
    @Test
    fun allDimensAreReadableAsDimension() {
        val res = context.resources
        val ids = R.dimen::class.java.fields.map { it.getInt(null) }
        assertTrue("R.dimen 不应为空", ids.isNotEmpty())

        ids.forEach { id ->
            val name = res.getResourceName(id)
            try {
                val px = res.getDimension(id)
                assertTrue("$name 读出的 px 应为有限正数，实际 $px", px.isFinite())
            } catch (e: android.content.res.Resources.NotFoundException) {
                fail(
                    "资源 $name 无法用 getDimension 读取：${e.message}。" +
                        "若它是用 <item format=\"float\"> 声明的，请改为代码常量，" +
                        "或改为 <attr format=\"float\"> 用 TypedArray.getFloat 读取。",
                )
            }
        }
        assertTrue("枚举到的 dimen 数量异常：${ids.size}", ids.size >= 25)
    }

    /**
     * 字号资源必须是 **sp** 语义，且只换算一次。
     *
     * 曾因在 `getDimension()` 之外又套了一层 `applyDimension(COMPLEX_UNIT_DIP, …)`
     * 导致 density 被乘两次，字号在 density=3 的设备上放大三倍。
     */
    @Test
    fun fontSizesUseSpAndAreNotDoubleScaled() {
        val metrics = context.resources.displayMetrics
        val expected = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, metrics)

        assertEquals(
            "day_timeline_axis_label_size 应等于 12sp 换算后的 px",
            expected,
            context.resources.getDimension(R.dimen.day_timeline_axis_label_size),
            0.5f,
        )
        assertEquals(
            "day_timeline_now_label_size 应等于 12sp 换算后的 px",
            expected,
            context.resources.getDimension(R.dimen.day_timeline_now_label_size),
            0.5f,
        )
    }

    /** 所有 integer 资源都应能被 getInteger 读取。 */
    @Test
    fun allIntegersAreReadable() {
        val res = context.resources
        val ids = R.integer::class.java.fields.map { it.getInt(null) }
        assertTrue("R.integer 不应为空", ids.isNotEmpty())
        ids.forEach { res.getInteger(it) }
    }

    /**
     * 17 项语义色项必须齐全（PRD §7.3），且都能解析出颜色。
     *
     * 若 `values-night/colors.xml` 少声明某一项，真机上深色模式会崩——
     * 那属于另一个用例的职责，这里只守住「数量与可解析性」。
     */
    @Test
    fun allSeventeenSemanticColorsResolve() {
        val semantic = R.color::class.java.fields.map { it.name }
            .filter { it.startsWith("day_timeline_") }
        assertEquals(
            "语义色项应为 17 项（PRD §7.3），实际 ${semantic.size}",
            17, semantic.size,
        )
        semantic.forEach { name ->
            val id = context.resources.getIdentifier(name, "color", context.packageName)
            assertTrue("$name 未在浅色资源中定义", id != 0)
            androidx.core.content.ContextCompat.getColor(context, id)
        }
    }
}
