package com.bablsoft.accessflow.compliance.internal;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Renders a persisted query-result snapshot to a PDF (#626), cloned from
 * {@link CompliancePdfWriter}'s fixed-grid layout (PDFBox has no table primitive). The watermark
 * is carried twice: as a visible stamp in the heading meta block and the trailing footer line,
 * and as document metadata (author = exporter email, subject = the query request id) — both baked
 * in before signing, so stripping either invalidates the signature.
 */
@Component
class ResultExportPdfWriter {

    private static final PDRectangle PAGE =
            new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()); // landscape
    private static final float MARGIN = 36f;
    private static final float TITLE_SIZE = 15f;
    private static final float META_SIZE = 9f;
    private static final float HEADER_SIZE = 8.5f;
    private static final float CELL_SIZE = 8f;
    private static final float LINE_HEIGHT = 10f;
    private static final float ROW_PADDING = 4f;

    private final PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private final PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    byte[] write(UUID queryRequestId, List<String> columnNames, List<List<Object>> rows,
                 String watermarkHeader, String watermarkFooter, String exporterEmail,
                 Instant generatedAt, boolean truncated) {
        try (var doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            applyMetadata(doc, queryRequestId, exporterEmail, generatedAt, watermarkHeader);
            var canvas = new Canvas(doc);
            drawHeading(canvas, queryRequestId, generatedAt, watermarkHeader, truncated);
            drawTable(canvas, columnNames, rows);
            if (watermarkFooter != null) {
                canvas.ensure(LINE_HEIGHT + 8f);
                canvas.advance(8f);
                canvas.text(bold, META_SIZE, MARGIN, watermarkFooter);
                canvas.advance(LINE_HEIGHT);
            }
            canvas.close();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render result-export PDF", e);
        }
    }

    private static void applyMetadata(PDDocument doc, UUID queryRequestId, String exporterEmail,
                                      Instant generatedAt, String watermarkHeader) {
        var info = new PDDocumentInformation();
        info.setTitle("AccessFlow query results " + queryRequestId);
        info.setSubject("query " + queryRequestId);
        if (exporterEmail != null) {
            info.setAuthor(exporterEmail);
        }
        if (watermarkHeader != null) {
            info.setKeywords(watermarkHeader);
        }
        info.setCreator("AccessFlow");
        info.setCustomMetadataValue("AccessFlow-Generated-At", generatedAt.toString());
        doc.setDocumentInformation(info);
    }

    private void drawHeading(Canvas c, UUID queryRequestId, Instant generatedAt,
                             String watermarkHeader, boolean truncated) throws IOException {
        c.text(bold, TITLE_SIZE, MARGIN, "Query Results Export");
        c.advance(TITLE_SIZE + 6f);
        c.text(font, META_SIZE, MARGIN, "Query request: " + queryRequestId);
        c.advance(LINE_HEIGHT);
        c.text(font, META_SIZE, MARGIN, "Generated at: " + generatedAt
                + (truncated ? "   (results truncated at the configured row cap)" : ""));
        c.advance(LINE_HEIGHT);
        if (watermarkHeader != null) {
            c.text(bold, META_SIZE, MARGIN, watermarkHeader);
            c.advance(LINE_HEIGHT);
        }
        c.advance(8f);
    }

    private void drawTable(Canvas c, List<String> headers, List<List<Object>> rows)
            throws IOException {
        float contentWidth = PAGE.getWidth() - 2 * MARGIN;
        int columns = Math.max(1, headers.size());
        float[] widths = new float[columns];
        float[] xs = new float[columns];
        float x = MARGIN;
        for (int i = 0; i < columns; i++) {
            widths[i] = contentWidth / columns;
            xs[i] = x;
            x += widths[i];
        }

        drawRow(c, headers, xs, widths, bold, HEADER_SIZE);
        if (rows.isEmpty()) {
            c.text(font, CELL_SIZE, MARGIN, "No rows.");
            c.advance(LINE_HEIGHT);
            return;
        }
        for (var row : rows) {
            var cells = new ArrayList<String>(columns);
            for (int i = 0; i < columns; i++) {
                cells.add(i < row.size() ? str(row.get(i)) : "");
            }
            drawRow(c, cells, xs, widths, font, CELL_SIZE);
        }
    }

    private void drawRow(Canvas c, List<String> cells, float[] xs, float[] widths,
                         PDType1Font cellFont, float size) throws IOException {
        var wrapped = new ArrayList<List<String>>(cells.size());
        int maxLines = 1;
        for (int i = 0; i < cells.size(); i++) {
            var lines = wrap(sanitize(cells.get(i)), cellFont, size, widths[i] - 4f);
            wrapped.add(lines);
            maxLines = Math.max(maxLines, lines.size());
        }
        float rowHeight = maxLines * LINE_HEIGHT + ROW_PADDING;
        c.ensure(rowHeight);
        float top = c.y;
        for (int i = 0; i < wrapped.size(); i++) {
            var lines = wrapped.get(i);
            for (int l = 0; l < lines.size(); l++) {
                c.textAt(cellFont, size, xs[i] + 2f, top - l * LINE_HEIGHT, lines.get(l));
            }
        }
        c.y = top - rowHeight;
    }

    private List<String> wrap(String text, PDType1Font cellFont, float size, float maxWidth)
            throws IOException {
        var lines = new ArrayList<String>();
        if (text.isEmpty()) {
            lines.add("");
            return lines;
        }
        var current = new StringBuilder();
        for (var word : text.split(" ", -1)) {
            var candidate = current.isEmpty() ? word : current + " " + word;
            if (stringWidth(cellFont, size, candidate) <= maxWidth || current.isEmpty()) {
                // hard-break a single word that itself overflows
                if (current.isEmpty() && stringWidth(cellFont, size, word) > maxWidth) {
                    lines.addAll(hardBreak(word, cellFont, size, maxWidth));
                    var last = lines.removeLast();
                    current.append(last);
                } else {
                    current.setLength(0);
                    current.append(candidate);
                }
            } else {
                lines.add(current.toString());
                current.setLength(0);
                current.append(word);
            }
        }
        lines.add(current.toString());
        return lines;
    }

    private List<String> hardBreak(String word, PDType1Font cellFont, float size, float maxWidth)
            throws IOException {
        var pieces = new ArrayList<String>();
        var piece = new StringBuilder();
        for (int i = 0; i < word.length(); i++) {
            piece.append(word.charAt(i));
            if (stringWidth(cellFont, size, piece.toString()) > maxWidth && piece.length() > 1) {
                piece.setLength(piece.length() - 1);
                pieces.add(piece.toString());
                piece.setLength(0);
                piece.append(word.charAt(i));
            }
        }
        pieces.add(piece.toString());
        return pieces;
    }

    private static float stringWidth(PDType1Font cellFont, float size, String text)
            throws IOException {
        return cellFont.getStringWidth(text) / 1000f * size;
    }

    /** Keep printable ASCII; replace control / non-WinAnsi characters so showText never throws. */
    private static String sanitize(String value) {
        var sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            sb.append(ch >= 0x20 && ch <= 0x7E ? ch : '?');
        }
        return sb.toString();
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }

    /** Tracks the current page / content stream / vertical cursor, breaking pages as needed. */
    private final class Canvas implements AutoCloseable {
        private final PDDocument doc;
        private PDPageContentStream cs;
        private float y;

        Canvas(PDDocument doc) throws IOException {
            this.doc = doc;
            newPage();
        }

        private void newPage() throws IOException {
            if (cs != null) {
                cs.close();
            }
            var page = new PDPage(PAGE);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            y = PAGE.getHeight() - MARGIN;
        }

        void ensure(float needed) throws IOException {
            if (y - needed < MARGIN) {
                newPage();
            }
        }

        /** Draws text at the current cursor and advances past one line. */
        void text(PDType1Font f, float size, float x, String value) throws IOException {
            textAt(f, size, x, y, value);
        }

        void textAt(PDType1Font f, float size, float x, float baseline, String value)
                throws IOException {
            cs.beginText();
            cs.setFont(f, size);
            cs.newLineAtOffset(x, baseline);
            cs.showText(sanitize(value));
            cs.endText();
        }

        void advance(float delta) {
            y -= delta;
        }

        @Override
        public void close() throws IOException {
            if (cs != null) {
                cs.close();
            }
        }
    }
}
