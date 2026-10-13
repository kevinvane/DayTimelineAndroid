package com.github.kevinvane.daytimeline.library

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout

/**
 * 仪器测试专用宿主窗口（只在 androidTest 源集里，不进发布物）。
 *
 * 存在的唯一理由：组件必须**真正挂进窗口**，才能观测到「有没有发生重绘」。
 * 既有仪器测试都是「裸 Context + `measure`/`layout`」——那种 View 压根不在窗口里，
 * `invalidate()` 不会触发任何绘制，`ViewTreeObserver.OnDrawListener` 一次都不会响。
 * 在那种前提下，「刷新是否收敛」（AD-06 / D17 / PRD §12.1 耗电项）无法被任何自动化观测到，
 * 于是它只能写成注释与 KDoc——本仓库反复出现的正是这种「文档里承诺、代码里无人守」的状态。
 *
 * 刻意用最朴素的平台 [Activity] + [FrameLayout]：宿主越薄，测试越接近业务方的真实接入方式。
 */
class TimelineTestHostActivity : Activity() {

    /** 测试往这里挂组件。 */
    lateinit var container: FrameLayout
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = FrameLayout(this)
        setContentView(container)
    }
}
