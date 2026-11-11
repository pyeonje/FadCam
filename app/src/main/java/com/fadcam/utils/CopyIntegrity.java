package com.fadcam.utils;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Independent, bounded read-back of a newly written file. */
public final class CopyIntegrity {
    private CopyIntegrity() {}

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public static boolean matches(InputStream input, long expectedBytes, byte[] expectedDigest) throws IOException {
        if (input == null) return false;
        try (InputStream readBack = input) {
            if (expectedBytes < 0) return false;
            MessageDigest actual = sha256();
            byte[] buffer = new byte[8192];
            long total = 0;
            for (int read; (read = readBack.read(buffer)) != -1;) {
                if (read == 0 || read > expectedBytes - total) return false;
                total += read;
                actual.update(buffer, 0, read);
            }
            return total == expectedBytes && MessageDigest.isEqual(expectedDigest, actual.digest());
        }
    }
}
