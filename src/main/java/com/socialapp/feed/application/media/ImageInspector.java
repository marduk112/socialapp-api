package com.socialapp.feed.application.media;

import com.socialapp.feed.domain.ImageFormat;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

/**
 * Decides whether uploaded bytes are an image we accept, and what exactly is stored. Pure JDK, no trust in client
 * metadata: the client's Content-Type and file name are never looked at.
 *
 * <ol>
 *   <li><b>Magic bytes</b> pick the format: JPEG {@code FF D8 FF}, PNG {@code 89 PNG\r\n\x1a\n}, GIF {@code GIF87a/89a},
 *       WebP {@code RIFF....WEBP}. Anything else (SVG, HTML, PDF, BMP, TIFF, HEIC...) is rejected.</li>
 *   <li><b>Markup scan</b> of the whole file: HTML/script markers anywhere (a polyglot such as a PNG with
 *       {@code <script>} in a text chunk or appended after its end) are rejected. Defense in depth only: the real
 *       protection is that files are served with the image Content-Type of their extension, {@code nosniff} and a
 *       sandboxing CSP. Markers are long or delimited so random compressed bytes practically never match.</li>
 *   <li><b>Structural walk</b> of the container to its end marker, with bounds and (PNG) CRC checks: PNG chunks to
 *       {@code IEND}, GIF blocks to the trailer, WebP RIFF chunks (RIFF size must equal the file size), JPEG marker
 *       segments and entropy-coded scans to {@code EOI}. Data after the end is rejected for PNG/GIF/WebP; for JPEG it
 *       is cut off (phones append gain maps / motion-photo videos after EOI, which we do not keep).</li>
 *   <li><b>Limits</b> from the headers, before any decoding: each side &lt;= {@value #MAX_SIDE} px, at most
 *       {@value #MAX_PIXELS} px per frame, at most {@value #MAX_FRAMES} frames and {@value #MAX_TOTAL_FRAME_PIXELS}
 *       px over all frames (GIF/APNG/animated WebP), at most {@value #MAX_JPEG_SCANS} JPEG scans (progressive-JPEG CPU
 *       bomb). Exceeding them is "too large" (413).</li>
 *   <li><b>Metadata stripping</b> (cheap, structural; the pixel data is copied byte for byte, nothing is re-encoded):
 *       JPEG APP1 (EXIF incl. GPS, XMP), APP13 (IPTC), COM, MPF; the EXIF orientation is kept as a minimal EXIF
 *       segment so the image still displays upright. PNG tEXt/zTXt/iTXt/eXIf/tIME. GIF comment, plain-text and
 *       application extensions other than the animation loop. WebP EXIF/XMP chunks. ICC colour profiles are kept.</li>
 *   <li><b>Decode check</b> (JPEG/PNG/GIF, first frame, via ImageIO with source subsampling so memory stays small) on
 *       the bytes that are stored. WebP has no decoder in the JDK: it is validated structurally only (RIFF layout,
 *       VP8/VP8L/VP8X headers and dimensions). At most {@value #DECODE_SLOTS} decodes run at once.</li>
 * </ol>
 */
public class ImageInspector {

    public static final int MAX_SIDE = 8000;
    public static final long MAX_PIXELS = 50_000_000L;
    public static final int MAX_FRAMES = 1000;
    public static final long MAX_TOTAL_FRAME_PIXELS = 250_000_000L;
    public static final int MAX_JPEG_SCANS = 100;
    static final int DECODE_SLOTS = 2;
    private static final int DECODE_TARGET_SIDE = 256;
    private static final long DECODE_WAIT_SECONDS = 30;

    /** The accepted image: detected format, display size (EXIF orientation applied) and the bytes to store. */
    public record Result(ImageFormat format, int width, int height, byte[] bytes) {}

    private final Semaphore decodeSlots = new Semaphore(DECODE_SLOTS, true);

    public Result inspect(byte[] data) {
        if (data == null || data.length == 0) {
            throw new MediaRejectedException(MediaRejectedException.Reason.EMPTY, "The file is empty");
        }
        var format = sniff(data).orElseThrow(() ->
                MediaRejectedException.unsupported("Only JPEG, PNG, GIF and WebP images are allowed"));
        rejectMarkup(data);
        var parsed = switch (format) {
            case PNG -> png(data);
            case GIF -> gif(data);
            case JPEG -> jpeg(data);
            case WEBP -> webp(data);
        };
        if (format != ImageFormat.WEBP) decodes(format, parsed);
        return new Result(format, parsed.displayWidth, parsed.displayHeight, parsed.bytes);
    }

    // ── Magic bytes ────────────────────────────────────────────────────────────

    static Optional<ImageFormat> sniff(byte[] b) {
        if (startsWith(b, 0, 0xFF, 0xD8, 0xFF)) return Optional.of(ImageFormat.JPEG);
        if (startsWith(b, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) return Optional.of(ImageFormat.PNG);
        if (startsWith(b, 0, 'G', 'I', 'F', '8', '7', 'a') || startsWith(b, 0, 'G', 'I', 'F', '8', '9', 'a')) {
            return Optional.of(ImageFormat.GIF);
        }
        if (startsWith(b, 0, 'R', 'I', 'F', 'F') && startsWith(b, 8, 'W', 'E', 'B', 'P')) {
            return Optional.of(ImageFormat.WEBP);
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] b, int offset, int... expected) {
        if (b.length < offset + expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if ((b[offset + i] & 0xFF) != expected[i]) return false;
        }
        return true;
    }

    // ── Markup scan ────────────────────────────────────────────────────────────

    /** Matched anywhere, case-insensitively. */
    private static final byte[][] MARKERS = ascii("<script", "<iframe", "<object", "<embed", "<!doctype",
            "javascript:", "<?php");
    /** Matched only when followed by whitespace, '>' or '/'. */
    private static final byte[][] DELIMITED_MARKERS = ascii("<html", "<head", "<body");

    private static byte[][] ascii(String... values) {
        var result = new byte[values.length][];
        for (int i = 0; i < values.length; i++) result[i] = values[i].getBytes(StandardCharsets.US_ASCII);
        return result;
    }

    static void rejectMarkup(byte[] b) {
        for (int i = 0; i < b.length; i++) {
            byte c = b[i];
            if (c != '<' && c != 'j' && c != 'J') continue;
            for (byte[] m : MARKERS) {
                if (matchesAt(b, i, m)) throw MediaRejectedException.unsupported("The file contains markup");
            }
            for (byte[] m : DELIMITED_MARKERS) {
                if (matchesAt(b, i, m) && (i + m.length == b.length || isDelimiter(b[i + m.length]))) {
                    throw MediaRejectedException.unsupported("The file contains markup");
                }
            }
        }
    }

    private static boolean matchesAt(byte[] b, int at, byte[] marker) {
        if (at + marker.length > b.length) return false;
        for (int k = 0; k < marker.length; k++) {
            int x = b[at + k];
            if (x >= 'A' && x <= 'Z') x += 'a' - 'A';
            if (x != marker[k]) return false;
        }
        return true;
    }

    private static boolean isDelimiter(byte c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '>' || c == '/';
    }

    // ── Shared parsing helpers ─────────────────────────────────────────────────

    /** Result of a structural parse: raw (stored) size for decoding, display size, bytes to store. */
    private record Parsed(int width, int height, int displayWidth, int displayHeight, byte[] bytes) {}

    private static MediaRejectedException malformed() {
        return MediaRejectedException.unsupported("The image is malformed or truncated");
    }

    private static MediaRejectedException trailingData() {
        return MediaRejectedException.unsupported("Unexpected data after the end of the image");
    }

    private static void need(byte[] b, long offset, long length) {
        if (offset < 0 || length < 0 || offset + length > b.length) throw malformed();
    }

    private static int u8(byte[] b, int i) {
        need(b, i, 1);
        return b[i] & 0xFF;
    }

    private static int u16be(byte[] b, int i) {
        need(b, i, 2);
        return (b[i] & 0xFF) << 8 | (b[i + 1] & 0xFF);
    }

    private static int u16le(byte[] b, int i) {
        need(b, i, 2);
        return (b[i] & 0xFF) | (b[i + 1] & 0xFF) << 8;
    }

    private static int u24le(byte[] b, int i) {
        need(b, i, 3);
        return (b[i] & 0xFF) | (b[i + 1] & 0xFF) << 8 | (b[i + 2] & 0xFF) << 16;
    }

    private static long u32be(byte[] b, int i) {
        need(b, i, 4);
        return ((long) (b[i] & 0xFF) << 24) | (b[i + 1] & 0xFF) << 16 | (b[i + 2] & 0xFF) << 8 | (b[i + 3] & 0xFF);
    }

    private static long u32le(byte[] b, int i) {
        need(b, i, 4);
        return (b[i] & 0xFF) | (b[i + 1] & 0xFF) << 8 | (b[i + 2] & 0xFF) << 16 | ((long) (b[i + 3] & 0xFF) << 24);
    }

    private static String fourCc(byte[] b, int i) {
        need(b, i, 4);
        return new String(b, i, 4, StandardCharsets.ISO_8859_1);
    }

    /** Each side within 1..{@link #MAX_SIDE} and the area within {@link #MAX_PIXELS}. */
    private static void checkFrame(long width, long height) {
        if (width < 1 || height < 1) throw malformed();
        if (width > MAX_SIDE || height > MAX_SIDE || width * height > MAX_PIXELS) {
            throw MediaRejectedException.tooLarge("Image dimensions exceed " + MAX_SIDE + "x" + MAX_SIDE
                    + " pixels or " + MAX_PIXELS / 1_000_000 + " megapixels");
        }
    }

    private static void checkAnimation(long frames, long totalPixels) {
        if (frames > MAX_FRAMES || totalPixels > MAX_TOTAL_FRAME_PIXELS) {
            throw MediaRejectedException.tooLarge("The animation has too many frames");
        }
    }

    // ── PNG ────────────────────────────────────────────────────────────────────

    private static final Set<String> PNG_DROPPED = Set.of("tEXt", "zTXt", "iTXt", "eXIf", "tIME");

    private static Parsed png(byte[] b) {
        var out = new ByteArrayOutputStream(b.length);
        out.write(b, 0, 8);
        int pos = 8;
        long width = 0, height = 0, frames = 1;
        boolean ihdr = false, idat = false, iend = false;
        var crc = new CRC32();
        while (pos < b.length) {
            long length = u32be(b, pos);
            if (length > Integer.MAX_VALUE) throw malformed();
            need(b, pos, 12 + length);
            String type = fourCc(b, pos + 4);
            for (int k = 0; k < 4; k++) {
                char c = type.charAt(k);
                if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) throw malformed();
            }
            int data = pos + 8;
            crc.reset();
            crc.update(b, pos + 4, 4 + (int) length);
            if (crc.getValue() != u32be(b, data + (int) length)) throw malformed();

            if (!ihdr) {
                if (!type.equals("IHDR") || length != 13) throw malformed();
                width = u32be(b, data);
                height = u32be(b, data + 4);
                checkFrame(width, height);
                ihdr = true;
            } else if (type.equals("IHDR")) {
                throw malformed();
            }
            if (type.equals("acTL") && length >= 8) frames = u32be(b, data);
            if (type.equals("IDAT")) idat = true;
            if (!PNG_DROPPED.contains(type)) out.write(b, pos, 12 + (int) length);
            pos += 12 + (int) length;
            if (type.equals("IEND")) {
                iend = true;
                break;
            }
        }
        if (!iend || !idat) throw malformed();
        if (pos != b.length) throw trailingData();
        checkAnimation(frames, frames * width * height);
        return new Parsed((int) width, (int) height, (int) width, (int) height, out.toByteArray());
    }

    // ── GIF ────────────────────────────────────────────────────────────────────

    private static Parsed gif(byte[] b) {
        int width = u16le(b, 6), height = u16le(b, 8);
        checkFrame(width, height);
        int packed = u8(b, 10);
        int pos = 13;
        if ((packed & 0x80) != 0) pos += 3 * (1 << ((packed & 7) + 1));
        need(b, 0, pos);
        var out = new ByteArrayOutputStream(b.length);
        out.write(b, 0, pos);
        long frames = 0, totalPixels = 0;
        while (true) {
            int start = pos;
            int introducer = u8(b, pos);
            if (introducer == 0x3B) { // trailer
                out.write(0x3B);
                pos++;
                break;
            } else if (introducer == 0x21) { // extension
                int label = u8(b, pos + 1);
                boolean keep = label == 0xF9; // graphic control
                if (label == 0xFF) { // application: keep only the animation loop extension
                    String app = "";
                    if (u8(b, pos + 2) == 11) {
                        need(b, pos + 3, 11);
                        app = new String(b, pos + 3, 11, StandardCharsets.ISO_8859_1);
                    }
                    keep = app.equals("NETSCAPE2.0") || app.equals("ANIMEXTS1.0");
                }
                pos = skipSubBlocks(b, pos + 2);
                if (keep) out.write(b, start, pos - start);
            } else if (introducer == 0x2C) { // image descriptor
                need(b, pos, 10);
                int fw = u16le(b, pos + 5), fh = u16le(b, pos + 7);
                checkFrame(fw, fh);
                int fpacked = u8(b, pos + 9);
                pos += 10;
                if ((fpacked & 0x80) != 0) pos += 3 * (1 << ((fpacked & 7) + 1));
                u8(b, pos); // LZW minimum code size
                pos = skipSubBlocks(b, pos + 1);
                out.write(b, start, pos - start);
                frames++;
                totalPixels += (long) fw * fh;
                checkAnimation(frames, totalPixels);
            } else {
                throw malformed();
            }
        }
        if (frames == 0) throw malformed();
        if (pos != b.length) throw trailingData();
        return new Parsed(width, height, width, height, out.toByteArray());
    }

    private static int skipSubBlocks(byte[] b, int pos) {
        while (true) {
            int size = u8(b, pos);
            pos++;
            if (size == 0) return pos;
            need(b, pos, size);
            pos += size;
        }
    }

    // ── JPEG ───────────────────────────────────────────────────────────────────

    private static final byte[] EXIF_HEADER = {'E', 'x', 'i', 'f', 0, 0};
    private static final byte[] MPF_HEADER = {'M', 'P', 'F', 0};

    private static Parsed jpeg(byte[] b) {
        var out = new ByteArrayOutputStream(b.length);
        out.write(0xFF);
        out.write(0xD8);
        int pos = 2;
        int width = -1, height = -1, scans = 0, orientation = 1;
        boolean orientationWritten = false;
        while (true) {
            if (u8(b, pos) != 0xFF) throw malformed();
            while (u8(b, pos + 1) == 0xFF) pos++; // fill bytes
            int marker = u8(b, pos + 1);
            pos += 2;
            if (marker == 0xD9) { // EOI: anything after it is dropped
                out.write(0xFF);
                out.write(0xD9);
                break;
            }
            if (marker == 0xD8 || marker == 0x00) throw malformed();
            if ((marker >= 0xD0 && marker <= 0xD7) || marker == 0x01) { // standalone markers
                out.write(0xFF);
                out.write(marker);
                continue;
            }
            int length = u16be(b, pos);
            if (length < 2) throw malformed();
            need(b, pos, length);
            int segmentStart = pos - 2, payload = pos + 2, end = pos + length;
            boolean keep = true;
            if (isStartOfFrame(marker)) {
                if (width != -1) throw malformed();
                if (marker != 0xC0 && marker != 0xC1 && marker != 0xC2) {
                    throw MediaRejectedException.unsupported("Unsupported JPEG encoding");
                }
                if (length < 8) throw malformed();
                height = u16be(b, payload + 1);
                width = u16be(b, payload + 3);
                checkFrame(width, height);
            } else if (marker == 0xE1) { // APP1: EXIF or XMP - dropped; the orientation survives
                keep = false;
                if (regionStartsWith(b, payload, end, EXIF_HEADER) && !orientationWritten) {
                    orientation = exifOrientation(b, payload + EXIF_HEADER.length, end);
                    if (orientation != 1) out.writeBytes(minimalExif(orientation));
                    orientationWritten = true;
                }
            } else if (marker == 0xE2) { // APP2: ICC profile kept, MPF (index of appended images) dropped
                keep = !regionStartsWith(b, payload, end, MPF_HEADER);
            } else if (marker == 0xED || marker == 0xFE) { // APP13 (IPTC/Photoshop), COM
                keep = false;
            }
            if (keep) out.write(b, segmentStart, end - segmentStart);
            pos = end;

            if (marker == 0xDA) { // SOS: entropy-coded data up to the next marker
                if (width == -1) throw malformed();
                if (++scans > MAX_JPEG_SCANS) throw MediaRejectedException.tooLarge("The JPEG has too many scans");
                int dataStart = pos;
                while (true) {
                    if (u8(b, pos) != 0xFF) {
                        pos++;
                        continue;
                    }
                    int next = u8(b, pos + 1);
                    if (next == 0x00 || (next >= 0xD0 && next <= 0xD7)) pos += 2;
                    else if (next == 0xFF) pos++;
                    else break;
                }
                out.write(b, dataStart, pos - dataStart);
            }
        }
        if (width == -1 || scans == 0) throw malformed();
        boolean swap = orientation >= 5;
        return new Parsed(width, height, swap ? height : width, swap ? width : height, out.toByteArray());
    }

    private static boolean isStartOfFrame(int marker) {
        return marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
    }

    private static boolean regionStartsWith(byte[] b, int from, int to, byte[] prefix) {
        if (to - from < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (b[from + i] != prefix[i]) return false;
        return true;
    }

    /** EXIF orientation (1..8) from a TIFF structure in {@code [tiff, end)}; 1 when absent or unreadable. */
    static int exifOrientation(byte[] b, int tiff, int end) {
        try {
            if (end - tiff < 8) return 1;
            boolean le;
            if (b[tiff] == 'I' && b[tiff + 1] == 'I') le = true;
            else if (b[tiff] == 'M' && b[tiff + 1] == 'M') le = false;
            else return 1;
            if ((le ? u16le(b, tiff + 2) : u16be(b, tiff + 2)) != 42) return 1;
            long ifd = le ? u32le(b, tiff + 4) : u32be(b, tiff + 4);
            if (ifd < 8 || tiff + ifd + 2 > end) return 1;
            int p = tiff + (int) ifd;
            int count = le ? u16le(b, p) : u16be(b, p);
            p += 2;
            for (int i = 0; i < count && p + 12 <= end; i++, p += 12) {
                int tag = le ? u16le(b, p) : u16be(b, p);
                if (tag != 0x0112) continue;
                int type = le ? u16le(b, p + 2) : u16be(b, p + 2);
                if (type != 3) return 1;
                int value = le ? u16le(b, p + 8) : u16be(b, p + 8);
                return value >= 1 && value <= 8 ? value : 1;
            }
            return 1;
        } catch (MediaRejectedException e) {
            return 1;
        }
    }

    /** An APP1 segment holding only IFD0 with the orientation tag (big-endian TIFF). */
    static byte[] minimalExif(int orientation) {
        return new byte[] {
                (byte) 0xFF, (byte) 0xE1, 0x00, 0x22,           // APP1, length 34
                'E', 'x', 'i', 'f', 0, 0,
                'M', 'M', 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,   // TIFF header, IFD0 at 8
                0x00, 0x01,                                     // one entry
                0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, // Orientation, SHORT, count 1
                0x00, (byte) orientation, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00                          // no next IFD
        };
    }

    // ── WebP ───────────────────────────────────────────────────────────────────

    private static final int VP8X_ANIMATION = 0x02, VP8X_XMP = 0x04, VP8X_EXIF = 0x08;

    private static Parsed webp(byte[] b) {
        long riffSize = u32le(b, 4);
        if (riffSize + 8 > b.length) throw malformed();
        if (riffSize + 8 < b.length) throw trailingData();
        var out = new ByteArrayOutputStream(b.length);
        out.write(b, 0, 12);
        int pos = 12;
        String first = null;
        int width = 0, height = 0, flags = 0, vp8xAt = -1;
        boolean bitstream = false;
        long frames = 0, totalPixels = 0;
        while (pos < b.length) {
            String type = fourCc(b, pos);
            long size = u32le(b, pos + 4);
            long padded = size + (size & 1);
            need(b, pos + 8, padded);
            int data = pos + 8, chunkEnd = (int) (data + padded);
            if (first == null) {
                first = type;
                switch (type) {
                    case "VP8 ", "VP8L" -> {
                        int[] wh = bitstreamSize(b, type, data, size);
                        width = wh[0];
                        height = wh[1];
                        checkFrame(width, height);
                        bitstream = true;
                    }
                    case "VP8X" -> {
                        if (size < 10) throw malformed();
                        flags = u8(b, data);
                        width = 1 + u24le(b, data + 4);
                        height = 1 + u24le(b, data + 7);
                        checkFrame(width, height);
                        vp8xAt = out.size();
                    }
                    default -> throw malformed();
                }
            } else if (first.equals("VP8X")) {
                if (type.equals("VP8 ") || type.equals("VP8L")) {
                    int[] wh = bitstreamSize(b, type, data, size);
                    if (bitstream || wh[0] != width || wh[1] != height) throw malformed();
                    bitstream = true;
                } else if (type.equals("ANMF")) {
                    if (size < 16 + 8) throw malformed();
                    int fw = 1 + u24le(b, data + 6), fh = 1 + u24le(b, data + 9);
                    checkFrame(fw, fh);
                    if (fw > width || fh > height) throw malformed();
                    String sub = fourCc(b, data + 16);
                    if (!sub.equals("VP8 ") && !sub.equals("VP8L") && !sub.equals("ALPH")) throw malformed();
                    frames++;
                    totalPixels += (long) fw * fh;
                    checkAnimation(frames, totalPixels);
                }
            } else {
                throw malformed(); // a simple (VP8/VP8L) file has exactly one chunk
            }
            boolean drop = first.equals("VP8X") && (type.equals("EXIF") || type.equals("XMP "));
            if (!drop) out.write(b, pos, chunkEnd - pos);
            pos = chunkEnd;
        }
        if (first == null) throw malformed();
        if (first.equals("VP8X")) {
            boolean animated = (flags & VP8X_ANIMATION) != 0;
            if (animated ? frames == 0 : !bitstream) throw malformed();
        }
        byte[] bytes = out.toByteArray();
        if (vp8xAt >= 0) bytes[vp8xAt + 8] &= (byte) ~(VP8X_EXIF | VP8X_XMP);
        long newRiffSize = bytes.length - 8L;
        bytes[4] = (byte) newRiffSize;
        bytes[5] = (byte) (newRiffSize >> 8);
        bytes[6] = (byte) (newRiffSize >> 16);
        bytes[7] = (byte) (newRiffSize >> 24);
        return new Parsed(width, height, width, height, bytes);
    }

    /** {width, height} from a VP8 (lossy key frame) or VP8L (lossless) bitstream header. */
    private static int[] bitstreamSize(byte[] b, String type, int data, long size) {
        if (type.equals("VP8 ")) {
            if (size < 10) throw malformed();
            int frameTag = u24le(b, data);
            if ((frameTag & 1) != 0) throw malformed(); // must be a key frame
            if (u8(b, data + 3) != 0x9D || u8(b, data + 4) != 0x01 || u8(b, data + 5) != 0x2A) throw malformed();
            return new int[] {u16le(b, data + 6) & 0x3FFF, u16le(b, data + 8) & 0x3FFF};
        }
        if (size < 5 || u8(b, data) != 0x2F) throw malformed();
        long bits = u32le(b, data + 1);
        if ((bits >>> 29) != 0) throw malformed(); // version must be 0
        return new int[] {(int) (bits & 0x3FFF) + 1, (int) ((bits >>> 14) & 0x3FFF) + 1};
    }

    // ── Decode check ───────────────────────────────────────────────────────────

    private void decodes(ImageFormat format, Parsed parsed) {
        boolean acquired;
        try {
            acquired = decodeSlots.tryAcquire(DECODE_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MediaRejectedException(MediaRejectedException.Reason.BUSY, "Server busy, try again later");
        }
        if (!acquired) {
            throw new MediaRejectedException(MediaRejectedException.Reason.BUSY, "Server busy, try again later");
        }
        try {
            decodeFirstFrame(format, parsed);
        } finally {
            decodeSlots.release();
        }
    }

    private static void decodeFirstFrame(ImageFormat format, Parsed parsed) {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format.name().toLowerCase(Locale.ROOT));
        if (!readers.hasNext()) throw new IllegalStateException("No ImageIO reader for " + format);
        ImageReader reader = readers.next();
        try (var in = new MemoryCacheImageInputStream(new ByteArrayInputStream(parsed.bytes))) {
            reader.setInput(in, true, true);
            if (format != ImageFormat.GIF // a GIF's first frame may be smaller than its canvas
                    && (reader.getWidth(0) != parsed.width || reader.getHeight(0) != parsed.height)) {
                throw malformed();
            }
            ImageReadParam param = reader.getDefaultReadParam();
            int step = Math.max(1, (Math.max(parsed.width, parsed.height) + DECODE_TARGET_SIDE - 1) / DECODE_TARGET_SIDE);
            param.setSourceSubsampling(step, step, 0, 0);
            // readRaster also works for CMYK/YCCK JPEGs, which read() rejects ("Unsupported Image Type")
            if (reader.canReadRaster()) reader.readRaster(0, param);
            else reader.read(0, param);
        } catch (MediaRejectedException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw MediaRejectedException.unsupported("The image could not be decoded");
        } finally {
            reader.dispose();
        }
    }
}
