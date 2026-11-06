package dev.client.geology.layout;

import java.util.ArrayList;
import java.util.List;

/**
 * Fits text into a width measured with the real font: word wrapping for paragraphs, an ellipsis for
 * single lines. Nothing is ever drawn wider than the space it was given, whatever the language or
 * GUI scale.
 */
public final class TextFit {
    public static final String ELLIPSIS = "…";

    private TextFit() {}

    /** {@code text} cut to {@code maxWidth}, ending in an ellipsis when it had to be cut. */
    public static String ellipsize(String text, int maxWidth, TextMeasure measure) {
        if (measure.width(text) <= maxWidth) {
            return text;
        }
        int ellipsis = measure.width(ELLIPSIS);
        if (ellipsis > maxWidth) {
            return "";
        }
        int end = text.length();
        while (end > 0 && measure.width(text.substring(0, end)) + ellipsis > maxWidth) {
            end--;
        }
        return text.substring(0, end).stripTrailing() + ELLIPSIS;
    }

    /** The first of {@code options} that fits, else the last one ellipsized. */
    public static String firstFitting(TextMeasure measure, int maxWidth, String... options) {
        for (String option : options) {
            if (measure.width(option) <= maxWidth) {
                return option;
            }
        }
        return ellipsize(options[options.length - 1], maxWidth, measure);
    }

    /**
     * Word-wrapped lines no wider than {@code maxWidth}. A word longer than the width is broken
     * between characters; explicit line breaks are kept.
     */
    public static List<String> wrap(String text, int maxWidth, TextMeasure measure) {
        List<String> lines = new ArrayList<>();
        if (maxWidth <= 0) {
            return lines;
        }
        for (String paragraph : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                if (word.isEmpty()) {
                    continue;
                }
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (measure.width(candidate) <= maxWidth) {
                    line.setLength(0);
                    line.append(candidate);
                    continue;
                }
                if (!line.isEmpty()) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                String rest = word;
                while (measure.width(rest) > maxWidth) {
                    int cut = rest.length() - 1;
                    while (cut > 1 && measure.width(rest.substring(0, cut)) > maxWidth) {
                        cut--;
                    }
                    lines.add(rest.substring(0, cut));
                    rest = rest.substring(cut);
                }
                line.append(rest);
            }
            lines.add(line.toString());
        }
        return lines;
    }
}
