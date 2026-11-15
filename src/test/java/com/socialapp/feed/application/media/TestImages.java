package com.socialapp.feed.application.media;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.CRC32;

/** Real image bytes for tests: generated with ImageIO (JPEG/PNG/GIF) or known-good WebP samples. */
public final class TestImages {

    private TestImages() {}

    public static byte[] png(int width, int height) {
        return encode(image(width, height, BufferedImage.TYPE_INT_RGB), "png");
    }

    public static byte[] jpeg(int width, int height) {
        return encode(image(width, height, BufferedImage.TYPE_INT_RGB), "jpeg");
    }

    public static byte[] gif(int width, int height) {
        return encode(image(width, height, BufferedImage.TYPE_BYTE_INDEXED), "gif");
    }

    /** A large but tiny-on-disk 1-bit PNG (a "decompression bomb" candidate). */
    public static byte[] bigBinaryPng(int width, int height) {
        return encode(new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY), "png");
    }

    private static BufferedImage image(int width, int height, int type) {
        var img = new BufferedImage(width, height, type);
        var g = img.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, Math.max(1, width / 2), Math.max(1, height / 2));
        g.dispose();
        return img;
    }

    private static byte[] encode(BufferedImage img, String format) {
        try {
            var out = new ByteArrayOutputStream();
            if (!ImageIO.write(img, format, out)) throw new IllegalStateException("No writer for " + format);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Known-good minimal WebP files (lossy, lossless, alpha, animated; 1x1), as used by common feature detection.
    public static final byte[] WEBP_LOSSY = b64("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");
    public static final byte[] WEBP_LOSSLESS = b64("UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==");
    public static final byte[] WEBP_ALPHA = b64("UklGRkoAAABXRUJQVlA4WAoAAAAQAAAAAAAAAAAAQUxQSAwAAAARBxAR/Q9ERP8DAABWUDggGAAAABQBAJ0BKgEAAQAAAP4AAA3AAP7mtQAAAA==");
    public static final byte[] WEBP_ANIMATED = b64("UklGRlIAAABXRUJQVlA4WAoAAAASAAAAAAAAAAAAQU5JTQYAAAD/////AABBTk1GJgAAAAAAAAAAAAAAAAAAAGQAAABWUDhMDQAAAC8AAAAQBxAREYiI/gcA");

    private static byte[] b64(String s) {
        return Base64.getDecoder().decode(s);
    }

    /** Inserts a PNG chunk right after IHDR (valid CRC). */
    public static byte[] withPngChunk(byte[] png, String type, byte[] data) {
        var chunk = ByteBuffer.allocate(12 + data.length);
        chunk.putInt(data.length);
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        chunk.put(typeBytes).put(data);
        var crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        chunk.putInt((int) crc.getValue());
        int at = 8 + 25; // signature + IHDR chunk
        return concat(slice(png, 0, at), chunk.array(), slice(png, at, png.length));
    }

    /**
     * Inserts an EXIF APP1 right after SOI: little-endian TIFF with IFD0 = {Orientation}, followed by {@code extra}
     * bytes (e.g. a fake GPS string) inside the segment.
     */
    public static byte[] withExif(byte[] jpeg, int orientation, String extra) {
        byte[] extraBytes = extra.getBytes(StandardCharsets.US_ASCII);
        var tiff = ByteBuffer.allocate(8 + 2 + 12 + 4 + extraBytes.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        tiff.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        tiff.putShort((short) 1);
        tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        tiff.putInt(0);
        tiff.put(extraBytes);
        byte[] payload = concat("Exif\0\0".getBytes(StandardCharsets.US_ASCII), tiff.array());
        int length = payload.length + 2;
        byte[] segment = concat(new byte[] {(byte) 0xFF, (byte) 0xE1, (byte) (length >> 8), (byte) length}, payload);
        return concat(slice(jpeg, 0, 2), segment, slice(jpeg, 2, jpeg.length));
    }

    public static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }

    public static byte[] slice(byte[] b, int from, int to) {
        return java.util.Arrays.copyOfRange(b, from, to);
    }

    public static boolean contains(byte[] haystack, String needle) {
        return new String(haystack, StandardCharsets.ISO_8859_1).contains(needle);
    }
}
