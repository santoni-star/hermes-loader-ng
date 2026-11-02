package com.hermes.loader.bootstrap;

/** Minimal log sink so bootstrap code can talk to the terminal without UI types. */
public interface Log {
    void line(String text);

    default void raw(String text) {
        line(text);
    }
}
