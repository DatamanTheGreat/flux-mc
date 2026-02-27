package net.datamanix.fluxMc;

import com.moandjiezana.toml.Toml;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Config {
    // Environment variable names for secure key resolution
    private static final String ENV_API_KEY = "FLUXMC_VULTR_API_KEY";
    private static final String PLACEHOLDER_API_KEY = "YOUR_VULTR_API_KEY_HERE";

    // Vultr API Configuration
    public static String VULTR_API_KEY;
    public static String VULTR_REGION;
    public static String VULTR_PLAN;
    public static int VULTR_OS_ID;
    public static String VULTR_INSTANCE_LABEL;
    public static String VULTR_HOSTNAME;
    public static String VULTR_BLOCK_DEVICE_ID;
    public static String VULTR_SSH_KEY_ID;
    public static String VULTR_VPC_ID;

    // Email notification settings
    public static boolean VULTR_SEND_ACTIVATION_EMAIL;

    // Server settings
    public static String TARGET_SERVER_NAME;
    public static int MAX_WAIT_TIME_MS;
    public static int CHECK_INTERVAL_MS;
    public static long EMPTY_TIMEOUT_MS;
    public static long SHUTDOWN_DETECTION_DELAY_MS;
    public static boolean DESTROY_ON_PROXY_SHUTDOWN;

    // Connection behavior settings
    public static boolean KICK_ON_STARTUP;
    public static String KICK_MESSAGE;

    /**
     * Load configuration from file
     */
    public static boolean load(Path dataDirectory, Logger logger) {
        File configFile = dataDirectory.resolve("config.toml").toFile();

        // Create config file with defaults if it doesn't exist
        if (!configFile.exists()) {
            logger.info("Config file not found, creating default config...");
            try {
                Files.createDirectories(dataDirectory);

                // Copy default config from resources
                try (InputStream in = Config.class.getResourceAsStream("/config.toml")) {
                    if (in != null) {
                        Files.copy(in, configFile.toPath());
                        logger.info("Default config created at: {}", configFile.getAbsolutePath());
                        logger.warn("Please edit the config file and restart the server!");
                        return false;
                    }
                }
            } catch (IOException e) {
                logger.error("Failed to create default config file", e);
                return false;
            }
        }

        // Load config
        try {
            Toml toml = new Toml().read(configFile);

            // Vultr settings (API key resolved separately below)
            VULTR_REGION = toml.getString("vultr.region");
            VULTR_PLAN = toml.getString("vultr.plan");
            VULTR_OS_ID = toml.getLong("vultr.os_id").intValue();
            VULTR_INSTANCE_LABEL = toml.getString("vultr.instance_label");
            VULTR_HOSTNAME = toml.getString("vultr.hostname");
            VULTR_BLOCK_DEVICE_ID = toml.getString("vultr.block_device_id");
            VULTR_SSH_KEY_ID = toml.getString("vultr.ssh_key_id", "");
            VULTR_VPC_ID = toml.getString("vultr.vpc_id", "");

            // Email notification settings
            VULTR_SEND_ACTIVATION_EMAIL = toml.getBoolean("vultr.send_activation_email", false);

            // Server settings
            TARGET_SERVER_NAME = toml.getString("server.target_server_name");
            MAX_WAIT_TIME_MS = toml.getLong("server.max_wait_time_seconds", 180L).intValue() * 1000;
            CHECK_INTERVAL_MS = toml.getLong("server.check_interval_seconds", 5L).intValue() * 1000;
            EMPTY_TIMEOUT_MS = toml.getLong("server.empty_timeout_minutes", 30L) * 60 * 1000;
            SHUTDOWN_DETECTION_DELAY_MS = toml.getLong("server.shutdown_detection_delay_seconds", 60L) * 1000;
            DESTROY_ON_PROXY_SHUTDOWN = toml.getBoolean("server.destroy_on_proxy_shutdown", true);

            // Connection behavior settings
            KICK_ON_STARTUP = toml.getBoolean("server.kick_on_startup", false);
            KICK_MESSAGE = toml.getString("server.kick_message",
                    "Server is starting! Please reconnect in 30 seconds.");

            // --- Secure API key resolution ---
            // Priority: environment variable > encrypted keystore > config file (plaintext)
            VULTR_API_KEY = resolveApiKey(toml, dataDirectory, logger);
            if (VULTR_API_KEY == null) {
                return false;
            }

            if (VULTR_BLOCK_DEVICE_ID == null || VULTR_BLOCK_DEVICE_ID.isEmpty() || VULTR_BLOCK_DEVICE_ID.equals("YOUR_BLOCK_DEVICE_ID_HERE")) {
                logger.error("VULTR_BLOCK_DEVICE_ID is not configured! Please edit config.toml");
                return false;
            }

            logger.info("Configuration loaded successfully");
            logger.info("Target server: {}", TARGET_SERVER_NAME);
            logger.info("Vultr region: {}, plan: {}", VULTR_REGION, VULTR_PLAN);
            logger.info("Connection mode: {}", KICK_ON_STARTUP ? "Kick and reconnect" : "Wait in lobby");
            logger.info("Destroy on proxy shutdown: {}", DESTROY_ON_PROXY_SHUTDOWN);
            
            if (SHUTDOWN_DETECTION_DELAY_MS > 0) {
                logger.info("Shutdown detection enabled with {} second delay", SHUTDOWN_DETECTION_DELAY_MS / 1000);
            }

            if (VULTR_SEND_ACTIVATION_EMAIL) {
                logger.info("Activation email notifications enabled");
            }

            return true;
        } catch (Exception e) {
            logger.error("Failed to load config file", e);
            return false;
        }
    }

    /**
     * Resolve the Vultr API key from multiple sources, in priority order:
     *   1. Environment variable  FLUXMC_VULTR_API_KEY
     *   2. Encrypted PKCS12 keystore  (credentials.p12)
     *   3. Plaintext value in config.toml  (auto-migrated to keystore on first load)
     *
     * If the key is found in the config file (plaintext), it is automatically
     * migrated into the encrypted keystore and replaced with a placeholder so
     * the secret no longer sits in a human-readable file.
     */
    private static String resolveApiKey(Toml toml, Path dataDirectory, Logger logger) {
        // 1. Environment variable — highest priority, most secure
        String envKey = System.getenv(ENV_API_KEY);
        if (envKey != null && !envKey.isBlank()) {
            logger.info("Vultr API key loaded from environment variable {}", ENV_API_KEY);
            return envKey;
        }

        // 2. Encrypted keystore
        SecureKeyStorage keyStorage = new SecureKeyStorage(dataDirectory, logger);
        String storedKey = keyStorage.retrieveApiKey();
        if (storedKey != null && !storedKey.isBlank()) {
            logger.info("Vultr API key loaded from encrypted keystore");
            return storedKey;
        }

        // 3. Plaintext config file — fallback with auto-migration
        String configKey = toml.getString("vultr.api_key");
        if (configKey == null || configKey.isBlank() || configKey.equals(PLACEHOLDER_API_KEY)) {
            logger.error("VULTR_API_KEY is not configured!");
            logger.error("Set the environment variable {} or edit config.toml", ENV_API_KEY);
            return null;
        }

        // Key exists in plaintext — migrate it to the encrypted keystore
        logger.warn("API key found in plaintext config file — migrating to encrypted keystore...");
        if (keyStorage.storeApiKey(configKey)) {
            // Replace the plaintext key in config.toml with a migration notice
            removePlaintextKey(dataDirectory, logger);
            logger.info("Migration complete. The plaintext key has been removed from config.toml.");
        } else {
            logger.warn("Could not migrate API key to keystore. "
                    + "Falling back to plaintext config (not recommended).");
        }

        return configKey;
    }

    /**
     * Replace the plaintext api_key value in config.toml with the placeholder,
     * so the secret no longer resides in a human-readable file.
     */
    private static void removePlaintextKey(Path dataDirectory, Logger logger) {
        Path configPath = dataDirectory.resolve("config.toml");
        try {
            List<String> lines = Files.readAllLines(configPath);
            for (int i = 0; i < lines.size(); i++) {
                String trimmed = lines.get(i).trim();
                if (trimmed.startsWith("api_key") && trimmed.contains("=")) {
                    lines.set(i, "api_key = \"" + PLACEHOLDER_API_KEY + "\"");
                    break;
                }
            }
            Files.write(configPath, lines);
        } catch (IOException e) {
            logger.warn("Could not update config.toml to remove plaintext key", e);
        }
    }
}