package net.datamanix.fluxMc;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ServerMonitor {
    private final ProxyServer proxy;
    private final ServerManager serverManager;
    private final Logger logger;
    private final ScheduledExecutorService scheduler;
    
    private long lastEmptyTime = -1;
    private long serverOfflineTime = -1;
    private boolean wasServerOnline = false;
    private boolean monitoring = false;

    public ServerMonitor(ProxyServer proxy, ServerManager serverManager, Logger logger) {
        this.proxy = proxy;
        this.serverManager = serverManager;
        this.logger = logger;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    /**
     * Start monitoring the server for emptiness and shutdowns
     */
    public void startMonitoring() {
        if (monitoring) {
            return;
        }
        
        monitoring = true;
        logger.info("Starting server monitor...");
        
        scheduler.scheduleAtFixedRate(() -> {
            try {
                checkServerStatus();
            } catch (Exception e) {
                logger.error("Error in server monitor", e);
            }
        }, 10, 10, TimeUnit.SECONDS); // Check every 10 seconds
    }

    /**
     * Check server status for both online/offline state and emptiness
     */
    private void checkServerStatus() {
        Optional<RegisteredServer> serverOpt = proxy.getServer(Config.TARGET_SERVER_NAME);
        
        if (!serverOpt.isPresent()) {
            logger.debug("Target server not registered");
            resetTimers();
            return;
        }

        RegisteredServer server = serverOpt.get();
        
        // Ping the server to check if it's online
        server.ping().thenAccept(ping -> {
            // Server is online and responding
            handleServerOnline(server);
        }).exceptionally(error -> {
            // Server is offline or not responding
            handleServerOffline();
            return null;
        });
    }

    /**
     * Handle when server is online
     */
    private void handleServerOnline(RegisteredServer server) {
        // Reset offline timer if server came back online
        if (serverOfflineTime != -1) {
            logger.info("Server is back online. Canceling shutdown detection.");
            serverOfflineTime = -1;
        }
        
        wasServerOnline = true;
        
        // Check for emptiness (existing logic)
        checkServerEmptiness(server);
    }

    /**
     * Handle when server is offline
     */
    private void handleServerOffline() {
        // Only start shutdown detection if:
        // 1. Shutdown detection is enabled (delay > 0)
        // 2. Server was previously online (not just starting up)
        if (Config.SHUTDOWN_DETECTION_DELAY_MS <= 0 || !wasServerOnline) {
            return;
        }

        // Check if there's a Vultr instance running
        serverManager.isServerRunning().thenAccept(instanceRunning -> {
            if (!instanceRunning) {
                // No instance to destroy
                return;
            }

            if (serverOfflineTime == -1) {
                // Server just went offline
                serverOfflineTime = System.currentTimeMillis();
                long delaySeconds = Config.SHUTDOWN_DETECTION_DELAY_MS / 1000;
                logger.info("Server went offline. Will destroy instance if it stays offline for {} seconds...", 
                    delaySeconds);
            } else {
                // Check if delay has passed
                long offlineDuration = System.currentTimeMillis() - serverOfflineTime;
                
                if (offlineDuration >= Config.SHUTDOWN_DETECTION_DELAY_MS) {
                    long delaySeconds = Config.SHUTDOWN_DETECTION_DELAY_MS / 1000;
                    logger.info("Server has been offline for {} seconds. Destroying Vultr instance...", 
                        delaySeconds);
                    
                    // Reset timers to prevent multiple destroy attempts
                    resetTimers();
                    
                    serverManager.destroyInstance().thenAccept(success -> {
                        if (success) {
                            logger.info("Instance successfully destroyed after server shutdown");
                        } else {
                            logger.error("Failed to destroy instance after server shutdown");
                        }
                    });
                } else {
                    long remainingSeconds = (Config.SHUTDOWN_DETECTION_DELAY_MS - offlineDuration) / 1000;
                    logger.debug("Server offline for {} seconds. {} seconds remaining before destruction.", 
                        offlineDuration / 1000, remainingSeconds);
                }
            }
        });
    }

    /**
     * Check if server is empty and destroy if timeout reached
     */
    private void checkServerEmptiness(RegisteredServer server) {
        int playerCount = server.getPlayersConnected().size();
        
        if (playerCount == 0) {
            if (lastEmptyTime == -1) {
                // Server just became empty
                lastEmptyTime = System.currentTimeMillis();
                long timeoutMinutes = Config.EMPTY_TIMEOUT_MS / 60000;
                logger.info("Server is now empty. Starting {}-minute countdown...", timeoutMinutes);
            } else {
                // Check if timeout has passed
                long emptyDuration = System.currentTimeMillis() - lastEmptyTime;
                
                if (emptyDuration >= Config.EMPTY_TIMEOUT_MS) {
                    long timeoutMinutes = Config.EMPTY_TIMEOUT_MS / 60000;
                    logger.info("Server has been empty for {} minutes. Destroying instance...", timeoutMinutes);
                    
                    // Reset timers immediately to prevent multiple destroy attempts
                    resetTimers();
                    
                    serverManager.destroyInstance().thenAccept(success -> {
                        if (success) {
                            logger.info("Instance successfully destroyed due to inactivity");
                        } else {
                            logger.error("Failed to destroy instance");
                        }
                    });
                } else {
                    long remainingMinutes = (Config.EMPTY_TIMEOUT_MS - emptyDuration) / 60000;
                    logger.debug("Server empty for {} minutes. {} minutes remaining.", 
                        emptyDuration / 60000, remainingMinutes);
                }
            }
        } else {
            // Server has players, reset empty timer
            if (lastEmptyTime != -1) {
                logger.info("Server is no longer empty. Resetting countdown.");
                lastEmptyTime = -1;
            }
        }
    }

    /**
     * Reset all timers
     */
    private void resetTimers() {
        lastEmptyTime = -1;
        serverOfflineTime = -1;
    }

    /**
     * Shutdown the monitor
     */
    public void shutdown() {
        logger.info("Shutting down server monitor...");
        monitoring = false;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
