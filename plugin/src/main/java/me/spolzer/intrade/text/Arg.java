package me.spolzer.intrade.text;

import net.kyori.adventure.text.Component;

public final class Arg {
    final String name;
    final String text;
    final Component component;
    final boolean markup;

    private Arg(String name, String text, Component component, boolean markup) {
        this.name = name;
        this.text = text;
        this.component = component;
        this.markup = markup;
    }

    public static Arg of(String name, Object value) {
        return new Arg(name, String.valueOf(value), null, false);
    }

    public static Arg of(String name, Component value) {
        return new Arg(name, null, value, false);
    }

    public static Arg markup(String name, String value) {
        return new Arg(name, value, null, true);
    }
}
