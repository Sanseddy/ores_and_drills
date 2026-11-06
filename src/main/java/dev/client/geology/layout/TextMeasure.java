package dev.client.geology.layout;

/** Width of rendered text; the client implements it with the game font, tests with a fake. */
public interface TextMeasure {
    int width(String text);

    int lineHeight();
}
