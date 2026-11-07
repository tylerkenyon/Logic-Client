package dev.technix.logic.client.render;

import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.StringDecomposer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

 
public final class NameMask {
    private final Map<String, String> names;
    private final Pattern pattern;

    public NameMask(Map<String, String> names) {
        this.names = Map.copyOf(names);
        String alternatives = names.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed())
            .map(Pattern::quote).collect(java.util.stream.Collectors.joining("|"));
        pattern = Pattern.compile("(?<![A-Za-z0-9_])(?:" + alternatives + ")(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE);
    }

    public boolean matches(Map<String, String> names) { return this.names.equals(names); }

    public String replace(String text) {
        if (text == null || names.isEmpty()) return text;
        return pattern.matcher(text).replaceAll(match -> Matcher.quoteReplacement(names.get(match.group().toLowerCase(Locale.ROOT))));
    }

    public FormattedCharSequence replace(FormattedCharSequence sequence) {
        if (names.isEmpty()) return sequence;
        StringBuilder text = new StringBuilder();
        List<Style> styles = new ArrayList<>();
        sequence.accept((index, style, codepoint) -> {
            text.appendCodePoint(codepoint);
            for (int i = 0; i < Character.charCount(codepoint); i++) styles.add(style);
            return true;
        });
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return sequence;
        List<FormattedCharSequence> parts = new ArrayList<>();
        int end = 0;
        do {
            addOriginal(parts, text, styles, end, matcher.start());
            parts.add(FormattedCharSequence.forward(names.get(matcher.group().toLowerCase(Locale.ROOT)), styles.get(matcher.start())));
            end = matcher.end();
        } while (matcher.find());
        addOriginal(parts, text, styles, end, text.length());
        return FormattedCharSequence.composite(parts);
    }

    private static void addOriginal(List<FormattedCharSequence> parts, StringBuilder text, List<Style> styles, int start, int end) {
        while (start < end) {
            int next = start + 1;
            while (next < end && styles.get(next).equals(styles.get(start))) next++;
            parts.add(FormattedCharSequence.forward(text.substring(start, next), styles.get(start)));
            start = next;
        }
    }

    public FormattedText replace(FormattedText text) {
        if (names.isEmpty()) return text;
        FormattedCharSequence original = sink -> text.visit((style, value) ->
            StringDecomposer.iterateFormatted(value, style, sink) ? Optional.empty() : FormattedText.STOP_ITERATION,
            Style.EMPTY).isEmpty();
        FormattedCharSequence rewritten = replace(original);
        if (rewritten == original) return text;
        List<FormattedText> parts = new ArrayList<>();
        rewritten.accept((index, style, codepoint) -> {
            parts.add(FormattedText.of(new String(Character.toChars(codepoint)), style));
            return true;
        });
        return FormattedText.composite(parts);
    }
}
