package com.github.kevinvane.daytimeline.app

import android.graphics.Rect
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.DisplayCutoutCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 窗口避让的回归测试（PRD §12.2「屏幕缺口与手势条：正确避让，不被遮挡」）。
 *
 * ## 为什么需要它——竖屏测试会给出假信号
 *
 * 2026-10-16 在 Pixel Fold AVD（`cutout.emulation.tall`，缺口 110px）上实测查出：
 * 原实现只取 `WindowInsetsCompat.Type.systemBars()`，**未并入 `displayCutout()`**。
 * 按 Android insets 契约 `systemBars()` 不含缺口，于是：
 *
 * - **竖屏**：缺口在顶部，状态栏 inset 恰好盖住它 → 看着正常，**测不出来**；
 * - **横屏**：缺口移到侧边（`insets=Rect(110, 0 - 0, 0)`），状态栏盖不住 → 轴标签
 *   `05:00`–`10:00` 整列落在缺口区内，物理摄像机会挡住。
 *
 * 只跑竖屏会漏掉它，只跑真机横屏则无法稳定复现。本测试**直接合成 insets 喂给根视图**，
 * 不依赖方向、也不依赖设备是否真有缺口，因此谁把 `displayCutout()` 删掉就会转红。
 *
 * ## 断言对象是「最终 padding」而不是「调了哪个方法」
 *
 * 比对 `R.id.main` 的 padding——那是避让是否生效的**最终结果**。
 * 中间过程（用没用 `maxOf`、并了几次）换了写法也不该让测试转红。
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class MainActivityWindowInsetsTest {

    private lateinit var scenario: ActivityScenario<MainActivity>
    private var activityRef: MainActivity? = null

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activityRef = it }
    }

    @After
    fun tearDown() {
        activityRef = null
        scenario.close()
    }

    @Test
    fun sideCutoutIsAvoidedEvenWhenSystemBarsAreEmpty() {
        // 横屏侧边缺口，状态栏 inset 全部为 0——这是原缺陷的精确复现条件。
        val root = dispatch(insetsOf(cutout = Rect(110, 0, 0, 0)))

        assertEquals(
            "侧边缺口 110px 未被避让：只看 systemBars() 就会漏掉 displayCutout()，" +
                "横屏下轴标签会整列画进缺口区",
            110,
            root.paddingLeft,
        )
    }

    @Test
    fun cutoutWinsOverSystemBarWhenCutoutIsLarger() {
        val root = dispatch(
            insetsOf(
                systemBars = Insets.of(20, 40, 20, 40),
                cutout = Rect(110, 200, 0, 0),
            ),
        )

        assertEquals("左边缺口 110 > 系统栏 20，应取 110", 110, root.paddingLeft)
        assertEquals("上边缺口 200 > 系统栏 40，应取 200", 200, root.paddingTop)
        assertEquals("右边无缺口，应回落到系统栏 20", 20, root.paddingRight)
        assertEquals("下边无缺口，应回落到系统栏 40", 40, root.paddingBottom)
    }

    @Test
    fun systemBarWinsWhenItIsLargerThanCutout() {
        val root = dispatch(
            insetsOf(
                systemBars = Insets.of(300, 300, 300, 300),
                cutout = Rect(0, 110, 0, 0),
            ),
        )

        assertTrue(
            "系统栏比缺口大时不应被缺口拉小：padding=$root",
            root.paddingLeft >= 300 && root.paddingTop >= 300,
        )
    }

    @Test
    fun fourEdgesAreAvoidedNotJustTheLeft() {
        val root = dispatch(insetsOf(cutout = Rect(10, 20, 30, 40)))

        assertEquals("左边缺口未避让", 10, root.paddingLeft)
        assertEquals("上边缺口未避让", 20, root.paddingTop)
        assertEquals("右边缺口未避让", 30, root.paddingRight)
        assertEquals("下边缺口未避让", 40, root.paddingBottom)
    }

    // ================= 工具 =================

    private val activity: MainActivity
        get() = requireNotNull(activityRef) { "前置失败：Activity 未启动（onActivity 没回调过）" }

    /**
     * 在主线程上构造 insets 并派发给根视图，返回**同一**根视图。
     *
     * `WindowInsetsCompat.toWindowInsets()` 需要 API 30+，故类上标了 `@SdkSuppress`。
     */
    private fun dispatch(insets: WindowInsetsCompat): View {
        lateinit var root: View
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            root = activity.findViewById(R.id.main)
            root.dispatchApplyWindowInsets(insets.toWindowInsets())
        }
        return root
    }

    private fun insetsOf(
        systemBars: Insets = Insets.NONE,
        cutout: Rect? = null,
    ): WindowInsetsCompat = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.systemBars(), systemBars)
        .apply {
            if (cutout != null) {
                // 2 参构造：第一个 Rect 就是「安全 inset」，即 getSafeInsetLeft/Top/Right/Bottom
                // 的来源——而这正是 getInsets(Type.displayCutout()) 实际读的值。
                // （另一个 6 参重载收的是 Insets，不要混用。）
                // 注意：android.graphics.Rect 没有 componentN()，不能用解构。
                setDisplayCutout(
                    DisplayCutoutCompat(
                        Rect(cutout.left, cutout.top, cutout.right, cutout.bottom),
                        emptyList(),
                    ),
                )
            }
        }
        .build()
}
