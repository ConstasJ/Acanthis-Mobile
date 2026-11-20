package io.acanthis.app.core.parser

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Comment
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.TextNode
import io.acanthis.app.core.network.rebaseMediaUrlWithBase

sealed interface ReaderBlock {
    val blockIndex: Int

    data class Heading(
        override val blockIndex: Int,
        val tag: String,
        val text: String,
    ) : ReaderBlock

    data class Paragraph(
        override val blockIndex: Int,
        val text: String,
    ) : ReaderBlock

    data class Image(
        override val blockIndex: Int,
        val src: String,
        val alt: String,
    ) : ReaderBlock
}

private val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
private val WHITESPACE_REGEX = Regex("\\s+")

fun parseChapterBlocks(
    rawHtml: String,
    apiBaseUrl: String,
): List<ReaderBlock> {
    if (rawHtml.isBlank()) {
        return emptyList()
    }

    val document = Ksoup.parseBodyFragment(rawHtml)
    val blocks = mutableListOf<ReaderBlock>()

    for (node in document.body().childNodes()) {
        when (node) {
            is TextNode -> {
                val trimmed = normalizeInlineWhitespace(node.getWholeText())
                if (trimmed.isNotEmpty()) {
                    blocks.add(
                        ReaderBlock.Paragraph(
                            blockIndex = blocks.size,
                            text = trimmed,
                        ),
                    )
                }
            }

            is Element -> {
                val tag = node.tagName().lowercase()
                when {
                    tag == "img" -> {
                        val src = rebaseMediaUrlWithBase(node.attr("src"), apiBaseUrl)
                        if (src.isNotEmpty()) {
                            blocks.add(
                                ReaderBlock.Image(
                                    blockIndex = blocks.size,
                                    src = src,
                                    alt = node.attr("alt"),
                                ),
                            )
                        }
                    }

                    tag == "p" -> {
                        val singleImg = extractSingleImageFromElement(node)
                        if (singleImg != null) {
                            val src = rebaseMediaUrlWithBase(singleImg.attr("src"), apiBaseUrl)
                            if (src.isNotEmpty()) {
                                blocks.add(
                                    ReaderBlock.Image(
                                        blockIndex = blocks.size,
                                        src = src,
                                        alt = singleImg.attr("alt"),
                                    ),
                                )
                            }
                            continue
                        }

                        val text = extractReadableText(node)
                        if (text.isNotEmpty()) {
                            blocks.add(
                                ReaderBlock.Paragraph(
                                    blockIndex = blocks.size,
                                    text = text,
                                ),
                            )
                        }
                    }

                    tag in HEADING_TAGS -> {
                        val text = extractReadableText(node)
                        if (text.isNotEmpty()) {
                            blocks.add(
                                ReaderBlock.Heading(
                                    blockIndex = blocks.size,
                                    tag = tag,
                                    text = text,
                                ),
                            )
                        }
                    }

                    else -> {
                        val embeddedImgs = node.select("img")
                        val text = extractReadableText(node)
                        if (text.isNotEmpty()) {
                            blocks.add(
                                ReaderBlock.Paragraph(
                                    blockIndex = blocks.size,
                                    text = text,
                                ),
                            )
                        } else if (embeddedImgs.size == 1) {
                            val img = embeddedImgs.first()
                            val src = rebaseMediaUrlWithBase(img?.attr("src"), apiBaseUrl)
                            if (src.isNotEmpty()) {
                                blocks.add(
                                    ReaderBlock.Image(
                                        blockIndex = blocks.size,
                                        src = src,
                                        alt = img?.attr("alt").orEmpty(),
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            else -> Unit
        }
    }

    return blocks
}

private fun extractSingleImageFromElement(element: Element): Element? {
    var foundImg: Element? = null
    for (child in element.childNodes()) {
        when (child) {
            is TextNode -> {
                if (!child.isBlank()) {
                    return null
                }
            }

            is Element -> {
                if (child.tagName().equals("img", ignoreCase = true) && foundImg == null) {
                    foundImg = child
                } else {
                    return null
                }
            }

            is Comment -> Unit
            else -> return null
        }
    }
    return foundImg
}

private fun extractReadableText(element: Element): String {
    val builder = StringBuilder()
    appendReadableNodes(element, builder)
    return builder
        .toString()
        .lines()
        .joinToString("\n") { normalizeInlineWhitespace(it) }
        .trim()
}

private fun appendReadableNodes(element: Element, builder: StringBuilder) {
    for (child in element.childNodes()) {
        when (child) {
            is TextNode -> builder.append(child.getWholeText())
            is Element -> {
                when (child.tagName().lowercase()) {
                    "br" -> builder.append('\n')
                    "rp", "script", "style" -> Unit
                    "rt" -> {
                        val rtText = normalizeInlineWhitespace(child.text())
                        if (rtText.isNotEmpty()) {
                            builder.append('(').append(rtText).append(')')
                        }
                    }
                    else -> appendReadableNodes(child, builder)
                }
            }
            else -> Unit
        }
    }
}

private fun normalizeInlineWhitespace(text: String): String {
    return text.replace(WHITESPACE_REGEX, " ").trim()
}
