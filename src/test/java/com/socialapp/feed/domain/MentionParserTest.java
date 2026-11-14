package com.socialapp.feed.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** The tokenization rule is a frontend contract: every parse case here must behave identically there. */
class MentionParserTest {

    private static final UUID AUTHOR = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID CAROL = UUID.randomUUID();

    private static List<String> parse(String text) {
        return MentionParser.parse(text);
    }

    // ── parse ───────────────────────────────────────────────────────────────

    @Test
    void noCandidates() {
        assertThat(parse(null)).isEmpty();
        assertThat(parse("")).isEmpty();
        assertThat(parse("@")).isEmpty();
        assertThat(parse("@ bob")).isEmpty();
    }

    @Test
    void candidatesAsWrittenInOrderDistinct() {
        assertThat(parse("@bob hi @Carol and @bob again, @BOB")).containsExactly("bob", "Carol", "BOB");
    }

    @Test
    void nameLengthIsThreeToThirtyCodePoints_longerRunIsNoMentionAtAll() {
        assertThat(parse("@ab")).isEmpty();
        assertThat(parse("@abc")).containsExactly("abc");
        assertThat(parse("@" + "a".repeat(30))).containsExactly("a".repeat(30));
        assertThat(parse("@" + "a".repeat(31))).isEmpty();
        assertThat(parse("@" + "ż".repeat(30))).hasSize(1);
        assertThat(parse("@" + "𝐀".repeat(30))).hasSize(1); // 60 UTF-16 units, 30 code points
    }

    @Test
    void dotsDashesAndUnderscoresAreAllowedInside() {
        assertThat(parse("@john.doe @mary-jane @big_al @a.b")).containsExactly("john.doe", "mary-jane", "big_al", "a.b");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "@bob.|bob", "@bob-|bob", "@bob...|bob", "@bob.-.|bob", "@bob,|bob", "(@bob)|bob", "@bob!|bob",
            "@bob:|bob", "@bob?|bob", "hi @bob.|bob", "😀@bob|bob", "#@bob|bob", "/@bob|bob", "&@bob|bob"
    })
    void trailingDotsAndDashesAndPunctuationAreNotPartOfTheName(String text, String expected) {
        assertThat(parse(text)).containsExactly(expected);
    }

    @Test
    void trimmingCanMakeTheNameTooShort() {
        assertThat(parse("@ab.")).isEmpty();
        assertThat(parse("@ab--")).isEmpty();
        assertThat(parse("@...")).isEmpty();
    }

    @Test
    void trailingTrimDoesNotCountTowardsTheMaximum() {
        assertThat(parse("@" + "a".repeat(30) + "...")).containsExactly("a".repeat(30));
    }

    @ParameterizedTest
    @ValueSource(strings = {"bob@example.com", "first.last@example.com", "x-@bobby", "x_@bobby", "x.@bobby",
            "1@bobby", "@@bobby", "ż@bobby", "𝐀@bobby"})
    void atPrecededByNameCharOrAtIsNoMention_soEmailsNeverMatch(String text) {
        assertThat(parse(text)).isEmpty();
    }

    @Test
    void emailInTheMiddleOfTextDoesNotHideARealMention() {
        assertThat(parse("mail bob@example.com or ping @carol")).containsExactly("carol");
    }

    @Test
    void adjacentMentionWithoutSeparatorIsNotASecondMention() {
        assertThat(parse("@bob@carol")).containsExactly("bob");
        assertThat(parse("@bob-@carol")).containsExactly("bob");
    }

    @Test
    void unicodeNames() {
        assertThat(parse("@Zażółć @日本語ユーザー @Иван")).containsExactly("Zażółć", "日本語ユーザー", "Иван");
    }

    // ── resolve ─────────────────────────────────────────────────────────────

    @Test
    void resolvesCaseInsensitivelyToCanonicalUsername() {
        var mentions = MentionParser.resolve(List.of("BOB", "carol"),
                List.of(new Mention(BOB, "bob"), new Mention(CAROL, "Carol")), AUTHOR);

        assertThat(mentions).containsExactly(new Mention(BOB, "bob"), new Mention(CAROL, "Carol"));
    }

    @Test
    void unknownNamesAreIgnored() {
        assertThat(MentionParser.resolve(List.of("ghost", "bob"), List.of(new Mention(BOB, "bob")), AUTHOR))
                .containsExactly(new Mention(BOB, "bob"));
        assertThat(MentionParser.resolve(List.of("ghost"), List.of(), AUTHOR)).isEmpty();
    }

    @Test
    void selfMentionIsNotStored() {
        assertThat(MentionParser.resolve(List.of("me", "bob"),
                List.of(new Mention(AUTHOR, "me"), new Mention(BOB, "bob")), AUTHOR))
                .containsExactly(new Mention(BOB, "bob"));
    }

    @Test
    void sameUserWrittenTwiceInDifferentCaseIsOneMention() {
        assertThat(MentionParser.resolve(List.of("bob", "BOB", "Bob"), List.of(new Mention(BOB, "bob")), AUTHOR))
                .containsExactly(new Mention(BOB, "bob"));
    }

    @Test
    void exactCaseWinsWhenUsernamesDifferOnlyInCase_otherwiseAmbiguousIsSkipped() {
        var known = List.of(new Mention(BOB, "Bob"), new Mention(CAROL, "bob"));

        assertThat(MentionParser.resolve(List.of("Bob"), known, AUTHOR)).containsExactly(new Mention(BOB, "Bob"));
        assertThat(MentionParser.resolve(List.of("bob"), known, AUTHOR)).containsExactly(new Mention(CAROL, "bob"));
        assertThat(MentionParser.resolve(List.of("BOB"), known, AUTHOR)).isEmpty();
    }

    @Test
    void atMostThirtyResolvedMentions_inOrderOfAppearance() {
        var names = IntStream.rangeClosed(1, 40).mapToObj(i -> "user" + i).toList();
        var known = names.stream().map(n -> new Mention(UUID.randomUUID(), n)).toList();

        var mentions = MentionParser.resolve(names, known, AUTHOR);

        assertThat(mentions).hasSize(30);
        assertThat(mentions.get(0).username()).isEqualTo("user1");
        assertThat(mentions.get(29).username()).isEqualTo("user30");
    }

    @Test
    void unresolvedCandidatesDoNotUseUpTheCap() {
        String text = IntStream.rangeClosed(1, 40).mapToObj(i -> "@ghost" + i).collect(Collectors.joining(" "))
                + " @bob";

        assertThat(MentionParser.resolve(parse(text), List.of(new Mention(BOB, "bob")), AUTHOR))
                .containsExactly(new Mention(BOB, "bob"));
    }

    @Test
    void maxLengthPostCannotExceedTheCandidateBound() {
        String text = IntStream.range(0, 100).mapToObj(i -> String.format("@%03d", i)).collect(Collectors.joining(" "));
        assertThat(Post.contentLength(text)).isLessThanOrEqualTo(Post.MAX_CONTENT_LENGTH);
        assertThat(parse(text)).hasSize(MentionParser.MAX_CANDIDATES);
    }
}
