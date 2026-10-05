package io.github.anyoung1.servercore.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public final class PasswordHasher {

    public static final int ITERATIONS = 600_000;

    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    public static HashResult hash(char[] password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);

        byte[] hash = derive(password, salt, ITERATIONS);

        return new HashResult(
                ITERATIONS,
                Base64.getEncoder().encodeToString(salt),
                Base64.getEncoder().encodeToString(hash)
        );
    }

    public static boolean verify(
            char[] password,
            int iterations,
            String encodedSalt,
            String encodedHash
    ) {
        byte[] salt = Base64.getDecoder().decode(encodedSalt);
        byte[] expectedHash = Base64.getDecoder().decode(encodedHash);

        byte[] actualHash = derive(password, salt, iterations);

        return MessageDigest.isEqual(expectedHash, actualHash);
    }

    private static byte[] derive(
            char[] password,
            byte[] salt,
            int iterations
    ) {
        PBEKeySpec spec =
                new PBEKeySpec(password, salt, iterations, KEY_BITS);

        try {
            SecretKeyFactory factory =
                    SecretKeyFactory.getInstance(
                            "PBKDF2WithHmacSHA256"
                    );

            return factory.generateSecret(spec).getEncoded();

        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "Unable to hash password",
                    e
            );
        } finally {
            spec.clearPassword();
        }
    }

    public record HashResult(
            int iterations,
            String salt,
            String hash
    ) {
    }
}