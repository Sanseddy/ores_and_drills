package dev.client.geology.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TextFitTest {
    /** Six pixels a character, like the default font's average; spaces are four. */
    static final TextMeasure FONT =
            new TextMeasure() {
                @Override
                public int width(String text) {
                    int width = 0;
                    for (char c : text.toCharArray()) {
                        width += c == ' ' ? 4 : 6;
                    }
                    return width;
                }

                @Override
                public int lineHeight() {
                    return 9;
                }
            };

    static final String LONG_RU =
            "Объединение съёмок: выключено, используется только текущая программа наблюдений";
    static final String LONG_WORD = "Сверхдлиннаянеразрывнаястрокабезпробеловдляпроверкипереноса";

    @Test
    void ellipsizedTextFitsAndKeepsShortText() {
        assertEquals("Карта", TextFit.ellipsize("Карта", 100, FONT));
        for (int width = 0; width <= 300; width += 7) {
            String fitted = TextFit.ellipsize(LONG_RU, width, FONT);
            assertTrue(FONT.width(fitted) <= width, "width " + width + ": " + fitted);
            if (!fitted.isEmpty() && !fitted.equals(LONG_RU)) {
                assertTrue(fitted.endsWith(TextFit.ELLIPSIS));
            }
        }
    }

    @Test
    void wrappedLinesFitAndKeepEveryWord() {
        for (int width = 20; width <= 300; width += 13) {
            List<String> lines = TextFit.wrap(LONG_RU + " " + LONG_WORD, width, FONT);
            StringBuilder joined = new StringBuilder();
            for (String line : lines) {
                assertTrue(FONT.width(line) <= width, "width " + width + ": " + line);
                joined.append(line);
            }
            assertEquals(
                    (LONG_RU + LONG_WORD).replace(" ", ""), joined.toString().replace(" ", ""));
        }
    }

    @Test
    void explicitBreaksAreKept() {
        assertEquals(List.of("a", "b"), TextFit.wrap("a\nb", 100, FONT));
    }

    @Test
    void firstFittingPrefersTheFullLabel() {
        assertEquals("Previous", TextFit.firstFitting(FONT, 60, "Previous", "Prev"));
        assertEquals("Prev", TextFit.firstFitting(FONT, 30, "Previous", "Prev"));
        assertTrue(FONT.width(TextFit.firstFitting(FONT, 10, "Previous", "Prev")) <= 10);
    }
}
