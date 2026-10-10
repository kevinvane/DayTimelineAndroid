package com.github.kevinvane.daytimeline.library

import android.os.Parcel
import android.os.Parcelable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.kevinvane.daytimeline.library.api.TimelineConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `DayTimelineSavedState` 的**真 Parcel 往返**测试（AD-11 / D12 / AD-28 拆分的回归）。
 *
 * ## 为什么必须是真 Parcel，不能走 `restoreHierarchyState`
 *
 * AD-28 把 `SavedState` 从 `DayTimelineView` 的嵌套类提为文件级私有类后，需要确认
 * 三个字段（`scrollOffset` / `viewDate` / `selectedId`）的读写没被手改坏。
 *
 * 第一版打算走 `saveHierarchyState` / `restoreHierarchyState`——**这条路是无效的**：
 * 同一进程内 Android 的 `View.onSaveInstanceState` 在 `SparseArray` 里直接传递
 * `SavedState` 对象引用，**不经过 Parcel 序列化**。实测证据：把
 * `writeToParcel` 的 `viewDate` 改成固定 `999L`、把 `Parcel` 构造里的
 * `readLong()` 改成 `= 0L`，两处同时破坏，`viewDateSurvivesRoundTrip` 仍然全绿。
 * 也就是说那时断言通过靠的是对象引用，`Parcelable` 的读写逻辑根本没被执行。
 *
 * 真 Parcel 才抓得到这类错误——而它恰好也是进程被系统杀掉后恢复时走的路径
 * （用户真正会遇到的状态丢失场景）。
 */
@RunWith(AndroidJUnit4::class)
class DayTimelineViewSavedStateParcelTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var view: DayTimelineView

    @Before
    fun setUp() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = DayTimelineView(context)
        }
        view.setConfig(
            TimelineConfig(
                hourHeight = 120,
                topPadding = 0,
                bottomPadding = 0,
                autoLocateOnFirstShow = false,
            ),
        )
    }

    /** 真 Parcel 往返：写 → marshal/unmarshal → createFromParcel。 */
    private fun roundTrip(state: DayTimelineSavedState): DayTimelineSavedState {
        val parcel = Parcel.obtain()
        return try {
            state.writeToParcel(parcel, 0)
            val bytes = parcel.marshall()
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            DayTimelineSavedState.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    /**
     * 构造载体。`superState` 传 `EMPTY_STATE`——`BaseSavedState` 的构造直接拒绝调用方
     * 传 `null`（`AbsSavedState` 会抛 IllegalArgumentException），这与组件无关，
     * 是 Android 框架自身的约束。
     */
    private fun newState(): DayTimelineSavedState =
        DayTimelineSavedState(android.view.AbsSavedState.EMPTY_STATE)

    @Test
    fun scrollOffsetSurvivesRealParcel() {
        val state = newState()
        state.scrollOffset = 1234

        val back = roundTrip(state)

        assertEquals("scrollOffset 必须原样往返", 1234, back.scrollOffset)
    }

    @Test
    fun viewDateSurvivesRealParcel() {
        val state = newState()
        state.viewDate = 1_724_500_000_000L

        val back = roundTrip(state)

        assertEquals("viewDate 必须原样往返", 1_724_500_000_000L, back.viewDate)
    }

    @Test
    fun selectedIdSurvivesRealParcel() {
        val state = newState()
        state.selectedId = "event-42"

        val back = roundTrip(state)

        assertEquals("selectedId 必须原样往返", "event-42", back.selectedId)
    }

    /** 三个字段一个都不能漏：写三个读三个，顺序错位会让后面两个值串味。 */
    @Test
    fun allThreeFieldsStayAlignedInParcel() {
        val state = newState()
        state.scrollOffset = 777
        state.viewDate = 1_724_500_000_000L
        state.selectedId = "align-check"

        val back = roundTrip(state)

        assertEquals(777, back.scrollOffset)
        assertEquals(1_724_500_000_000L, back.viewDate)
        assertEquals("align-check", back.selectedId)
    }

    /** 空值态也要是对的：未选中时 selectedId 往返后仍为 null，不能变成 "null" 字符串。 */
    @Test
    fun nullSelectedIdStaysNullAfterParcel() {
        val state = newState()
        state.scrollOffset = 0
        state.viewDate = 0L
        state.selectedId = null

        val back = roundTrip(state)

        assertNull("未选中时 selectedId 往返后必须是 null", back.selectedId)
    }
}
