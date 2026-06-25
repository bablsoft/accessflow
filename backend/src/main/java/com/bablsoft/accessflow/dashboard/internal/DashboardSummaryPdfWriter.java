package com.bablsoft.accessflow.dashboard.internal;

import com.bablsoft.accessflow.dashboard.api.DashboardWeeklySummary;
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

/**
 * Renders a {@link DashboardWeeklySummary} to a single-column portrait PDF using Apache PDFBox
 * (AF-498). The summary is small (well under a page), so this is a simple top-down text layout with an
 * automatic page break as a safety net. Text is sanitized to WinAnsi so {@code showText} never throws.
 */
@Component
class DashboardSummaryPdfWriter {

    private static final float MARGIN = 50f;
    private static final float TITLE_SIZE = 16f;
    private static final float HEADING_SIZE = 12f;
    private static final float BODY_SIZE = 10f;
    private static final float LINE_HEIGHT = 16f;

    private final PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private final PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    byte[] write(DashboardWeeklySummary s) {
        try (var doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var cursor = new Cursor(doc);
            cursor.line(bold, TITLE_SIZE, "AccessFlow - Weekly Dashboard Summary");
            cursor.gap(6f);
            cursor.line(font, BODY_SIZE, "User: " + orDash(s.userDisplayName()) + " <" + orDash(s.userEmail()) + ">");
            cursor.line(font, BODY_SIZE, "Week: " + s.weekStart() + " to " + s.weekEnd());
            cursor.line(font, BODY_SIZE, "Generated at: " + s.generatedAt());
            cursor.gap(10f);

            cursor.line(bold, HEADING_SIZE, "Headline metrics");
            cursor.line(font, BODY_SIZE, "Queries submitted this week: " + s.totalQueries());
            cursor.line(font, BODY_SIZE, "Pending approvals (as reviewer): " + s.pendingApprovals());
            cursor.line(font, BODY_SIZE, "Open anomalies: " + s.openAnomalies());
            cursor.line(font, BODY_SIZE, "Open optimization suggestions: " + s.openSuggestions());
            cursor.gap(10f);

            cursor.line(bold, HEADING_SIZE, "Status breakdown");
            if (s.statusBreakdown().isEmpty()) {
                cursor.line(font, BODY_SIZE, "No queries submitted this week.");
            } else {
                for (var c : s.statusBreakdown()) {
                    cursor.line(font, BODY_SIZE, "  " + c.status().name() + ": " + c.count());
                }
            }
            cursor.gap(10f);

            cursor.line(bold, HEADING_SIZE, "Risk breakdown");
            if (s.riskBreakdown().isEmpty()) {
                cursor.line(font, BODY_SIZE, "No analyzed queries this week.");
            } else {
                for (var c : s.riskBreakdown()) {
                    cursor.line(font, BODY_SIZE, "  " + c.riskLevel().name() + ": " + c.count());
                }
            }

            cursor.close();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render dashboard summary PDF", e);
        }
    }

    private static String orDash(String v) {
        return v == null || v.isBlank() ? "-" : v;
    }

    /** A top-down text cursor that opens a fresh page when it runs out of vertical room. */
    private final class Cursor {
        private final PDDocument doc;
        private PDPageContentStream stream;
        private float y;

        Cursor(PDDocument doc) throws IOException {
            this.doc = doc;
            newPage();
        }

        private void newPage() throws IOException {
            if (stream != null) {
                stream.close();
            }
            var page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            stream = new PDPageContentStream(doc, page);
            y = PDRectangle.A4.getHeight() - MARGIN;
        }

        void line(PDType1Font f, float size, String text) throws IOException {
            if (y < MARGIN) {
                newPage();
            }
            stream.beginText();
            stream.setFont(f, size);
            stream.newLineAtOffset(MARGIN, y);
            stream.showText(sanitize(text));
            stream.endText();
            y -= LINE_HEIGHT;
        }

        void gap(float h) {
            y -= h;
        }

        void close() throws IOException {
            stream.close();
        }
    }

    private static String sanitize(String text) {
        var sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            sb.append(ch >= 0x20 && ch <= 0xFF ? ch : '?');
        }
        return sb.toString();
    }
}
