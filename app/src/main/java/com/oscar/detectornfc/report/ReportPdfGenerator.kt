package com.oscar.detectornfc.report

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "ReportPdfGenerator"
private const val CM = 28.3465f

private const val CONFIDENTIAL_TEXT = "C O N F I D E N C I A L"
private const val INTERNAL_ONLY_TEXT = "SÓLO USO INTERNO, PROHIBIDO DIFUSIÓN"
private const val REPORT_TITLE_PREFIX = "INFORME DATOS INTERNOS DOCUMENTO: "

private fun cm(value: Float): Float = value * CM

private val COLOR_NAVY = Color.rgb(31, 42, 68)
private val COLOR_GRID = Color.rgb(176, 182, 196)
private val COLOR_ROW_ALT = Color.rgb(243, 245, 249)
private val COLOR_SECTION = Color.rgb(223, 229, 240)
private val COLOR_GRAY = Color.rgb(112, 118, 130)
private val COLOR_BODY = Color.rgb(40, 44, 52)

private fun String?.nn(): String? = this?.trim()?.takeIf { it.isNotBlank() }

private fun fmtDate(ms: Long): String {
    if (ms <= 0L) return ""
    return SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.forLanguageTag("es-ES")).format(Date(ms))
}

class ReportPdfGenerator(private val context: Context) {

    fun generate(report: ReportData, outFile: File): File {
        val shieldSpain = decodeShield("esc_espana.png")
        val shieldGc = decodeShield("esc_guardia_civil.png")

        val counting = Renderer(report, 0, shieldSpain, shieldGc)
        counting.render()
        val totalPages = counting.pageCount
        counting.release()

        val doc = Renderer(report, totalPages, shieldSpain, shieldGc).render()
        FileOutputStream(outFile).use { doc.writeTo(it) }
        doc.close()
        return outFile
    }

    private fun decodeShield(name: String): Bitmap? = try {
        context.assets.open("escudos/$name").use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        Log.w(TAG, "No se pudo cargar el escudo $name: ${e.message}")
        null
    }
}

private class Renderer(
    private val report: ReportData,
    private val totalPages: Int,
    private val shieldSpain: Bitmap?,
    private val shieldGc: Bitmap?
) {
    private val doc = PdfDocument()
    private lateinit var page: PdfDocument.Page
    private lateinit var canvas: Canvas
    private var pageNumber = 0
    private var y = 0f
    private val generatedAt = System.currentTimeMillis()

    private val pageW = cm(21f)
    private val pageH = cm(29.7f)
    private val marginH = cm(1.5f)
    private val contentW = pageW - 2f * marginH
    private val footerReserve = cm(1.3f)
    private val contentBottom = pageH - footerReserve

    private val pBitmap = Paint(Paint.FILTER_BITMAP_FLAG)

    private val pRed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val pConfidential = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val pInternalOnly = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        textAlign = Paint.Align.CENTER
        textSize = 6f
    }
    private val pTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_NAVY
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }
    private val pSubtitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_GRAY
        textAlign = Paint.Align.CENTER
        textSize = 7.5f
    }
    private val pRuleThick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_NAVY
        strokeWidth = 1.2f
    }
    private val pRuleThin = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_NAVY
        strokeWidth = 0.5f
    }
    private val pGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_GRID
        style = Paint.Style.STROKE
        strokeWidth = 0.7f
    }
    private val pHeaderFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_NAVY
        style = Paint.Style.FILL
    }
    private val pHeaderLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 8f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val pPlaceholder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_GRAY
        textAlign = Paint.Align.CENTER
        textSize = 9f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC)
    }
    private val pImageBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_GRID
        style = Paint.Style.STROKE
        strokeWidth = 0.5f
    }
    private val pBandTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 10f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val pSectionFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_SECTION
        style = Paint.Style.FILL
    }
    private val pSectionLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_NAVY
        textSize = 8.5f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val pRowAltFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_ROW_ALT
        style = Paint.Style.FILL
    }
    private val pCellLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_NAVY
        textSize = 8.5f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val pCellValue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_BODY
        textSize = 8.5f
    }
    private val pFooterL = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_GRAY
        textSize = 7f
    }
    private val pFooterC = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_GRAY
        textAlign = Paint.Align.CENTER
        textSize = 7f
    }
    private val pFooterR = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_GRAY
        textAlign = Paint.Align.RIGHT
        textSize = 7f
    }

    fun render(): PdfDocument {
        startPage(first = true)
        drawHeader()
        drawTitle()
        drawPhotoTable()
        drawDataTable()
        finishPage()
        return doc
    }

    val pageCount: Int
        get() = pageNumber

    fun release() {
        doc.close()
    }

    // ── Páginas ──────────────────────────────────────────────

    private fun startPage(first: Boolean) {
        pageNumber++
        page = doc.startPage(
            PdfDocument.PageInfo.Builder(pageW.toInt(), pageH.toInt(), pageNumber).create()
        )
        canvas = page.canvas
        canvas.drawColor(Color.WHITE)
        if (first) {
            y = 0f
        } else {
            drawContinuationHeader()
        }
    }

    private fun newPage() {
        finishPage()
        startPage(first = false)
    }

    private fun ensureSpace(height: Float) {
        if (y + height > contentBottom) newPage()
    }

    private fun finishPage() {
        drawFooter()
        doc.finishPage(page)
    }

    private fun drawContinuationHeader() {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_GRAY
            textSize = 8f
        }
        val text = REPORT_TITLE_PREFIX + report.titleIdentifier + " (continuación)"
        fitText(p, text, contentW, 8f, 6f)
        canvas.drawText(text, marginH, cm(1.15f), p)
        val lineY = cm(1.4f)
        canvas.drawLine(marginH, lineY, pageW - marginH, lineY, pRuleThin)
        y = cm(1.85f)
    }

    private fun drawFooter() {
        val lineY = pageH - footerReserve + 4f
        canvas.drawLine(marginH, lineY, pageW - marginH, lineY, pGrid)
        val baseline = lineY + 11f
        canvas.drawText("Generado el ${fmtDate(generatedAt)}", marginH, baseline, pFooterL)
        val hash = report.jsonSha256
        if (!hash.isNullOrBlank()) {
            canvas.drawText("SHA-256 JSON: ${hash.take(24)}…", pageW / 2f, baseline, pFooterC)
        }
        val label = if (totalPages > 1) "Página $pageNumber de $totalPages" else "Página $pageNumber"
        canvas.drawText(label, pageW - marginH, baseline, pFooterR)
    }

    // ── Cabecera ─────────────────────────────────────────────

    private fun drawHeader() {
        val shieldH = cm(3f)
        val top = cm(2f)

        val wEs = shieldWidth(shieldSpain, shieldH)
        if (shieldSpain != null) {
            canvas.drawBitmap(
                shieldSpain, null,
                RectF(cm(1f), top, cm(1f) + wEs, top + shieldH), pBitmap
            )
        }
        val wGc = shieldWidth(shieldGc, shieldH)
        if (shieldGc != null) {
            canvas.drawBitmap(
                shieldGc, null,
                RectF(pageW - cm(1f) - wGc, top, pageW - cm(1f), top + shieldH), pBitmap
            )
        }

        val boxW = cm(9f)
        val boxH = cm(2.5f)
        val boxL = (pageW - boxW) / 2f
        val boxT = top + (shieldH - boxH) / 2f

        canvas.drawRect(boxL, boxT, boxL + boxW, boxT + boxH, pRed)
        canvas.drawRect(boxL + 3.5f, boxT + 3.5f, boxL + boxW - 3.5f, boxT + boxH - 3.5f, pRed)

        pConfidential.textSize = 18f
        while (pConfidential.textSize > 6f &&
            pConfidential.measureText(CONFIDENTIAL_TEXT) > boxW - 18f
        ) {
            pConfidential.textSize -= 0.5f
        }
        canvas.drawText(
            CONFIDENTIAL_TEXT,
            boxL + boxW / 2f,
            centerBaseline(boxT, boxH, pConfidential),
            pConfidential
        )

        canvas.drawText(INTERNAL_ONLY_TEXT, pageW / 2f, boxT + boxH + 12f, pInternalOnly)

        y = maxOf(top + shieldH, boxT + boxH + 16f) + cm(0.55f)
    }

    // ── Título ───────────────────────────────────────────────

    private fun drawTitle() {
        val text = REPORT_TITLE_PREFIX + report.titleIdentifier
        fitText(pTitle, text, contentW, 13f, 7.5f)
        val titleH = lineH(pTitle)
        canvas.drawText(text, pageW / 2f, y + verticalOffset(titleH, pTitle), pTitle)
        y += titleH + 3f

        val subtitle =
            "Análisis forense de datos · Lectura de chip NFC · Generado el ${fmtDate(generatedAt)}"
        fitText(pSubtitle, subtitle, contentW, 7.5f, 6f)
        val subH = lineH(pSubtitle)
        canvas.drawText(subtitle, pageW / 2f, y + verticalOffset(subH, pSubtitle), pSubtitle)
        y += subH + 5f

        canvas.drawLine(marginH, y, pageW - marginH, y, pRuleThick)
        y += 3f
        canvas.drawLine(marginH, y, pageW - marginH, y, pRuleThin)
        y += 9f
    }

    // ── Tabla foto / firma ───────────────────────────────────

    private fun drawPhotoTable() {
        val tableH = cm(7f)
        ensureSpace(tableH + cm(0.3f))
        val top = y
        val gap = cm(0.4f)
        val colW = (contentW - gap) / 2f
        drawMediaColumn(
            marginH, top, colW, tableH,
            "FOTOGRAFÍA (DG2)", report.photo, "no se ha obtenido foto"
        )
        drawMediaColumn(
            marginH + colW + gap, top, colW, tableH,
            "FIRMA (DG7)", report.signature, "no se ha obtenido firma"
        )
        y = top + tableH + cm(0.55f)
    }

    private fun drawMediaColumn(
        x: Float, top: Float, w: Float, h: Float,
        label: String, image: Bitmap?, placeholder: String
    ) {
        val headerH = cm(0.55f)
        canvas.drawRect(x, top, x + w, top + h, pGrid)
        canvas.drawRect(x, top, x + w, top + headerH, pHeaderFill)
        canvas.drawText(label, x + w / 2f, centerBaseline(top, headerH, pHeaderLabel), pHeaderLabel)

        val pad = 6f
        val areaL = x + pad
        val areaT = top + headerH + pad
        val areaR = x + w - pad
        val areaB = top + h - pad

        val usable = image?.takeIf { it.width > 0 && it.height > 0 }
        if (usable != null) {
            val scale = minOf(
                (areaR - areaL) / usable.width,
                (areaB - areaT) / usable.height
            )
            val dw = usable.width * scale
            val dh = usable.height * scale
            val dl = areaL + ((areaR - areaL) - dw) / 2f
            val dt = areaT + ((areaB - areaT) - dh) / 2f
            canvas.drawBitmap(usable, null, RectF(dl, dt, dl + dw, dt + dh), pBitmap)
            canvas.drawRect(dl, dt, dl + dw, dt + dh, pImageBorder)
        } else {
            fitText(pPlaceholder, placeholder, areaR - areaL, 9f, 6f)
            val lines = wrapText(pPlaceholder, placeholder, areaR - areaL)
            val lh = lineH(pPlaceholder)
            var ty = areaT + ((areaB - areaT) - lines.size * lh) / 2f
            for (line in lines) {
                canvas.drawText(
                    line,
                    (areaL + areaR) / 2f,
                    ty + verticalOffset(lh, pPlaceholder),
                    pPlaceholder
                )
                ty += lh
            }
        }
    }

    // ── Tabla de datos ───────────────────────────────────────

    private fun drawDataTable() {
        val bandH = cm(0.62f)
        ensureSpace(bandH + cm(1.6f))
        canvas.drawRect(marginH, y, marginH + contentW, y + bandH, pHeaderFill)
        pBandTitle.textSize = 10f
        fitText(pBandTitle, report.tableTitle, contentW - 14f, 10f, 6.5f)
        canvas.drawText(report.tableTitle, pageW / 2f, centerBaseline(y, bandH, pBandTitle), pBandTitle)
        y += bandH + 2f

        val labelW = contentW * 0.38f
        val valueW = contentW * 0.62f
        val minRowH = cm(0.5f)
        var alt = false

        for ((sectionTitle, rows) in buildSections()) {
            if (rows.isEmpty()) continue
            val sectionH = cm(0.48f)
            val firstLines = wrapText(pCellValue, rows[0].second, valueW - 10f)
            val firstRowH = maxOf(minRowH, firstLines.size * lineH(pCellValue) + 8f)
            ensureSpace(sectionH + firstRowH)

            canvas.drawRect(marginH, y, marginH + contentW, y + sectionH, pSectionFill)
            canvas.drawText(
                sectionTitle,
                marginH + 6f,
                centerBaseline(y, sectionH, pSectionLabel),
                pSectionLabel
            )
            y += sectionH

            for ((label, value) in rows) {
                val valueLines = wrapText(pCellValue, value, valueW - 10f)
                val rowH = maxOf(minRowH, valueLines.size * lineH(pCellValue) + 8f)
                ensureSpace(rowH)
                drawRow(label, labelW, valueLines, rowH, alt)
                alt = !alt
                y += rowH
            }
            y += 4f
        }
    }

    private fun drawRow(label: String, labelW: Float, valueLines: List<String>, rowH: Float, alt: Boolean) {
        val left = marginH
        val right = marginH + contentW
        if (alt) canvas.drawRect(left, y, right, y + rowH, pRowAltFill)
        canvas.drawRect(left, y, right, y + rowH, pGrid)
        canvas.drawLine(left + labelW, y, left + labelW, y + rowH, pGrid)

        val labelLines = wrapText(pCellLabel, label, labelW - 10f)
        val lhL = lineH(pCellLabel)
        var ly = y + (rowH - labelLines.size * lhL) / 2f
        for (line in labelLines) {
            canvas.drawText(line, left + 5f, ly + verticalOffset(lhL, pCellLabel), pCellLabel)
            ly += lhL
        }

        val lhV = lineH(pCellValue)
        var vy = y + 4f
        for (line in valueLines) {
            canvas.drawText(line, left + labelW + 5f, vy + verticalOffset(lhV, pCellValue), pCellValue)
            vy += lhV
        }
    }

    private fun buildSections(): List<Pair<String, List<Pair<String, String>>>> {
        val id = report.identity
        fun rows(vararg pairs: Pair<String, String?>): List<Pair<String, String>> =
            pairs.mapNotNull { (k, v) -> v.nn()?.let { k to it } }

        val dgs = report.dataGroups.takeIf { it.isNotEmpty() }
            ?.joinToString(", ") { "DG$it" }
        val fechaLectura = report.scanTimestamp.takeIf { it > 0L }?.let { fmtDate(it) }
        val accesoAlt = report.fallbackUsed?.let { if (it) "Sí" else "No" }

        return listOf(
            "IDENTIFICACIÓN" to rows(
                "Nombre" to id.nombre,
                "Apellidos" to id.apellidos,
                "Número de documento" to id.numeroDocumento,
                "Tipo documental" to id.tipoDocumento,
                "Número de soporte" to id.numeroSoporte,
                "País emisor" to id.pais,
                "Nacionalidad" to id.nacionalidad
            ),
            "NACIMIENTO" to rows(
                "Fecha de nacimiento" to id.fechaNacimiento,
                "Lugar de nacimiento" to id.lugarNacimiento,
                "Sexo" to id.genero
            ),
            "DOMICILIO" to rows("Domicilio" to id.domicilio),
            "FAMILIA" to rows(
                "Nombre del padre" to id.padre,
                "Nombre de la madre" to id.madre
            ),
            "LECTURA DEL CHIP" to rows(
                "UID" to report.uid,
                "CAN utilizado" to maskCAN(report.can),
                "Fecha y hora de lectura" to fechaLectura,
                "Método lector" to report.readerMethod,
                "Acceso alternativo" to accesoAlt,
                "Estado de sesión" to report.sessionStatus,
                "Incidencia" to report.sessionError,
                "Arquitectura LDS" to id.arquitectura,
                "Protocolos" to id.protocolos,
                "Data Groups leídos" to dgs,
                "SHA-256 del JSON de lectura" to report.jsonSha256
            )
        )
    }

    // ── Utilidades de dibujo ─────────────────────────────────

    private fun shieldWidth(bitmap: Bitmap?, height: Float): Float =
        if (bitmap == null || bitmap.height == 0) 0f
        else height * bitmap.width / bitmap.height

    private fun fitText(paint: Paint, text: String, maxWidth: Float, startSize: Float, minSize: Float) {
        paint.textSize = startSize
        var size = startSize
        while (size > minSize && paint.measureText(text) > maxWidth) {
            size -= 0.5f
            paint.textSize = size
        }
    }

    private fun wrapText(paint: Paint, text: String, maxWidth: Float): List<String> {
        val clean = text.trim()
        if (clean.isEmpty()) return listOf("")
        if (paint.measureText(clean) <= maxWidth) return listOf(clean)

        val lines = mutableListOf<String>()
        var line = ""
        for (word in clean.split(Regex("\\s+"))) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) <= maxWidth) {
                line = candidate
            } else {
                if (line.isNotEmpty()) lines.add(line)
                line = if (paint.measureText(word) <= maxWidth) {
                    word
                } else {
                    var rest = word
                    while (paint.measureText(rest) > maxWidth) {
                        var cut = rest.length - 1
                        while (cut > 1 && paint.measureText(rest.substring(0, cut)) > maxWidth) cut--
                        lines.add(rest.substring(0, cut))
                        rest = rest.substring(cut)
                    }
                    rest
                }
            }
        }
        if (line.isNotEmpty()) lines.add(line)
        return lines.ifEmpty { listOf(clean) }
    }

    private fun lineH(paint: Paint): Float = paint.textSize * 1.28f

    private fun verticalOffset(lineHeight: Float, paint: Paint): Float =
        (lineHeight - (paint.ascent() + paint.descent())) / 2f

    private fun centerBaseline(boxTop: Float, boxHeight: Float, paint: Paint): Float =
        boxTop + (boxHeight - (paint.ascent() + paint.descent())) / 2f
}
