package net.datamanix.fluxMc;

import org.slf4j.Logger;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

/**
 * Encrypted credential storage using Java's built-in PKCS12 KeyStore.
 *
 * The API key is stored as an AES SecretKey entry inside a password-protected
 * PKCS12 keystore file (credentials.p12) in the plugin data directory.
 *
 * Keystore password resolution order:
 *   1. Environment variable FLUXMC_KEYSTORE_PASSWORD
 *   2. Default passphrase (still better than plaintext — the key is encrypted
 *      on disk and invisible to text searches / accidental git commits)
 */
public class SecureKeyStorage {

    private static final String KEYSTORE_FILE = "credentials.p12";
    private static final String KEYSTORE_TYPE = "PKCS12";
    private static final String API_KEY_ALIAS = "vultr_api_key";
    private static final String KEYSTORE_PASSWORD_ENV = "FLUXMC_KEYSTORE_PASSWORD";
    private static final String DEFAULT_KEYSTORE_PASSWORD = "fluxmc-credential-store";

    private final Path keystorePath;
    private final Logger logger;
    private final char[] password;

    public SecureKeyStorage(Path dataDirectory, Logger logger) {
        this.keystorePath = dataDirectory.resolve(KEYSTORE_FILE);
        this.logger = logger;

        String envPassword = System.getenv(KEYSTORE_PASSWORD_ENV);
        this.password = (envPassword != null ? envPassword : DEFAULT_KEYSTORE_PASSWORD).toCharArray();
    }

    /**
     * Store the API key in the encrypted keystore.
     */
    public boolean storeApiKey(String apiKey) {
        try {
            KeyStore keyStore = loadOrCreateKeyStore();

            SecretKey secretKey = new SecretKeySpec(
                    apiKey.getBytes(StandardCharsets.UTF_8), "AES");
            KeyStore.SecretKeyEntry entry = new KeyStore.SecretKeyEntry(secretKey);
            KeyStore.ProtectionParameter protection = new KeyStore.PasswordProtection(password);

            keyStore.setEntry(API_KEY_ALIAS, entry, protection);

            try (OutputStream os = Files.newOutputStream(keystorePath)) {
                keyStore.store(os, password);
            }

            logger.info("API key stored securely in encrypted keystore: {}", keystorePath);
            return true;
        } catch (Exception e) {
            logger.error("Failed to store API key in keystore", e);
            return false;
        }
    }

    /**
     * Retrieve the API key from the encrypted keystore.
     *
     * @return the decrypted API key, or null if not found / error
     */
    public String retrieveApiKey() {
        if (!Files.exists(keystorePath)) {
            return null;
        }

        try {
            KeyStore keyStore = loadOrCreateKeyStore();

            if (!keyStore.containsAlias(API_KEY_ALIAS)) {
                return null;
            }

            KeyStore.ProtectionParameter protection = new KeyStore.PasswordProtection(password);
            KeyStore.SecretKeyEntry entry =
                    (KeyStore.SecretKeyEntry) keyStore.getEntry(API_KEY_ALIAS, protection);

            if (entry == null) {
                return null;
            }

            return new String(entry.getSecretKey().getEncoded(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.error("Failed to retrieve API key from keystore", e);
            return null;
        }
    }

    /**
     * Check whether the keystore already contains an API key.
     */
    public boolean hasApiKey() {
        if (!Files.exists(keystorePath)) {
            return false;
        }
        try {
            KeyStore keyStore = loadOrCreateKeyStore();
            return keyStore.containsAlias(API_KEY_ALIAS);
        } catch (Exception e) {
            return false;
        }
    }

    private KeyStore loadOrCreateKeyStore() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE);

        if (Files.exists(keystorePath)) {
            try (InputStream is = Files.newInputStream(keystorePath)) {
                keyStore.load(is, password);
            }
        } else {
            keyStore.load(null, password); // initialise empty keystore
        }

        return keyStore;
    }
}
