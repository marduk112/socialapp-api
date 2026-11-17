package com.socialapp.shared.events;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SnippetTest {

    @Test
    void collapsesWhitespaceAndTrims_blankIsNull() {
        assertThat(Snippet.of("  hello\n\n  world\t ")).isEqualTo("hello world");
        assertThat(Snippet.of(" \n ")).isNull();
        assertThat(Snippet.of("")).isNull();
        assertThat(Snippet.of(null)).isNull();
    }

    @Test
    void keepsUpTo120CodePoints_longerIsCutWithAnEllipsis() {
        assertThat(Snippet.of("a".repeat(120))).isEqualTo("a".repeat(120));
        assertThat(Snippet.of("a".repeat(121))).isEqualTo("a".repeat(119) + "…");
    }

    @Test
    void countsCodePoints_neverSplitsASurrogatePair() {
        String emoji = "😀"; // 1 code point, 2 UTF-16 units
        String s = Snippet.of(emoji.repeat(200));
        assertThat(s.codePointCount(0, s.length())).isEqualTo(120);
        assertThat(s).isEqualTo(emoji.repeat(119) + "…");
        assertThat(Snippet.of(emoji.repeat(120))).isEqualTo(emoji.repeat(120));
    }
}
