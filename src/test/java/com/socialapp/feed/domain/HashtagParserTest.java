package com.socialapp.feed.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The tokenization rule is a frontend contract: every case here must behave identically there. */
class HashtagParserTest {

    private static List<String> parse(String text) {
        return HashtagParser.parse(text);
    }

    // ── basic recognition ────────────────────────────────────────────────────

    @Test
    void nullOrEmptyHasNoTags() {
        assertThat(parse(null)).isEmpty();
        assertThat(parse("")).isEmpty();
        assertThat(parse("no tags here")).isEmpty();
        assertThat(parse("#")).isEmpty();
        assertThat(parse("# space")).isEmpty();
    }

    @Test
    void tagAtStartMiddleAndEnd() {
        assertThat(parse("#start middle #mid end #end")).containsExactly("start", "mid", "end");
    }

    @Test
    void normalisedToLowerCaseDeduplicatedInOrderOfFirstAppearance() {
        assertThat(parse("#Hello #hello #WORLD #HeLLo #world #new")).containsExactly("hello", "world", "new");
    }

    @Test
    void lettersDigitsAndUnderscoreBelongToTheTag() {
        assertThat(parse("#a_1 #snake_case #v2 #_lead #2024rocks")).containsExactly("a_1", "snake_case", "v2",
                "_lead", "2024rocks");
    }

    @ParameterizedTest
    @ValueSource(strings = {"#123", "#_", "#__", "#1_2", "#٣٤" /* Arabic-Indic digits */, "#²"})
    void tagMustContainAtLeastOneLetter(String text) {
        assertThat(parse(text)).isEmpty();
    }

    // ── adjacent punctuation ────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "#tag,|tag", "#tag.|tag", "(#tag)|tag", "[#tag]|tag", "\"#tag\"|tag", "#tag!|tag", "#tag?|tag",
            "#tag-suffix|tag", "#tag:|tag", "¡#tag|tag", "-#tag|tag", "@#tag|tag", "*#tag*|tag",
            "#tag…|tag", "😀#tag|tag", "#tag😀|tag", ".#tag|tag"
    })
    void punctuationAroundTheTagIsNotPartOfIt(String text, String expected) {
        assertThat(parse(text)).containsExactly(expected);
    }

    @Test
    void whitespaceAndApostropheAroundTheTag() {
        assertThat(parse("x\n#tag")).containsExactly("tag");
        assertThat(parse("x\t#tag")).containsExactly("tag");
        assertThat(parse("#tag's")).containsExactly("tag");
        assertThat(parse("x\u00A0#tag")).containsExactly("tag"); // no-break space
    }

    // ── preceded-by rule ────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"a#b", "abc#tag", "1#tag", "_#tag", "&#123;", "&#x41;", "&#tag",
            "http://x/#anchor", "https://example.com/page#section", "https://x.com/#/route", "a/#tag", "é#tag"})
    void hashPrecededByWordCharAmpersandOrSlashIsNotATag(String text) {
        assertThat(parse(text)).isEmpty();
    }

    @Test
    void secondHashDirectlyAfterATagIsNotATag() {
        assertThat(parse("#tag#tag2")).containsExactly("tag");
        assertThat(parse("#tag#tag2 #tag3")).containsExactly("tag", "tag3");
    }

    @Test
    void hashPrecededByHashOrOtherPunctuationStillStartsATag() {
        // '#' is not in [\p{L}\p{N}_&/], so "##tag": the second '#' is a valid start. The first '#' has an
        // empty run and yields nothing.
        assertThat(parse("x ##tag")).containsExactly("tag");
        assertThat(parse("##tag")).containsExactly("tag");
    }

    @Test
    void urlWithQueryHashIsATagOnlyIfPrecededByNonBlockedChar() {
        assertThat(parse("see example.com?#a")).containsExactly("a"); // '?' does not block (documented edge)
    }

    // ── length ──────────────────────────────────────────────────────────────

    @Test
    void fiftyCharsIsATag_fiftyOneIsNoTagAtAll() {
        String fifty = "a".repeat(50);
        assertThat(parse("#" + fifty)).containsExactly(fifty);
        assertThat(parse("#" + fifty + "b")).isEmpty();
        assertThat(parse("#" + fifty + "b #ok")).containsExactly("ok");
        assertThat(parse("#" + fifty + ".")).containsExactly(fifty);
    }

    @Test
    void lengthIsCountedInCodePoints() {
        String fiftySupplementary = "𝐀".repeat(50); // U+1D400, a letter outside the BMP (2 UTF-16 units)
        assertThat(parse("#" + fiftySupplementary)).containsExactly(fiftySupplementary.toLowerCase(java.util.Locale.ROOT));
        assertThat(parse("#" + fiftySupplementary + "𝐀")).isEmpty();
    }

    @Test
    void atMostThirtyTagsPerPost_extrasIgnored() {
        String text = IntStream.rangeClosed(1, 35).mapToObj(i -> "#t" + i).collect(Collectors.joining(" "));
        var tags = parse(text);
        assertThat(tags).hasSize(30).startsWith("t1").endsWith("t30");
    }

    @Test
    void duplicatesDoNotCountTowardsTheCap() {
        String text = "#dup #DUP #Dup " + IntStream.rangeClosed(1, 29).mapToObj(i -> "#t" + i)
                .collect(Collectors.joining(" "));
        assertThat(parse(text)).hasSize(30).contains("dup", "t29");
    }

    // ── unicode ─────────────────────────────────────────────────────────────

    @Test
    void unicodeLettersAndNumbers() {
        assertThat(parse("#zażółć #Gęślą #日本語 #Привет #مرحبا #ελληνικά #Ⅻtag #café2"))
                .containsExactly("zażółć", "gęślą", "日本語", "привет", "مرحبا", "ελληνικά", "ⅻtag", "café2");
    }

    @Test
    void supplementaryLetterBeforeHashBlocksTheTag() {
        // Java regex lookbehind gets this wrong (it only sees the low surrogate); the scanner must not.
        assertThat(parse("𝐀#tag")).isEmpty();
        assertThat(parse("𝐀 #tag")).containsExactly("tag");
    }

    @Test
    void emojiIsNotATagCharacter() {
        assertThat(parse("#fun😀")).containsExactly("fun");
        assertThat(parse("#😀")).isEmpty();
    }

    @Test
    void combiningMarksEndTheTag_documentedLimitationOfTheRule() {
        // NFD "é" = 'e' + U+0301 (a mark, not \p{L}): the tag stops before the mark.
        assertThat(parse("#café")).containsExactly("cafe");
    }

    @Test
    void lowerCasingUsesLocaleRoot() {
        assertThat(parse("#TITLE")).containsExactly("title"); // not the Turkish dotless ı
        assertThat(parse("#ΟΔΟΣ")).containsExactly("\u03BF\u03B4\u03BF\u03C2"); // final sigma, as in JS
    }

    // ── normaliseQuery (path parameter) ─────────────────────────────────────

    @ParameterizedTest
    @CsvSource({"hello,hello", "Hello,hello", "#Hello,hello", "HELLO_2,hello_2", "Zażółć,zażółć", "' tag ',tag"})
    void queryIsNormalisedLikeParsedTags(String raw, String expected) {
        assertThat(HashtagParser.normaliseQuery(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "#", "##tag", "123", "_", "two words", "tag!", "a-b", "😀"})
    void invalidQueryIsRejected(String raw) {
        assertThatThrownBy(() -> HashtagParser.normaliseQuery(raw)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid hashtag");
    }

    @Test
    void queryLongerThanFiftyIsRejected_nullToo() {
        assertThat(HashtagParser.normaliseQuery("a".repeat(50))).hasSize(50);
        assertThatThrownBy(() -> HashtagParser.normaliseQuery("a".repeat(51)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HashtagParser.normaliseQuery(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
