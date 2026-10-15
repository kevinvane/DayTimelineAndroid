package com.github.kevinvane.daytimeline.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 文档与源码的一致性守卫。
 *
 * ## 为什么需要这条测试
 *
 * 完整能力文档（`docs/DayTimeline-完整能力文档.md`）要列全 55 项配置，
 * 而 `attrs.xml` 与 `TimelineConfig` 也各有一份。**三份副本天然会漂移**——
 * 本仓库反复出现的故障模式是「文档/清单声称覆盖了，实际没有」：
 *
 * - `:app:detekt` 注释写「覆盖整个仓库」，实际漏掉整个 `src/androidTest`
 * - `verifyNoDeadCode` 守着 detekt 里根本不存在的 `UnusedReturnValue`
 * - §16.2 的「17 项色值须为设计定稿」长期只存在于文字里，无人可判定
 *
 * 「新增一个配置项却忘了写文档」正是同一类问题，且**没有任何门禁能发现**：
 * 编译通过、单测全绿、覆盖率达标，而文档已经错了。
 *
 * 所以这里用**结构性断言**而不是人工核对：集合相等就相等，
 * 少一项或多一项立刻失败，且失败信息直接指出缺哪个。
 *
 * ## 断言哪三份
 *
 * 1. `attrs.xml` 声明的 XML 属性
 * 2. `TimelineConfig` 的字段（按 `dt` + 首字母大写的既定约定换算成属性名）
 * 3. 完整能力文档里列出的属性
 *
 * 三者必须完全相等。约定本身也被断言：若某天改了对��规则，这里会先失败，
 * 而不是悄悄放过一批对不上的项。
 *
 * ## 顺带守住「README 不再复制全表」
 *
 * README 与完整能力文档分界是刻意划的：README 只讲怎么接起来，
 * 全表只在完整能力文档里维护。若哪天有人把全表搬回 README，
 * 两份副本立刻开始漂移——那条断言会在**第二次**改动时才暴露问题，
 * 不如现在直接拦住。
 */
/**
 * 文档里出现的 `dtXxx` 名称，**必须全部**是 `attrs.xml` 里真实声明的属性。
 *
 * 这里刻意没有任何例外名单。
 *
* 一开始是有的：文档曾写出 `dtShowEditActions` / `dtShowDeleteAction`
 * 两个**故意不声明**的属性名（组件不内置编辑按钮），为了让读者知道
 * PRD §7.7 要求的「可配置是否内置」尚未实现。已按「删除而不是豁免」处理——
 * 那两个名字写在文档里，读者会以为配了却没生效，
 * 而这正是本仓库记过两次的「声明了却没有任何代码」反模式（AD-24）。
 * 能力缺口应当以**能力**陈述，而不是以一个假属性名陈述。
 *
 * **2026-10-13 更新**：PRD v1.22 已取消「可配置是否内置」这项需求（组件恒不内置），
 * 因此这里不再是「能力缺口」而是「确定的行为」——理由变了，结论不变：
 * 两个属性名仍然不得出现在文档里。若哪天看到有人重新引入它们，
 * 先查 `attrs.xml` 里的那条警示注释：**那个需求已经被删，不是待补。**
 *
 * 因此：将来若有人想在文档里提一个尚未实现的属性名，
 * 请改成「组件不提供 X，请用 Y 接管」的说法，不要写属性名。
 */
class DocsConfigCoverageTest {

    /** 定位仓库根：自当前工作目录向上找 `settings.gradle.kts`。 */
    private fun repoRoot(): File {
        var dir = File("").absoluteFile
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: error("未找到仓库根（向上没有 settings.gradle.kts）")
        }
    }

    private fun read(relativePath: String): String {
        val file = File(repoRoot(), relativePath)
        assertTrue("文件不存在：${file.absolutePath}", file.isFile)
        return file.readText()
    }

    /** `attrs.xml` 里声明的全部 `dtXxx`。 */
    private fun attrsXmlNames(): Set<String> =
        Regex("""<attr name="(dt\w+)"""")
            .findAll(read("library/src/main/res/values/attrs.xml"))
            .map { it.groupValues[1] }
            .toSet()

    /** `TimelineConfig` 声明的全部 `val` 字段。 */
    private fun configFieldNames(): Set<String> =
        Regex("""(?m)^\s*val\s+(\w+)\s*:""")
            .findAll(
                read("library/src/main/java/com/github/kevinvane/daytimeline/library/api/TimelineConfig.kt"),
            )
            .map { it.groupValues[1] }
            .toSet()

    /** 既定换算：字段名 → XML 属性名（`hourHeight` → `dtHourHeight`）。 */
    private fun attrNameOf(field: String) = "dt" + field.replaceFirstChar { it.uppercase() }

    /**
     * 抽出一篇文档里出现的全部 `dt` 属性名。
     *
     * ## 为什么必须同时匹配两种写法
     *
     * 第一版只认反引号包裹的 `` `dtXxx` ``，于是**代码块里的属性名全部漏网**——
     * 而代码块恰恰是接入方唯一会**复制粘贴**的地方：
     * `app:dtScrollModeTypo="external"` 写在 XML 示例里，照抄进项目就编译不过。
     * 补上 `app:dtXxx` 这一路之后，当场就把快速上手文档里一处真实笔误抓了出来。
     *
     * 两路都要，是因为反引号那路覆盖正文里对属性的讨论，
     * `app:` 那路覆盖会被复制的代码片段。
     */
    private fun attrNamesIn(relativePath: String): Set<String> {
        val text = read(relativePath)
        val backticked = Regex("""`((?:dt)\w+)`""").findAll(text).map { it.groupValues[1] }
        val inCode = Regex("""\bapp:(dt\w+)\s*=""").findAll(text).map { it.groupValues[1] }
        return (backticked + inCode).toSet()
    }

    /** 完整能力文档里作为表格首列出现的属性名。 */
    private fun documentedAttrNames(): Set<String> = attrNamesIn("docs/DayTimeline-完整能力文档.md")

    private fun readmeAttrNames(): Set<String> = attrNamesIn("README.md")

    /**
     * 快速上手文档（DL-03）里出现的 `dt` 属性名。
     *
     * ## 为什么把它也纳入扫描
     *
     * DL-03 交付后就有**第三份**会写属性名的文档，而它是最容易被复制粘贴的一份。
     *
     * 只做**反向**校验（不许写不存在的），不要求它覆盖全部 55 项：
     * 快速上手的价值就在于短，覆盖全表反而违背它自己的定位。
     */
    private fun quickStartAttrNames(): Set<String> = attrNamesIn("docs/DayTimeline-快速上手.md")

    @Test
    fun timelineConfigFieldsMapOneToOneOntoAttrs() {
        val fields = configFieldNames()
        val attrs = attrsXmlNames()
        val expected = fields.map(::attrNameOf).toSet()

        assertEquals(
            "TimelineConfig 字段与 attrs.xml 的属性不是一一对应。" +
                "\n  约定：属性名 = dt + 首字母大写的字段名。" +
                "\n  只有字段没有属性：${expected - attrs}" +
                "\n  只有属性没有字段：${attrs - expected}",
            expected,
            attrs,
        )
    }

    @Test
    fun capabilityDocumentCoversEveryAttr() {
        val attrs = attrsXmlNames()
        val documented = documentedAttrNames()

        // ① 覆盖：attrs.xml 里有的，文档必须全都写到
        assertEquals(
            "完整能力文档漏写了配置项——新增属性后忘了更新文档。" +
                "\n  文档未覆盖：${attrs - documented}",
            emptySet<String>(),
            attrs - documented,
        )
        // ② 反向：文档里不允许出现 attrs.xml 中不存在的属性名
        assertEquals(
            "完整能力文档写了 attrs.xml 里不存在的属性。" +
                "\n  多写：${documented - attrs}" +
                "\n  能力缺口请以「组件不提供 X，请用 Y 接管」陈述，不要写属性名。",
            emptySet<String>(),
            documented - attrs,
        )
    }

    @Test
    fun quickStartOnlyMentionsRealAttributes() {
        val all = attrsXmlNames()
        val inQuickStart = quickStartAttrNames()

        assertEquals(
            "快速上手文档写了 attrs.xml 里不存在的属性：${inQuickStart - all}" +
                "\n  这份文档会被直接复制粘贴，错误会扩散到接入方项目里。" +
                "\n  能力缺口请以「组件不提供 X，请用 Y 接管」陈述，不要写属性名。",
            emptySet<String>(),
            inQuickStart - all,
        )
        assertTrue(
            "快速上手文档又抄了一遍配置全表（覆盖 ${inQuickStart.size}/${all.size} 项）。" +
                "\n全表只在 docs/DayTimeline-完整能力文档.md 维护；" +
                "\nDL-03 的定位是「5 分钟跑通第一条链路」，长即失效。",
            inQuickStart.size < all.size,
        )
    }

    @Test
    fun readmeDoesNotDuplicateTheFullConfigTable() {
        val all = attrsXmlNames()
        val inReadme = readmeAttrNames()

        assertTrue(
            "README 又把 55 项配置全表抄了一遍（覆盖 ${inReadme.size}/${all.size} 项）。" +
                "\n全表只在 docs/DayTimeline-完整能力文档.md 维护，README 只列最常改的几项并链接过去。",
            inReadme.size < all.size,
        )
        assertEquals(
            "README 里的 dtXxx 名称有源码中不存在的项：${inReadme - all}",
            emptySet<String>(),
            inReadme - all,
        )
    }
}
