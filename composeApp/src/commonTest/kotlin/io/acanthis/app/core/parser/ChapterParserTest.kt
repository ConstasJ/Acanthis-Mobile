package io.acanthis.app.core.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ChapterParserTest {

    @Test
    fun returnsEmptyListForBlankHtml() {
        assertEquals(emptyList(), parseChapterBlocks("   \n\t ", "http://10.0.2.2:5301"))
    }

    @Test
    fun parsesHeadingsParagraphsSingleImageParagraphsAndRubyText() {
        val html = """
            <h2>第一章 序幕</h2>
            <p>   </p>
            <p>第一段正文包含<ruby>漢字<rp>(</rp><rt>かんじ</rt><rp>)</rp></ruby>注音。</p>
            <p>
                <!-- comment -->
                <img src="/v1/linovelib/illustration/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" alt="卷首插画" />
            </p>
            <img src="http://localhost:5301/v1/linovelib/illustration/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" alt="第二张" />
        """.trimIndent()

        val blocks = parseChapterBlocks(html, "https://novel.example.com/v1")
        assertEquals(4, blocks.size)

        val b0 = assertIs<ReaderBlock.Heading>(blocks[0])
        assertEquals(0, b0.blockIndex)
        assertEquals("h2", b0.tag)
        assertEquals("第一章 序幕", b0.text)

        val b1 = assertIs<ReaderBlock.Paragraph>(blocks[1])
        assertEquals(1, b1.blockIndex)
        assertEquals("第一段正文包含漢字(かんじ)注音。", b1.text)

        val b2 = assertIs<ReaderBlock.Image>(blocks[2])
        assertEquals(2, b2.blockIndex)
        assertEquals(
            "https://novel.example.com/v1/linovelib/illustration/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            b2.src,
        )
        assertEquals("卷首插画", b2.alt)

        val b3 = assertIs<ReaderBlock.Image>(blocks[3])
        assertEquals(3, b3.blockIndex)
        assertEquals(
            "https://novel.example.com/v1/linovelib/illustration/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            b3.src,
        )
        assertEquals("第二张", b3.alt)
    }

    @Test
    fun handlesMixedTextAndImageInsideSingleParagraphAsParagraph() {
        val html = """<p>文字说明<img src="/v1/linovelib/illustration/cccccccccccccccccccccccccccccccc" alt="图" /></p>"""
        val blocks = parseChapterBlocks(html, "http://10.0.2.2:5301")

        assertEquals(1, blocks.size)
        val p = assertIs<ReaderBlock.Paragraph>(blocks[0])
        assertEquals("文字说明", p.text)
    }
}
