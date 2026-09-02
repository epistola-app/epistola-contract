// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.collect;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Decompresses the collect response.
 *
 * <p>Chooses the decompressor by <em>sniffing the stream's magic bytes</em> rather than trusting
 * {@code Content-Encoding}. In an application server the JAX-RS implementation may already have
 * decoded {@code gzip} itself (RESTEasy does), and whether it also strips the header varies —
 * decoding a second time on the header's say-so would corrupt the stream. The magic bytes describe
 * what is actually there.
 *
 * <p>{@code gzip} is handled by the JDK. {@code lz4} and {@code zstd} are loaded reflectively, so
 * they cost nothing when the consumer has not put those libraries on the classpath; the server
 * only sends them when the request's {@code Accept-Encoding} offered them, which
 * {@link #acceptEncoding()} does only for the codecs actually present.
 */
final class Compression {

    private static final byte[] GZIP_MAGIC = {(byte) 0x1F, (byte) 0x8B};
    private static final byte[] LZ4_FRAME_MAGIC = {(byte) 0x04, (byte) 0x22, (byte) 0x4D, (byte) 0x18};
    private static final byte[] ZSTD_MAGIC = {(byte) 0x28, (byte) 0xB5, (byte) 0x2F, (byte) 0xFD};

    private static final Constructor<?> LZ4_CONSTRUCTOR = findConstructor("net.jpountz.lz4.LZ4FrameInputStream");
    private static final Constructor<?> ZSTD_CONSTRUCTOR = findConstructor("com.github.luben.zstd.ZstdInputStream");

    /** The {@code Accept-Encoding} value naming every codec this classpath can decode. */
    static String acceptEncoding() {
        List<String> codecs = new ArrayList<>();
        if (LZ4_CONSTRUCTOR != null) {
            codecs.add("lz4");
        }
        if (ZSTD_CONSTRUCTOR != null) {
            codecs.add("zstd");
        }
        codecs.add("gzip");
        return String.join(", ", codecs);
    }

    /**
     * Wraps {@code input} in the decompressor its leading bytes call for, or returns it unchanged
     * when the content is already plain NDJSON.
     */
    static InputStream decompress(InputStream input) throws IOException {
        PushbackInputStream pushback = new PushbackInputStream(input, 4);
        byte[] magic = new byte[4];
        int read = readFully(pushback, magic);
        if (read > 0) {
            pushback.unread(magic, 0, read);
        }

        if (startsWith(magic, read, GZIP_MAGIC)) {
            return new GZIPInputStream(pushback);
        }
        if (startsWith(magic, read, LZ4_FRAME_MAGIC)) {
            return wrap(LZ4_CONSTRUCTOR, pushback, "lz4", "net.jpountz.lz4:lz4-java");
        }
        if (startsWith(magic, read, ZSTD_MAGIC)) {
            return wrap(ZSTD_CONSTRUCTOR, pushback, "zstd", "com.github.luben:zstd-jni");
        }
        return pushback;
    }

    private static InputStream wrap(Constructor<?> constructor, InputStream input, String codec, String coordinates)
            throws IOException {
        if (constructor == null) {
            throw new IllegalStateException(
                    "Server sent " + codec + "-compressed results but " + coordinates + " is not on the classpath");
        }
        try {
            return (InputStream) constructor.newInstance(input);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IllegalStateException("Failed to open the " + codec + " decompressor", cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to open the " + codec + " decompressor", e);
        }
    }

    private static int readFully(InputStream input, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int read = input.read(buffer, total, buffer.length - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total;
    }

    private static boolean startsWith(byte[] buffer, int length, byte[] prefix) {
        if (length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (buffer[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static Constructor<?> findConstructor(String className) {
        try {
            return Class.forName(className).getConstructor(InputStream.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private Compression() {
    }
}
