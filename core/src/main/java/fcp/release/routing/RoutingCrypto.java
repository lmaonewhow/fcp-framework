package fcp.release.routing;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

final class RoutingCrypto {
    static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    static byte[] key(byte[] key) {
        if (key == null || key.length < 32) throw new IllegalArgumentException("Signing key requires at least 32 bytes");
        return key.clone();
    }
    static byte[] mac(byte[] key, String value) {
        try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key,"HmacSHA256")); return mac.doFinal(value.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    static String sign(byte[] key, String json) {
        String encoded = ENCODER.encodeToString(json.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + ENCODER.encodeToString(mac(key, encoded));
    }
    static String verify(byte[] key, String token) {
        try {
            if (token == null || token.length() > 8192) throw new IllegalArgumentException();
            String[] parts = token.split("\\.",-1);
            if (parts.length != 2 || !MessageDigest.isEqual(mac(key,parts[0]), DECODER.decode(parts[1]))) throw new IllegalArgumentException();
            return new String(DECODER.decode(parts[0]),StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) { throw new fcp.FcpException("INVALID_RELEASE_AFFINITY","Invalid signed release context"); }
    }
    static String digest(byte[] value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
}
