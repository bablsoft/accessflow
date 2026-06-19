package com.bablsoft.accessflow.compliance.internal;

import com.bablsoft.accessflow.compliance.api.Approver;
import com.bablsoft.accessflow.compliance.api.ClassifiedAccessReportRow;
import com.bablsoft.accessflow.compliance.api.ComplianceReport;
import com.bablsoft.accessflow.compliance.api.MatchedClassification;
import com.bablsoft.accessflow.compliance.api.RegulatoryAuditTrailRow;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Renders a {@link ComplianceReport} to a PDF using Apache PDFBox (#459). PDFBox has no table
 * primitive, so this writer lays out a fixed-column grid with per-cell word wrapping and automatic
 * page breaks. Output is a human-readable evidence summary; the signed CSV export carries full
 * fidelity (untruncated SQL, etc.).
 */
@Component
class CompliancePdfWriter {

    private static final PDRectangle PAGE =
            new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()); // landscape
    private static final float MARGIN = 36f;
    private static final float TITLE_SIZE = 15f;
    private static final float META_SIZE = 9f;
    private static final float HEADER_SIZE = 8.5f;
    private static final float CELL_SIZE = 8f;
    private static final float LINE_HEIGHT = 10f;
    private static final float ROW_PADDING = 4f;
    private static final String MULTI_SEP = "; ";

    private final PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private final PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    byte[] write(ComplianceReport report) {
        try (var doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var canvas = new Canvas(doc);
            drawHeading(canvas, report);
            switch (report.type()) {
                case CLASSIFIED_ACCESS -> drawClassifiedAccess(canvas, report.classifiedAccess());
                case REGULATORY_AUDIT_TRAIL -> drawAuditTrail(canvas, report.auditTrail());
            }
            canvas.close();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render compliance PDF", e);
        }
    }

    private void drawHeading(Canvas c, ComplianceReport report) throws IOException {
        var title = switch (report.type()) {
            case CLASSIFIED_ACCESS -> "Compliance Report - Classified Data Access";
            case REGULATORY_AUDIT_TRAIL -> "Compliance Report - Regulatory Audit Trail";
        };
        c.text(bold, TITLE_SIZE, MARGIN, title);
        c.advance(TITLE_SIZE + 6f);
        c.text(font, META_SIZE, MARGIN, "Organization: " + report.organizationId());
        c.advance(LINE_HEIGHT);
        c.text(font, META_SIZE, MARGIN,
                "Period: " + report.periodFrom() + "  to  " + report.periodTo());
        c.advance(LINE_HEIGHT);
        c.text(font, META_SIZE, MARGIN, "Generated at: " + report.generatedAt()
                + (report.truncated() ? "   (results truncated at the configured row cap)" : ""));
        c.advance(LINE_HEIGHT + 8f);
    }

    private void drawClassifiedAccess(Canvas c, List<ClassifiedAccessReportRow> rows) throws IOException {
        var headers = List.of("Executed At", "Datasource", "Submitter", "Type",
                "Referenced Tables", "Classifications", "Rows");
        float[] weights = {2f, 2f, 2.5f, 1f, 3f, 3f, 1f};
        var data = new ArrayList<List<String>>();
        for (var row : rows) {
            data.add(List.of(
                    str(row.executedAt()), nullToEmpty(row.datasourceName()),
                    nullToEmpty(row.submitterEmail()), str(row.queryType()),
                    String.join(MULTI_SEP, row.referencedTables()),
                    formatMatched(row.matched()), str(row.rowsAffected())));
        }
        drawTable(c, headers, weights, data);
    }

    private void drawAuditTrail(Canvas c, List<RegulatoryAuditTrailRow> rows) throws IOException {
        var headers = List.of("Executed At", "Datasource", "Submitter", "Type", "SQL", "Approvers");
        float[] weights = {2f, 2f, 2f, 1f, 4f, 3f};
        var data = new ArrayList<List<String>>();
        for (var row : rows) {
            data.add(List.of(
                    str(row.executedAt()), nullToEmpty(row.datasourceName()),
                    nullToEmpty(row.submitterEmail()), str(row.queryType()),
                    nullToEmpty(row.sqlText()), formatApprovers(row.approvers())));
        }
        drawTable(c, headers, weights, data);
    }

    private void drawTable(Canvas c, List<String> headers, float[] weights,
                           List<List<String>> rows) throws IOException {
        float contentWidth = PAGE.getWidth() - 2 * MARGIN;
        float totalWeight = 0f;
        for (float w : weights) {
            totalWeight += w;
        }
        float[] widths = new float[weights.length];
        float[] xs = new float[weights.length];
        float x = MARGIN;
        for (int i = 0; i < weights.length; i++) {
            widths[i] = contentWidth * weights[i] / totalWeight;
            xs[i] = x;
            x += widths[i];
        }

        drawRow(c, headers, xs, widths, bold, HEADER_SIZE);
        if (rows.isEmpty()) {
            c.text(font, CELL_SIZE, MARGIN, "No records for the selected period.");
            c.advance(LINE_HEIGHT);
            return;
        }
        for (var row : rows) {
            drawRow(c, row, xs, widths, font, CELL_SIZE);
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

    private static float stringWidth(PDType1Font cellFont, float size, String text) throws IOException {
        return cellFont.getStringWidth(text) / 1000f * size;
    }

    private static String formatMatched(List<MatchedClassification> matched) {
        return matched.stream()
                .map(m -> m.columnName() == null
                        ? m.tableName() + ":" + m.classification()
                        : m.tableName() + "." + m.columnName() + ":" + m.classification())
                .collect(Collectors.joining(MULTI_SEP));
    }

    private static String formatApprovers(List<Approver> approvers) {
        return approvers.stream()
                .map(a -> {
                    var name = a.displayName() == null ? a.email() : a.displayName();
                    return a.email() == null ? String.valueOf(name) : name + " <" + a.email() + ">";
                })
                .collect(Collectors.joining(MULTI_SEP));
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

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
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

        void textAt(PDType1Font f, float size, float x, float baseline, String value) throws IOException {
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
