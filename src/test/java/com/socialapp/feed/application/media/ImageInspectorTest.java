package com.socialapp.feed.application.media;

import com.socialapp.feed.application.media.MediaRejectedException.Reason;
import com.socialapp.feed.domain.ImageFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static com.socialapp.feed.application.media.TestImages.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Magic-byte detection, structural validation, limits and metadata stripping of uploads. No client metadata exists at
 * this level at all: the inspector only ever sees bytes.
 */
class ImageInspectorTest {

    private final ImageInspector inspector = new ImageInspector();

    private static void rejected(Runnable call, Reason reason) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(MediaRejectedException.class,
                e -> assertThat(e.reason()).isEqualTo(reason));
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ── accepted formats, decided by magic bytes ─────────────────────────────

    @Test
    void realPngJpegGifAreAcceptedWithTheirDimensions() throws Exception {
        var png = inspector.inspect(png(40, 30));
        assertThat(png.format()).isEqualTo(ImageFormat.PNG);
        assertThat(png.width()).isEqualTo(40);
        assertThat(png.height()).isEqualTo(30);
        assertThat(ImageIO.read(new ByteArrayInputStream(png.bytes()))).isNotNull();

        var jpeg = inspector.inspect(jpeg(64, 48));
        assertThat(jpeg.format()).isEqualTo(ImageFormat.JPEG);
        assertThat(jpeg.width()).isEqualTo(64);
        assertThat(jpeg.height()).isEqualTo(48);
        assertThat(ImageIO.read(new ByteArrayInputStream(jpeg.bytes())).getWidth()).isEqualTo(64);

        var gif = inspector.inspect(gif(20, 10));
        assertThat(gif.format()).isEqualTo(ImageFormat.GIF);
        assertThat(gif.width()).isEqualTo(20);
        assertThat(gif.height()).isEqualTo(10);
        assertThat(ImageIO.read(new ByteArrayInputStream(gif.bytes()))).isNotNull();
    }

    @Test
    void realWebpVariantsAreAccepted() {
        for (byte[] webp : new byte[][] {WEBP_LOSSY, WEBP_LOSSLESS, WEBP_ALPHA, WEBP_ANIMATED}) {
            var result = inspector.inspect(webp);
            assertThat(result.format()).isEqualTo(ImageFormat.WEBP);
            assertThat(result.width()).isEqualTo(1);
            assertThat(result.height()).isEqualTo(1);
            assertThat(result.bytes()).isEqualTo(webp); // nothing to strip
        }
    }

    @Test
    void largeRealJpegIsDecodedWithSubsamplingAndAccepted() {
        var result = inspector.inspect(jpeg(4000, 3000));
        assertThat(result.width()).isEqualTo(4000);
        assertThat(result.height()).isEqualTo(3000);
    }

    // ── rejected: not an image / not an accepted one ─────────────────────────

    @Test
    void emptyIsRejectedAsEmpty() {
        rejected(() -> inspector.inspect(new byte[0]), Reason.EMPTY);
        rejected(() -> inspector.inspect(null), Reason.EMPTY);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>",
            "<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"/>",
            "<!DOCTYPE html><html><body>hi</body></html>",
            "just some text pretending to be an image",
            "%PDF-1.7 ...",
            "BM6\u0000\u0000\u0000"})
    void nonImagesAndUnsupportedFormatsAreRejectedByMagic(String content) {
        rejected(() -> inspector.inspect(ascii(content)), Reason.UNSUPPORTED);
    }

    @Test
    void fakePngWithTextAfterTheSignatureIsRejected() {
        byte[] fake = concat(slice(png(4, 4), 0, 8), ascii("this is not really a png, just text"));
        rejected(() -> inspector.inspect(fake), Reason.UNSUPPORTED);
    }

    @Test
    void fakeHeadersOfEveryFormatAreRejected() {
        rejected(() -> inspector.inspect(concat(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, ascii("garbage"))),
                Reason.UNSUPPORTED);
        rejected(() -> inspector.inspect(ascii("GIF89a\u0001\u0000\u0001\u0000 garbage")), Reason.UNSUPPORTED);
        rejected(() -> inspector.inspect(ascii("GIF89a header claims a huge canvas")), Reason.TOO_LARGE);
        rejected(() -> inspector.inspect(ascii("RIFF\u0010\u0000\u0000\u0000WEBPVP8 garbage")), Reason.UNSUPPORTED);
    }

    @Test
    void truncatedOrCorruptedImagesAreRejected() {
        byte[] png = png(30, 30);
        rejected(() -> inspector.inspect(slice(png, 0, png.length - 20)), Reason.UNSUPPORTED);
        byte[] corrupted = png.clone();
        corrupted[50] ^= 0x55; // inside IDAT: CRC mismatch
        rejected(() -> inspector.inspect(corrupted), Reason.UNSUPPORTED);
        byte[] jpeg = jpeg(30, 30);
        rejected(() -> inspector.inspect(slice(jpeg, 0, jpeg.length / 2)), Reason.UNSUPPORTED);
        byte[] gif = gif(30, 30);
        rejected(() -> inspector.inspect(slice(gif, 0, gif.length - 1)), Reason.UNSUPPORTED);
        rejected(() -> inspector.inspect(slice(WEBP_LOSSY, 0, WEBP_LOSSY.length - 2)), Reason.UNSUPPORTED);
    }

    @Test
    void unsupportedJpegEncodingIsRejected() {
        byte[] jpeg = jpeg(16, 16);
        for (int i = 2; i < jpeg.length - 1; i++) { // turn the baseline SOF0 into an arithmetic SOF9
            if ((jpeg[i] & 0xFF) == 0xFF && (jpeg[i + 1] & 0xFF) == 0xC0) {
                jpeg[i + 1] = (byte) 0xC9;
                break;
            }
        }
        rejected(() -> inspector.inspect(jpeg), Reason.UNSUPPORTED);
    }

    // ── polyglots ─────────────────────────────────────────────────────────────

    @Test
    void pngWithHtmlAppendedAfterItsEndIsRejected() {
        rejected(() -> inspector.inspect(concat(png(8, 8), ascii("<html><script>alert(1)</script></html>"))),
                Reason.UNSUPPORTED);
    }

    @Test
    void pngWithScriptHiddenInATextChunkIsRejected() {
        byte[] polyglot = withPngChunk(png(8, 8), "tEXt", ascii("Comment\0<script>alert(document.cookie)</script>"));
        rejected(() -> inspector.inspect(polyglot), Reason.UNSUPPORTED);
    }

    @Test
    void anyDataAfterTheEndOfPngGifWebpIsRejected_eg_GifarStyleZipAppended() {
        byte[] zip = {'P', 'K', 3, 4, 20, 0, 0, 0, 8, 0};
        rejected(() -> inspector.inspect(concat(png(8, 8), zip)), Reason.UNSUPPORTED);
        rejected(() -> inspector.inspect(concat(gif(8, 8), zip)), Reason.UNSUPPORTED);
        rejected(() -> inspector.inspect(concat(WEBP_LOSSLESS, zip)), Reason.UNSUPPORTED);
    }

    @Test
    void jpegWithScriptInACommentIsRejected() {
        byte[] jpeg = jpeg(8, 8);
        byte[] comment = ascii("<script>alert(1)</script>");
        byte[] com = concat(new byte[] {(byte) 0xFF, (byte) 0xFE, 0, (byte) (comment.length + 2)}, comment);
        rejected(() -> inspector.inspect(concat(slice(jpeg, 0, 2), com, slice(jpeg, 2, jpeg.length))),
                Reason.UNSUPPORTED);
    }

    @Test
    void jpegWithInnocentDataAfterEoiIsAcceptedAndTheTailIsCutOff() {
        byte[] jpeg = jpeg(8, 8);
        var result = inspector.inspect(concat(jpeg, ascii("MOTIONPHOTO-TAIL-DATA")));
        assertThat(contains(result.bytes(), "MOTIONPHOTO")).isFalse();
        assertThat(result.bytes()[result.bytes().length - 2] & 0xFF).isEqualTo(0xFF);
        assertThat(result.bytes()[result.bytes().length - 1] & 0xFF).isEqualTo(0xD9);
    }

    // ── limits ────────────────────────────────────────────────────────────────

    @Test
    void sidesAbove8000AreTooLarge() {
        rejected(() -> inspector.inspect(png(8001, 1)), Reason.TOO_LARGE);
        rejected(() -> inspector.inspect(gif(1, 8001)), Reason.TOO_LARGE);
        rejected(() -> inspector.inspect(jpeg(8001, 8)), Reason.TOO_LARGE);
    }

    @Test
    void decompressionBombIsRejectedFromTheHeaderBeforeDecoding() {
        byte[] bomb = bigBinaryPng(8000, 7000); // 56 MP, a few KB on disk
        assertThat(bomb.length).isLessThan(1_000_000);
        long start = System.nanoTime();
        rejected(() -> inspector.inspect(bomb), Reason.TOO_LARGE);
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2_000); // header check, not a full decode
    }

    // ── metadata stripping ────────────────────────────────────────────────────

    @Test
    void jpegExifIsStrippedButOrientationIsKept_andDisplaySizeIsRotated() throws Exception {
        byte[] withExif = withExif(jpeg(60, 20), 6, "GPS-48.8584N-2.2945E");

        var result = inspector.inspect(withExif);

        assertThat(contains(result.bytes(), "GPS-48")).isFalse();
        assertThat(ImageInspector.exifOrientation(result.bytes(), 2 + 4 + 6, result.bytes().length)).isEqualTo(6);
        assertThat(result.width()).isEqualTo(20); // rotated 90 degrees for display
        assertThat(result.height()).isEqualTo(60);
        assertThat(ImageIO.read(new ByteArrayInputStream(result.bytes())).getWidth()).isEqualTo(60);
    }

    @Test
    void jpegExifWithNormalOrientationIsDroppedEntirely() {
        var result = inspector.inspect(withExif(jpeg(10, 10), 1, "GPS-SECRET"));
        assertThat(contains(result.bytes(), "Exif")).isFalse();
        assertThat(contains(result.bytes(), "GPS-SECRET")).isFalse();
    }

    @Test
    void pngTextChunksAreStripped() throws Exception {
        byte[] png = withPngChunk(png(5, 5), "tEXt", ascii("Author\0Jane Doe, Main Street 1"));
        var result = inspector.inspect(png);
        assertThat(contains(result.bytes(), "Jane Doe")).isFalse();
        assertThat(result.bytes().length).isLessThan(png.length);
        assertThat(ImageIO.read(new ByteArrayInputStream(result.bytes())).getWidth()).isEqualTo(5);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @Test
    void sniffIgnoresEverythingButTheLeadingBytes() {
        assertThat(ImageInspector.sniff(png(1, 1))).contains(ImageFormat.PNG);
        assertThat(ImageInspector.sniff(jpeg(1, 1))).contains(ImageFormat.JPEG);
        assertThat(ImageInspector.sniff(gif(1, 1))).contains(ImageFormat.GIF);
        assertThat(ImageInspector.sniff(WEBP_LOSSY)).contains(ImageFormat.WEBP);
        assertThat(ImageInspector.sniff(ascii("<svg"))).isEmpty();
        assertThat(ImageInspector.sniff(new byte[] {(byte) 0x89, 'P'})).isEmpty();
    }
}
