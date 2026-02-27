
package net.datamanix.fluxMc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ServerManager {
    private final Logger logger;
    private String currentInstanceId;

    public ServerManager(Logger logger) {
        this.logger = logger;
    }

    /**
     * Check if server instance is running
     */
    public CompletableFuture<Boolean> isServerRunning() {
        return CompletableFuture.supplyAsync(() -> {
            if (currentInstanceId == null) {
                currentInstanceId = findInstanceByLabel();
            }

            if (currentInstanceId == null) {
                return false;
            }

            JsonObject instance = fetchInstance(currentInstanceId);
            if (instance == null) {
                currentInstanceId = null;
                return false;
            }

            String status = instance.get("status").getAsString();
            String powerStatus = instance.get("power_status").getAsString();

            logger.debug("Instance status: {}, power_status: {}", status, powerStatus);
            return "active".equals(status) && "running".equals(powerStatus);
        });
    }

    /**
     * Create a new server instance
     */
    public CompletableFuture<Boolean> createInstance() {
        return CompletableFuture.supplyAsync(() -> {
            logger.info("Creating new Vultr VX1 instance...");

            JsonObject payload = buildInstancePayload();
            JsonObject response = executeApiCall("POST", "/v2/instances", payload.toString());

            if (response == null || response.has("error")) {
                logger.error("Failed to create instance: {}",
                        response != null ? response.get("error").getAsString() : "No response");
                return false;
            }

            if (response.has("instance")) {
                JsonObject instance = response.getAsJsonObject("instance");
                currentInstanceId = instance.get("id").getAsString();
                logger.info("Instance created successfully with ID: {}", currentInstanceId);

                if (Config.VULTR_SEND_ACTIVATION_EMAIL) {
                    logger.info("Activation email will be sent to Vultr account holder");
                }

                return true;
            }

            return false;
        });
    }

    /**
     * Destroy server instance
     */
    public CompletableFuture<Boolean> destroyInstance() {
        return CompletableFuture.supplyAsync(() -> {
            if (currentInstanceId == null) {
                currentInstanceId = findInstanceByLabel();
                if (currentInstanceId == null) {
                    logger.warn("No instance found to destroy");
                    return true;
                }
            }

            logger.info("Destroying Vultr instance: {}", currentInstanceId);

            String response = executeApiCallRaw("DELETE", "/v2/instances/" + currentInstanceId, null);

            // Vultr returns empty response (204) on successful deletion
            boolean success = response == null || response.isEmpty() || !response.contains("error");

            if (success) {
                logger.info("Instance destroyed successfully: {}", currentInstanceId);
                currentInstanceId = null;
            } else {
                logger.error("Failed to destroy instance: {}", response);
            }

            return success;
        });
    }

    /**
     * Wait for server to become ready
     */
    public CompletableFuture<Boolean> waitForServerReady() {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            long timeout = Config.MAX_WAIT_TIME_MS;

            while (System.currentTimeMillis() - startTime < timeout) {
                try {
                    if (isServerRunning().join()) {
                        logger.info("Server is now ready!");
                        return true;
                    }
                    Thread.sleep(Config.CHECK_INTERVAL_MS);
                } catch (InterruptedException e) {
                    logger.error("Interrupted while waiting for server", e);
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            logger.warn("Server did not become ready within timeout period");
            return false;
        });
    }

    /**
     * Get the current instance ID
     */
    public String getCurrentInstanceId() {
        return currentInstanceId;
    }

    // ==================== Private Helper Methods ====================

    /**
     * Find instance by label
     */
    private String findInstanceByLabel() {
        JsonObject response = executeApiCall("GET", "/v2/instances", null);

        if (response == null || !response.has("instances")) {
            return null;
        }

        for (var element : response.getAsJsonArray("instances")) {
            JsonObject instance = element.getAsJsonObject();
            String label = instance.get("label").getAsString();

            if (Config.VULTR_INSTANCE_LABEL.equals(label)) {
                String id = instance.get("id").getAsString();
                logger.info("Found existing instance with label '{}': {}", label, id);
                return id;
            }
        }

        return null;
    }

    /**
     * Fetch single instance details
     */
    private JsonObject fetchInstance(String instanceId) {
        JsonObject response = executeApiCall("GET", "/v2/instances/" + instanceId, null);

        if (response == null || response.has("error")) {
            return null;
        }

        return response.has("instance") ? response.getAsJsonObject("instance") : null;
    }

    /**
     * Build instance creation payload
     */
    private JsonObject buildInstancePayload() {
        JsonObject payload = new JsonObject();
        payload.addProperty("region", Config.VULTR_REGION);
        payload.addProperty("plan", Config.VULTR_PLAN);
        payload.addProperty("label", Config.VULTR_INSTANCE_LABEL);
        payload.addProperty("hostname", Config.VULTR_HOSTNAME);
        payload.addProperty("os_id", Config.VULTR_OS_ID);

        // Add activation email flag if enabled
        if (Config.VULTR_SEND_ACTIVATION_EMAIL) {
            payload.addProperty("activation_email", true);
        }

        // Add SSH key if configured
        if (Config.VULTR_SSH_KEY_ID != null && !Config.VULTR_SSH_KEY_ID.isEmpty()) {
            JsonArray sshKeys = new JsonArray();
            sshKeys.add(Config.VULTR_SSH_KEY_ID);
            payload.add("sshkey_id", sshKeys);
        }

        // Add VPC if configured
        if (Config.VULTR_VPC_ID != null && !Config.VULTR_VPC_ID.isEmpty()) {
            JsonArray vpcs = new JsonArray();
            vpcs.add(Config.VULTR_VPC_ID);
            payload.add("attach_vpc", vpcs);
        }

        // Add block device
        JsonArray blockDevices = new JsonArray();
        JsonObject blockDevice = new JsonObject();
        blockDevice.addProperty("block_id", Config.VULTR_BLOCK_DEVICE_ID);
        blockDevice.addProperty("bootable", true);
        blockDevices.add(blockDevice);
        payload.add("block_devices", blockDevices);

        return payload;
    }

    /**
     * Execute API call and return parsed JSON response
     */
    private JsonObject executeApiCall(String method, String endpoint, String jsonPayload) {
        String response = executeApiCallRaw(method, endpoint, jsonPayload);

        if (response == null || response.isEmpty()) {
            return null;
        }

        try {
            return JsonParser.parseString(response).getAsJsonObject();
        } catch (Exception e) {
            logger.error("Error parsing API response", e);
            return null;
        }
    }

    /**
     * Execute API call and return raw response.
     *
     * The Authorization header is passed to curl via stdin (using --config -)
     * rather than as a command-line argument, so the API key is not visible
     * in process listings (ps, /proc/*/cmdline).
     */
    private String executeApiCallRaw(String method, String endpoint, String jsonPayload) {
        try {
            List<String> command = new ArrayList<>();
            command.add("curl");
            command.add("-s");
            command.add("--config");
            command.add("-");       // read config (containing auth header) from stdin
            command.add("-X");
            command.add(method);
            command.add("https://api.vultr.com" + endpoint);

            if (jsonPayload != null) {
                command.add("-H");
                command.add("Content-Type: application/json");
                command.add("-d");
                command.add(jsonPayload);
            }

            Process process = new ProcessBuilder(command).start();

            // Write the auth header through stdin so it never appears in the
            // process argument list.  curl --config format: header = "Name: Value"
            try (var stdin = process.getOutputStream()) {
                String configLine = "header = \"Authorization: Bearer "
                        + Config.VULTR_API_KEY + "\"\n";
                stdin.write(configLine.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                stdin.flush();
            }

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }

            process.waitFor();
            return response.toString();

        } catch (IOException | InterruptedException e) {
            logger.error("Error executing API call: {} {}", method, endpoint, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }
}