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

    /** 完整能力文档里作为表格首列出现的属性名。 */
    private fun documentedAttrNames(): Set<String> =
        Regex("""`((?:dt)\w+)`""")
            .findAll(read("docs/DayTimeline-完整能力文档.md"))
            .map { it.groupValues[1] }
            .toSet()

    private fun readmeAttrNames(): Set<String> =
        Regex("""`((?:dt)\w+)`""")
            .findAll(read("README.md"))
            .map { it.groupValues[1] }
            .toSet()

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
