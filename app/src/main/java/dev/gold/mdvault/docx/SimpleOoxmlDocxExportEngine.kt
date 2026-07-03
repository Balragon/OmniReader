package dev.gold.mdvault.docx

import com.vladsch.flexmark.ast.AutoLink
import com.vladsch.flexmark.ast.BlockQuote
import com.vladsch.flexmark.ast.BulletList
import com.vladsch.flexmark.ast.Code
import com.vladsch.flexmark.ast.Emphasis
import com.vladsch.flexmark.ast.FencedCodeBlock
import com.vladsch.flexmark.ast.HardLineBreak
import com.vladsch.flexmark.ast.Heading
import com.vladsch.flexmark.ast.HtmlBlock
import com.vladsch.flexmark.ast.HtmlInline
import com.vladsch.flexmark.ast.Image
import com.vladsch.flexmark.ast.IndentedCodeBlock
import com.vladsch.flexmark.ast.Link
import com.vladsch.flexmark.ast.ListItem
import com.vladsch.flexmark.ast.OrderedList
import com.vladsch.flexmark.ast.Paragraph
import com.vladsch.flexmark.ast.SoftLineBreak
import com.vladsch.flexmark.ast.StrongEmphasis
import com.vladsch.flexmark.ast.Text
import com.vladsch.flexmark.ast.TextBase
import com.vladsch.flexmark.ast.ThematicBreak
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListItem
import com.vladsch.flexmark.ext.tables.TableBlock
import com.vladsch.flexmark.ext.tables.TableBody
import com.vladsch.flexmark.ext.tables.TableCell
import com.vladsch.flexmark.ext.tables.TableHead
import com.vladsch.flexmark.ext.tables.TableRow
import com.vladsch.flexmark.ext.tables.TablesExtension
import com.vladsch.flexmark.ext.yaml.front.matter.YamlFrontMatterBlock
import com.vladsch.flexmark.ext.yaml.front.matter.YamlFrontMatterExtension
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.ast.Node
import com.vladsch.flexmark.util.data.MutableDataSet
import dev.gold.mdvault.document.ConversionWarning
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 외부 의존성 0개(flexmark AST + java.util.zip)의 제한된 DOCX writer.
 * 지원 범위와 강등 규칙은 DocxExportEngine 참조.
 */
class SimpleOoxmlDocxExportEngine : DocxExportEngine {

    private val parser = Parser.builder(
        MutableDataSet().set(
            Parser.EXTENSIONS,
            listOf(
                TablesExtension.create(),
                TaskListExtension.create(),
                YamlFrontMatterExtension.create(),
            ),
        ),
    ).build()

    override fun export(
        markdown: String,
        title: String,
        assets: AssetResolver,
        output: OutputStream,
    ): List<ConversionWarning> {
        val document = parser.parse(markdown)
        val builder = BodyBuilder(assets)
        val bodyXml = builder.build(document)

        val zip = ZipOutputStream(output)
        zip.putTextEntry("[Content_Types].xml", contentTypesXml(builder.media))
        zip.putTextEntry("_rels/.rels", PACKAGE_RELS_XML)
        zip.putTextEntry("docProps/core.xml", coreXml(title))
        zip.putTextEntry("word/document.xml", documentXml(bodyXml))
        zip.putTextEntry("word/styles.xml", STYLES_XML)
        zip.putTextEntry("word/numbering.xml", numberingXml(builder.orderedListNums))
        zip.putTextEntry("word/_rels/document.xml.rels", documentRelsXml(builder))
        for (asset in builder.media) {
            zip.putNextEntry(ZipEntry("word/media/${asset.fileName}"))
            zip.write(asset.bytes)
            zip.closeEntry()
        }
        zip.finish()
        return builder.warnings.distinct()
    }

    private fun ZipOutputStream.putTextEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun contentTypesXml(media: List<MediaAsset>): String = buildString {
        append(XML_DECLARATION)
        append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        for (extension in media.map { it.fileName.substringAfterLast('.') }.distinct()) {
            val contentType = if (extension == "png") "image/png" else "image/jpeg"
            append("<Default Extension=\"$extension\" ContentType=\"$contentType\"/>")
        }
        append("<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>")
        append("<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>")
        append("<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>")
        append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>")
        append("</Types>")
    }

    private fun coreXml(title: String): String = buildString {
        append(XML_DECLARATION)
        append("<cp:coreProperties")
        append(" xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\"")
        append(" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">")
        val writer = OoxmlWriter(this)
        writer.element("dc:title") { text(title) }
        writer.element("dc:creator") { text("mdvault") }
        append("</cp:coreProperties>")
    }

    private fun documentXml(body: String): String = buildString {
        append(XML_DECLARATION)
        append("<w:document")
        append(" xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"")
        append(" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"")
        append(" xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\"")
        append(" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"")
        append(" xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">")
        append("<w:body>")
        append(body)
        append("<w:sectPr>")
        append("<w:pgSz w:w=\"$PAGE_WIDTH_TWIPS\" w:h=\"$PAGE_HEIGHT_TWIPS\"/>")
        append("<w:pgMar w:top=\"$MARGIN_TWIPS\" w:right=\"$MARGIN_TWIPS\" w:bottom=\"$MARGIN_TWIPS\" w:left=\"$MARGIN_TWIPS\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/>")
        append("</w:sectPr>")
        append("</w:body></w:document>")
    }

    private fun numberingXml(orderedNums: List<OrderedNum>): String = buildString {
        append(XML_DECLARATION)
        append("<w:numbering xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">")
        // abstractNum 0: bullet 2단
        append("<w:abstractNum w:abstractNumId=\"0\">")
        append("<w:multiLevelType w:val=\"hybridMultilevel\"/>")
        append(bulletLevel(0, "•", 720))
        append(bulletLevel(1, "◦", 1440))
        append("</w:abstractNum>")
        // abstractNum 1: decimal 2단
        append("<w:abstractNum w:abstractNumId=\"1\">")
        append("<w:multiLevelType w:val=\"hybridMultilevel\"/>")
        append(decimalLevel(0, "%1.", 720))
        append(decimalLevel(1, "%2.", 1440))
        append("</w:abstractNum>")
        append("<w:num w:numId=\"$BULLET_NUM_ID\"><w:abstractNumId w:val=\"0\"/></w:num>")
        for (num in orderedNums) {
            append("<w:num w:numId=\"${num.numId}\">")
            append("<w:abstractNumId w:val=\"1\"/>")
            append("<w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"${num.start}\"/></w:lvlOverride>")
            append("</w:num>")
        }
        append("</w:numbering>")
    }

    private fun bulletLevel(level: Int, marker: String, indent: Int): String =
        "<w:lvl w:ilvl=\"$level\"><w:start w:val=\"1\"/><w:numFmt w:val=\"bullet\"/>" +
            "<w:lvlText w:val=\"$marker\"/><w:lvlJc w:val=\"left\"/>" +
            "<w:pPr><w:ind w:left=\"$indent\" w:hanging=\"360\"/></w:pPr></w:lvl>"

    private fun decimalLevel(level: Int, format: String, indent: Int): String =
        "<w:lvl w:ilvl=\"$level\"><w:start w:val=\"1\"/><w:numFmt w:val=\"decimal\"/>" +
            "<w:lvlText w:val=\"$format\"/><w:lvlJc w:val=\"left\"/>" +
            "<w:pPr><w:ind w:left=\"$indent\" w:hanging=\"360\"/></w:pPr></w:lvl>"

    private fun documentRelsXml(builder: BodyBuilder): String = buildString {
        append(XML_DECLARATION)
        append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        append("<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
        append("<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering\" Target=\"numbering.xml\"/>")
        val writer = OoxmlWriter(this)
        for (asset in builder.media) {
            writer.element(
                "Relationship",
                "Id" to asset.relId,
                "Type" to "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image",
                "Target" to "media/${asset.fileName}",
            )
        }
        for (link in builder.hyperlinks) {
            writer.element(
                "Relationship",
                "Id" to link.relId,
                "Type" to "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink",
                "Target" to link.url,
                "TargetMode" to "External",
            )
        }
        append("</Relationships>")
    }

    companion object {
        private const val XML_DECLARATION =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\r\n"

        // A4 + 1인치 여백
        internal const val PAGE_WIDTH_TWIPS = 11906
        internal const val PAGE_HEIGHT_TWIPS = 16838
        internal const val MARGIN_TWIPS = 1440
        internal const val BODY_WIDTH_TWIPS = PAGE_WIDTH_TWIPS - 2 * MARGIN_TWIPS

        /** 본문 폭(EMU): 1 twip = 635 EMU */
        internal const val MAX_IMAGE_WIDTH_EMU = BODY_WIDTH_TWIPS * 635L

        internal const val BULLET_NUM_ID = 1

        private val PACKAGE_RELS_XML = XML_DECLARATION +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
            "</Relationships>"

        private val STYLES_XML = XML_DECLARATION +
            "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:docDefaults><w:rPrDefault><w:rPr><w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/></w:rPr></w:rPrDefault><w:pPrDefault/></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
            headingStyle(1, 32) + headingStyle(2, 28) + headingStyle(3, 24) +
            "<w:style w:type=\"character\" w:styleId=\"Hyperlink\"><w:name w:val=\"Hyperlink\"/>" +
            "<w:rPr><w:color w:val=\"0563C1\"/><w:u w:val=\"single\"/></w:rPr></w:style>" +
            "</w:styles>"

        private fun headingStyle(level: Int, halfPointSize: Int): String =
            "<w:style w:type=\"paragraph\" w:styleId=\"Heading$level\">" +
                "<w:name w:val=\"heading $level\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
                "<w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"120\"/><w:outlineLvl w:val=\"${level - 1}\"/></w:pPr>" +
                "<w:rPr><w:b/><w:sz w:val=\"$halfPointSize\"/><w:szCs w:val=\"$halfPointSize\"/></w:rPr>" +
                "</w:style>"
    }
}

internal data class MediaAsset(val relId: String, val fileName: String, val bytes: ByteArray)
internal data class HyperlinkRel(val relId: String, val url: String)
internal data class OrderedNum(val numId: Int, val start: Int)

/**
 * flexmark AST → word/document.xml 본문. 지원 밖 노드는
 * ConversionWarning.UnsupportedFeature + plain text 강등.
 */
private class BodyBuilder(private val assets: AssetResolver) {

    val warnings = mutableListOf<ConversionWarning>()
    val media = mutableListOf<MediaAsset>()
    val hyperlinks = mutableListOf<HyperlinkRel>()
    val orderedListNums = mutableListOf<OrderedNum>()

    private val sb = StringBuilder()
    private val xml = OoxmlWriter(sb)
    private var nextRelId = 3
    private var nextNumId = SimpleOoxmlDocxExportEngine.BULLET_NUM_ID + 1
    private var nextDrawingId = 1

    fun build(document: Node): String {
        for (block in document.children) {
            renderBlock(block)
        }
        return sb.toString()
    }

    private fun renderBlock(block: Node) {
        when (block) {
            is YamlFrontMatterBlock -> Unit // 메타데이터 — DOCX 본문 대상 아님
            is Heading -> renderHeading(block)
            is Paragraph -> renderParagraph(block.children)
            is BulletList -> renderList(block, numId = SimpleOoxmlDocxExportEngine.BULLET_NUM_ID, level = 0)
            is OrderedList -> renderList(block, numId = allocateOrderedNumId(block), level = 0)
            is TableBlock -> renderTable(block)
            is FencedCodeBlock -> renderCodeBlock(block.contentChars.toString())
            is IndentedCodeBlock -> renderCodeBlock(block.contentChars.toString())
            is BlockQuote -> {
                unsupported("block-quote")
                for (child in block.children) renderBlock(child)
            }
            is ThematicBreak -> {
                unsupported("thematic-break")
                emptyParagraph()
            }
            is HtmlBlock -> unsupported("html-block")
            else -> {
                unsupported(block.nodeName)
                val fallback = collectText(block)
                if (fallback.isNotBlank()) plainParagraph(fallback)
            }
        }
    }

    private fun renderHeading(heading: Heading) {
        val level = heading.level
        if (level > 3) unsupported("heading-$level (Heading3로 강등)")
        val styleLevel = level.coerceAtMost(3)
        paragraph(paragraphProperties = "<w:pPr><w:pStyle w:val=\"Heading$styleLevel\"/></w:pPr>") {
            renderInlines(heading.children)
        }
    }

    private fun renderParagraph(inlines: Iterable<Node>) {
        paragraph { renderInlines(inlines) }
    }

    private fun renderList(list: Node, numId: Int, level: Int) {
        val effectiveLevel = if (level > 1) {
            unsupported("list-nesting-depth-${level + 1} (2단으로 강등)")
            1
        } else {
            level
        }
        for (item in list.children) {
            if (item !is ListItem) continue
            if (item is TaskListItem) unsupported("task-list-item (텍스트로 강등)")
            var firstParagraphRendered = false
            for (child in item.children) {
                when (child) {
                    is Paragraph -> {
                        if (!firstParagraphRendered) {
                            firstParagraphRendered = true
                            paragraph(
                                paragraphProperties = "<w:pPr><w:numPr>" +
                                    "<w:ilvl w:val=\"$effectiveLevel\"/><w:numId w:val=\"$numId\"/>" +
                                    "</w:numPr></w:pPr>",
                            ) {
                                if (item is TaskListItem) {
                                    run(item.markerSuffix.toString().trim() + " ")
                                }
                                renderInlines(child.children)
                            }
                        } else {
                            renderParagraph(child.children)
                        }
                    }
                    is BulletList -> renderList(
                        child,
                        numId = SimpleOoxmlDocxExportEngine.BULLET_NUM_ID,
                        level = effectiveLevel + 1,
                    )
                    is OrderedList -> {
                        // 중첩 ordered는 부모 num 공유 (ilvl로 구분)
                        renderList(child, numId = numId.takeIf { list is OrderedList } ?: allocateOrderedNumId(child), level = effectiveLevel + 1)
                    }
                    else -> renderBlock(child)
                }
            }
        }
    }

    private fun allocateOrderedNumId(list: Node): Int {
        val start = (list as? OrderedList)?.startNumber ?: 1
        val numId = nextNumId++
        orderedListNums += OrderedNum(numId, start.coerceAtLeast(1))
        return numId
    }

    private fun renderCodeBlock(content: String) {
        unsupported("code-block (plain text로 강등)")
        for (line in content.trimEnd('\n').split('\n')) {
            plainParagraph(line)
        }
    }

    private fun renderTable(table: TableBlock) {
        val headRows = (table.children.firstOrNull { it is TableHead } as? TableHead)
            ?.children?.filterIsInstance<TableRow>().orEmpty()
        val bodyRows = (table.children.firstOrNull { it is TableBody } as? TableBody)
            ?.children?.filterIsInstance<TableRow>().orEmpty()
        val columnCount = (headRows.firstOrNull() ?: bodyRows.firstOrNull())
            ?.children?.count { it is TableCell } ?: return
        if (columnCount == 0) return
        val columnWidth = SimpleOoxmlDocxExportEngine.BODY_WIDTH_TWIPS / columnCount

        sb.append("<w:tbl>")
        sb.append("<w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/><w:tblBorders>")
        for (edge in listOf("top", "left", "bottom", "right", "insideH", "insideV")) {
            sb.append("<w:$edge w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>")
        }
        sb.append("</w:tblBorders></w:tblPr>")
        sb.append("<w:tblGrid>")
        repeat(columnCount) { sb.append("<w:gridCol w:w=\"$columnWidth\"/>") }
        sb.append("</w:tblGrid>")
        for (row in headRows) renderTableRow(row, columnWidth, isHeader = true)
        for (row in bodyRows) renderTableRow(row, columnWidth, isHeader = false)
        sb.append("</w:tbl>")
        // 표 직후 빈 문단 (Word에서 표가 문서 끝이면 렌더 문제 방지)
        emptyParagraph()
    }

    private fun renderTableRow(row: TableRow, columnWidth: Int, isHeader: Boolean) {
        sb.append("<w:tr>")
        if (isHeader) sb.append("<w:trPr><w:tblHeader/></w:trPr>")
        for (cell in row.children.filterIsInstance<TableCell>()) {
            sb.append("<w:tc><w:tcPr><w:tcW w:w=\"$columnWidth\" w:type=\"dxa\"/></w:tcPr>")
            val alignment = when (cell.alignment) {
                TableCell.Alignment.CENTER -> "<w:jc w:val=\"center\"/>"
                TableCell.Alignment.RIGHT -> "<w:jc w:val=\"right\"/>"
                else -> ""
            }
            paragraph(paragraphProperties = if (alignment.isEmpty()) null else "<w:pPr>$alignment</w:pPr>") {
                renderInlines(cell.children, bold = isHeader)
            }
            sb.append("</w:tc>")
        }
        sb.append("</w:tr>")
    }

    // ---- inline 렌더링 ----

    private fun renderInlines(nodes: Iterable<Node>, bold: Boolean = false, italic: Boolean = false, linkStyle: Boolean = false) {
        for (node in nodes) {
            when (node) {
                is Text -> run(node.chars.toString(), bold, italic, linkStyle)
                is TextBase -> renderInlines(node.children, bold, italic, linkStyle)
                is StrongEmphasis -> renderInlines(node.children, bold = true, italic = italic, linkStyle = linkStyle)
                is Emphasis -> renderInlines(node.children, bold = bold, italic = true, linkStyle = linkStyle)
                is Code -> {
                    unsupported("inline-code (plain text로 강등)")
                    run(node.text.toString(), bold, italic, linkStyle)
                }
                is Link -> renderHyperlink(node.url.toString()) {
                    renderInlines(node.children, bold, italic, linkStyle = true)
                }
                is AutoLink -> renderHyperlink(node.url.toString()) {
                    run(node.url.toString(), bold, italic, linkStyle = true)
                }
                is Image -> renderImage(node, bold, italic)
                is SoftLineBreak -> run(" ", bold, italic, linkStyle)
                is HardLineBreak -> sb.append("<w:r><w:br/></w:r>")
                is HtmlInline -> unsupported("html-inline")
                else -> {
                    unsupported(node.nodeName)
                    val fallback = collectText(node)
                    if (fallback.isNotEmpty()) run(fallback, bold, italic, linkStyle)
                }
            }
        }
    }

    private fun renderHyperlink(url: String, body: () -> Unit) {
        val relId = "rId${nextRelId++}"
        hyperlinks += HyperlinkRel(relId, url)
        sb.append("<w:hyperlink r:id=\"$relId\">")
        body()
        sb.append("</w:hyperlink>")
    }

    private fun renderImage(image: Image, bold: Boolean, italic: Boolean) {
        val url = image.url.toString()
        val altText = collectText(image).ifBlank { url }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            unsupported("external-image ($url — alt 텍스트로 강등)")
            run(altText, bold, italic)
            return
        }
        val bytes = assets.open(url)?.use { it.readBytes() }
        if (bytes == null) {
            unsupported("missing-image-asset ($url — alt 텍스트로 강등)")
            run(altText, bold, italic)
            return
        }
        val dimensions = ImageDimensionReader.read(bytes)
        if (dimensions == null) {
            unsupported("unreadable-image-dimensions ($url — alt 텍스트로 강등)")
            run(altText, bold, italic)
            return
        }

        var cx = dimensions.widthEmu
        var cy = dimensions.heightEmu
        if (cx > SimpleOoxmlDocxExportEngine.MAX_IMAGE_WIDTH_EMU) {
            cy = cy * SimpleOoxmlDocxExportEngine.MAX_IMAGE_WIDTH_EMU / cx
            cx = SimpleOoxmlDocxExportEngine.MAX_IMAGE_WIDTH_EMU
        }

        val extension = when {
            url.endsWith(".png", ignoreCase = true) -> "png"
            else -> "jpg"
        }
        val relId = "rId${nextRelId++}"
        val fileName = "image${media.size + 1}.$extension"
        media += MediaAsset(relId, fileName, bytes)
        val drawingId = nextDrawingId++

        sb.append("<w:r><w:drawing>")
        sb.append("<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">")
        sb.append("<wp:extent cx=\"$cx\" cy=\"$cy\"/>")
        xml.element("wp:docPr", "id" to "$drawingId", "name" to "Picture $drawingId", "descr" to altText)
        sb.append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">")
        sb.append("<pic:pic>")
        sb.append("<pic:nvPicPr>")
        xml.element("pic:cNvPr", "id" to "$drawingId", "name" to fileName)
        sb.append("<pic:cNvPicPr/></pic:nvPicPr>")
        sb.append("<pic:blipFill><a:blip r:embed=\"$relId\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>")
        sb.append("<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>")
        sb.append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>")
        sb.append("</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>")
    }

    // ---- 저수준 조립 ----

    private fun paragraph(paragraphProperties: String? = null, body: () -> Unit) {
        sb.append("<w:p>")
        paragraphProperties?.let { sb.append(it) }
        body()
        sb.append("</w:p>")
    }

    private fun plainParagraph(text: String) {
        paragraph { run(text) }
    }

    private fun emptyParagraph() {
        sb.append("<w:p/>")
    }

    private fun run(text: String, bold: Boolean = false, italic: Boolean = false, linkStyle: Boolean = false) {
        if (text.isEmpty()) return
        sb.append("<w:r>")
        if (bold || italic || linkStyle) {
            sb.append("<w:rPr>")
            if (linkStyle) sb.append("<w:rStyle w:val=\"Hyperlink\"/>")
            if (bold) sb.append("<w:b/>")
            if (italic) sb.append("<w:i/>")
            sb.append("</w:rPr>")
        }
        sb.append("<w:t xml:space=\"preserve\">")
        xml.text(text)
        sb.append("</w:t></w:r>")
    }

    private fun unsupported(feature: String) {
        warnings += ConversionWarning.UnsupportedFeature(feature)
    }

    private fun collectText(node: Node): String = buildString {
        fun visit(current: Node) {
            when (current) {
                is Text -> append(current.chars)
                is Code -> append(current.text)
                is SoftLineBreak -> append(' ')
                else -> for (child in current.children) visit(child)
            }
        }
        visit(node)
    }
}
