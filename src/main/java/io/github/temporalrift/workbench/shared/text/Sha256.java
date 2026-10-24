package io.github.temporalrift.workbench.shared.text;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 digests and their lowercase hex form. */
public final class Sha256 {

    private static final String ALGORITHM = "SHA-256";

    private Sha256() {}

    public static byte[] digest(byte[] content) {
        return instance().digest(content);
    }

    public static String hex(byte[] content) {
        return HexFormat.of().formatHex(digest(content));
    }

    public static String hexUtf8(String text) {
        return hex(text.getBytes(StandardCharsets.UTF_8));
    }

    /** The hex digest of everything the stream yields; the stream is read to its end but not closed. */
    public static String hex(InputStream content) throws IOException {
        var sha = instance();
        var buffer = new byte[64 * 1024];
        for (var read = content.read(buffer); read >= 0; read = content.read(buffer)) {
            sha.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    /** The digest of the parts' UTF-8 text, each prefixed by its byte length so distinct parts can never alias. */
    public static byte[] digestOfParts(Object... parts) {
        var sha = instance();
        for (var part : parts) {
            var bytes = String.valueOf(part).getBytes(StandardCharsets.UTF_8);
            sha.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            sha.update(bytes);
        }
        return sha.digest();
    }

    public static String hexOfParts(Object... parts) {
        return HexFormat.of().formatHex(digestOfParts(parts));
    }

    private static MessageDigest instance() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a mandatory JDK algorithm", e);
        }
    }
}
