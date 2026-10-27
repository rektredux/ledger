package com.ledger.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

/**
 * Authenticated encryption for OAuth tokens at rest.
 *
 * <p>A Gmail refresh token is a long-lived credential: anyone holding it can
 * read the user's mail until it is revoked. Storing it as plaintext in a SQLite
 * file would mean a stray backup copy, a synced folder or a borrowed laptop
 * hands over mailbox access. So tokens are encrypted with AES-256-GCM before
 * they are written.
 *
 * <h2>What this does and does not protect against</h2>
 * GCM gives confidentiality and integrity: a tampered ciphertext fails to
 * decrypt rather than decrypting to something wrong. The key lives outside the
 * database — in {@code LEDGER_TOKEN_KEY} or a {@code 0600} file beside it — so
 * the database file alone is not enough to recover a token.
 *
 * <p>It is emphatically <em>not</em> protection against an attacker who already
 * has the user's OS account: such an attacker can read the key file as easily
 * as the database. That threat is addressed by OS-level full-disk encryption
 * and account separation, which the README says so plainly rather than implying
 * this class makes the problem go away. What this does buy is that a copied or
 * leaked database file is not a usable credential.
 */
@Component
public class TokenCipher {

    private static final Logger log = LoggerFactory.getLogger(TokenCipher.class);

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;      // AES-256
    private static final int IV_BYTES = 12;       // GCM standard nonce length
    private static final int TAG_BITS = 128;      // GCM authentication tag

    /** Marks the payload format so the scheme can be changed later. */
    private static final byte VERSION = 1;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public TokenCipher(@Value("${ledger.security.token-key:}") String configuredKey,
                       @Value("${ledger.security.token-key-file:./data/token.key}") String keyFilePath) {
        this.key = resolveKey(configuredKey, Path.of(keyFilePath));
    }

    /**
     * Resolves the encryption key.
     *
     * <p>An explicitly configured key wins, so a deployment can keep it in a
     * secret manager and never write it to disk. Otherwise a key is generated
     * once and stored in a {@code 0600} file, which keeps the application
     * zero-configuration without ever defaulting to a hard-coded key — a
     * hard-coded default would make the encryption decorative, since the
     * "secret" would be in the public source.
     */
    private static SecretKey resolveKey(String configuredKey, Path keyFile) {
        if (configuredKey != null && !configuredKey.isBlank()) {
            byte[] decoded = Base64.getDecoder().decode(configuredKey.trim());
            if (decoded.length != KEY_BYTES) {
                throw new IllegalStateException(
                        "ledger.security.token-key must be exactly " + KEY_BYTES
                                + " base64-encoded bytes (got " + decoded.length + ")");
            }
            return new SecretKeySpec(decoded, ALGORITHM);
        }
        return loadOrCreateKeyFile(keyFile);
    }

    private static SecretKey loadOrCreateKeyFile(Path keyFile) {
        try {
            if (Files.exists(keyFile)) {
                byte[] decoded = Base64.getDecoder().decode(Files.readString(keyFile).trim());
                if (decoded.length != KEY_BYTES) {
                    throw new IllegalStateException("token key file is corrupt: " + keyFile);
                }
                return new SecretKeySpec(decoded, ALGORITHM);
            }

            byte[] fresh = new byte[KEY_BYTES];
            new SecureRandom().nextBytes(fresh);

            Path parent = keyFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(keyFile, Base64.getEncoder().encodeToString(fresh));
            restrictPermissions(keyFile);

            log.info("Generated a new token encryption key at {}. Back this file up: "
                    + "without it, stored provider tokens cannot be decrypted and you "
                    + "will need to reconnect.", keyFile);
            return new SecretKeySpec(fresh, ALGORITHM);

        } catch (IOException e) {
            throw new IllegalStateException("could not read or create the token key file " + keyFile, e);
        }
    }

    /**
     * Narrows the key file to owner-only.
     *
     * <p>Best effort: on a filesystem without POSIX permissions the call is
     * skipped with a warning rather than failing startup. A warning is the
     * right outcome — the user should know the key is not protected, but
     * refusing to start would be worse than running with a documented
     * limitation.
     */
    private static void restrictPermissions(Path keyFile) {
        try {
            Set<PosixFilePermission> ownerOnly =
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(keyFile, ownerOnly);
        } catch (UnsupportedOperationException | IOException e) {
            log.warn("Could not restrict permissions on {}; verify it is not world-readable", keyFile);
        }
    }

    /**
     * Encrypts a token.
     *
     * <p>Output is base64 of {@code version || iv || ciphertext+tag}. A fresh
     * random IV per call is essential with GCM: reusing an IV with the same key
     * is catastrophic, so the nonce is generated here and never derived from
     * anything caller-supplied.
     *
     * @return the ciphertext, or null for null input
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buffer = ByteBuffer.allocate(1 + iv.length + ciphertext.length);
            buffer.put(VERSION).put(iv).put(ciphertext);
            return Base64.getEncoder().encodeToString(buffer.array());

        } catch (Exception e) {
            // The message is never included: it could echo the plaintext.
            throw new TokenCipherException("could not encrypt token", e);
        }
    }

    /**
     * Decrypts a token produced by {@link #encrypt}.
     *
     * @throws TokenCipherException when the payload is malformed, was encrypted
     *     with a different key, or has been tampered with. Callers treat this
     *     as "the stored credential is unusable, ask the user to reconnect"
     *     rather than retrying, because no amount of retrying will fix it.
     */
    public String decrypt(String encoded) {
        if (encoded == null) {
            return null;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(encoded);
            if (raw.length < 1 + IV_BYTES + 1) {
                throw new TokenCipherException("stored token is too short to be valid", null);
            }
            ByteBuffer buffer = ByteBuffer.wrap(raw);
            byte version = buffer.get();
            if (version != VERSION) {
                throw new TokenCipherException("unsupported token encryption version " + version, null);
            }
            byte[] iv = new byte[IV_BYTES];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);

        } catch (TokenCipherException e) {
            throw e;
        } catch (Exception e) {
            throw new TokenCipherException(
                    "could not decrypt the stored token; it may have been encrypted with a "
                            + "different key. Reconnect the provider to store a fresh token.", e);
        }
    }

    /** Encryption or decryption failed. Never carries token material. */
    public static class TokenCipherException extends RuntimeException {
        public TokenCipherException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
