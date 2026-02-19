package net.datamanix.fluxMc;

import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Handles player connection logic, managing server startup and player routing.
 * This handler intercepts initial connections and manages server provisioning.
 */
public class PlayerConnectionHandler {
    private final ProxyServer proxy;
    private final ServerManager serverManager;
    private final Logger logger;
    private final Object plugin;

    // Track players currently waiting for server to start (prevents duplicate handling)
    private final Set<UUID> playersWaitingForServer = ConcurrentHashMap.newKeySet();

    // Cache for the kick message (no need to parse repeatedly)
    private final Component kickMessage;

    // Cache for target server (validated once at startup)
    private final RegisteredServer targetServer;

    public PlayerConnectionHandler(ProxyServer proxy, ServerManager serverManager, Logger logger, Object plugin) {
        this.proxy = proxy;
        this.serverManager = serverManager;
        this.logger = logger;
        this.plugin = plugin;

        // Pre-parse kick message
        this.kickMessage = LegacyComponentSerializer.legacyAmpersand()
                .deserialize(Config.KICK_MESSAGE);

        // Validate and cache target server
        Optional<RegisteredServer> serverOpt = proxy.getServer(Config.TARGET_SERVER_NAME);
        if (serverOpt.isEmpty()) {
            logger.error("Target server '{}' is not registered in Velocity! Plugin will not function correctly.",
                    Config.TARGET_SERVER_NAME);
            this.targetServer = null;
        } else {
            this.targetServer = serverOpt.get();
            logger.info("Target server '{}' validated successfully", Config.TARGET_SERVER_NAME);
        }
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        // Only intercept initial connections (not server switches)
        if (event.getPlayer().getCurrentServer().isPresent()) {
            return;
        }

        // Validate target server is configured
        if (targetServer == null) {
            logger.error("Cannot handle connection for {} - target server not configured",
                    event.getPlayer().getUsername());
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            disconnectPlayer(event.getPlayer(),
                    Component.text("Server configuration error. Please contact an administrator.",
                            NamedTextColor.RED));
            return;
        }

        handleInitialConnection(event);
    }

    @Subscribe
    public void onKickedFromServer(KickedFromServerEvent event) {
        // Only handle kicks from the target server
        if (!event.getServer().equals(targetServer)) {
            return;
        }

        // Only handle if player has no current server (initial connection failed)
        if (event.getPlayer().getCurrentServer().isPresent()) {
            return;
        }

        Player player = event.getPlayer();
        
        // Check if server is still starting or if player tried too early
        if (playersWaitingForServer.contains(player.getUniqueId())) {
            logger.info("Player {} connection failed while server starting - showing startup message",
                    player.getUsername());
            
            // Show custom startup message instead of connection error
            event.setResult(KickedFromServerEvent.DisconnectPlayer.create(kickMessage));
            playersWaitingForServer.remove(player.getUniqueId());
            return;
        }

        // Allow default behavior for other kick reasons
    }

    /**
     * Handles a player's initial connection attempt
     */
    private void handleInitialConnection(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        logger.info("Handling initial connection for player {}", player.getUsername());

        // Check if already being processed
        if (playersWaitingForServer.contains(playerId)) {
            logger.warn("Player {} is already being processed, denying duplicate connection",
                    player.getUsername());
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            disconnectPlayer(player, kickMessage);
            return;
        }

        // Check server status and handle accordingly
        serverManager.isServerRunning().thenAccept(isRunning -> {
            if (!player.isActive()) {
                logger.debug("Player {} disconnected before server check completed", player.getUsername());
                playersWaitingForServer.remove(playerId);
                return;
            }

            if (isRunning) {
                handleServerRunning(event, player);
            } else {
                handleServerNotRunning(event, player);
            }
        }).exceptionally(ex -> {
            logger.error("Error checking server status for player {}", player.getUsername(), ex);
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            disconnectPlayer(player,
                    Component.text("Error checking server status. Please try again.", NamedTextColor.RED));
            playersWaitingForServer.remove(playerId);
            return null;
        });
    }

    /**
     * Handles connection when server is already running
     */
    private void handleServerRunning(ServerPreConnectEvent event, Player player) {
        logger.info("Server is running, connecting player {} immediately", player.getUsername());
            
        // Allow the connection
        event.setResult(ServerPreConnectEvent.ServerResult.allowed(targetServer));
        
        // Track this player in case connection fails
        playersWaitingForServer.add(player.getUniqueId());
        
        // Schedule removal after a short delay (successful connections will happen quickly)
        proxy.getScheduler()
                .buildTask(plugin, () -> playersWaitingForServer.remove(player.getUniqueId()))
                .delay(5, TimeUnit.SECONDS)
                .schedule();
    }

    /**
     * Handles connection when server needs to be started
     */
    private void handleServerNotRunning(ServerPreConnectEvent event, Player player) {
        logger.info("Server is not running for player {}", player.getUsername());

        // Deny the initial connection in both modes
        event.setResult(ServerPreConnectEvent.ServerResult.denied());

        // Mark player as being processed
        playersWaitingForServer.add(player.getUniqueId());

        // Handle based on configured mode
        if (Config.KICK_ON_STARTUP) {
            handleKickMode(player);
        } else {
            handleWaitMode(player);
        }
    }

    /**
     * Kick mode: Start server and disconnect player with message
     */
    private void handleKickMode(Player player) {
        logger.info("Starting server in kick mode for player {}", player.getUsername());

        startServerAndCleanup(player)
                .thenAccept(success -> {
                    if (player.isActive()) {
                        if (success) {
                            logger.info("Server started successfully, kicking player {} with message",
                                    player.getUsername());
                            player.disconnect(kickMessage);
                        } else {
                            logger.error("Failed to start server for player {}", player.getUsername());
                            disconnectPlayer(player,
                                    Component.text("Failed to start server. Please contact an administrator.",
                                            NamedTextColor.RED));
                        }
                    }
                    playersWaitingForServer.remove(player.getUniqueId());
                });
    }

    /**
     * Wait mode: Keep player connected while server starts
     */
    private void handleWaitMode(Player player) {
        logger.info("Starting server in wait mode for player {}", player.getUsername());

        player.sendMessage(Component.text("Starting server, please wait...", NamedTextColor.YELLOW));

        startServerAndCleanup(player)
                .thenCompose(instanceCreated -> {
                    if (!player.isActive()) {
                        return CompletableFuture.completedFuture(false);
                    }

                    if (!instanceCreated) {
                        player.sendMessage(Component.text(
                                "Failed to start server. Please contact an administrator.",
                                NamedTextColor.RED));
                        return CompletableFuture.completedFuture(false);
                    }

                    player.sendMessage(Component.text("Server starting, please wait...", NamedTextColor.GREEN));
                    return waitForServerAndConnect(player);
                })
                .thenAccept(success -> {
                    playersWaitingForServer.remove(player.getUniqueId());
                })
                .exceptionally(ex -> {
                    logger.error("Error in wait mode for player {}", player.getUsername(), ex);
                    if (player.isActive()) {
                        player.sendMessage(Component.text(
                                "An error occurred while starting the server. Please try again.",
                                NamedTextColor.RED));
                    }
                    playersWaitingForServer.remove(player.getUniqueId());
                    return null;
                });
    }

    /**
     * Starts the server and returns a future indicating success
     */
    private CompletableFuture<Boolean> startServerAndCleanup(Player player) {
        return serverManager.createInstance()
                .exceptionally(ex -> {
                    logger.error("Exception creating instance for player {}", player.getUsername(), ex);
                    return false;
                });
    }

    /**
     * Waits for server to be ready and connects the player
     */
    private CompletableFuture<Boolean> waitForServerAndConnect(Player player) {
        return serverManager.waitForServerReady()
                .thenCompose(ready -> {
                    if (!player.isActive()) {
                        logger.debug("Player {} disconnected while waiting for server", player.getUsername());
                        return CompletableFuture.completedFuture(false);
                    }

                    if (!ready) {
                        player.sendMessage(Component.text(
                                "Server failed to start. Please try again later.",
                                NamedTextColor.RED));
                        logger.error("Server failed to become ready for player {}", player.getUsername());
                        return CompletableFuture.completedFuture(false);
                    }

                    player.sendMessage(Component.text("Server is ready! Connecting...", NamedTextColor.GREEN));

                    // Give server a brief moment to fully initialize before connecting
                    CompletableFuture<Boolean> connectionFuture = new CompletableFuture<>();
                    proxy.getScheduler()
                            .buildTask(plugin, () -> {
                                if (player.isActive()) {
                                    connectPlayerToServer(player).thenAccept(connectionFuture::complete);
                                } else {
                                    logger.debug("Player {} disconnected before connection", player.getUsername());
                                    connectionFuture.complete(false);
                                }
                            })
                            .delay(2, TimeUnit.SECONDS)
                            .schedule();

                    return connectionFuture;
                })
                .exceptionally(ex -> {
                    logger.error("Error waiting for server ready for player {}", player.getUsername(), ex);
                    if (player.isActive()) {
                        player.sendMessage(Component.text(
                                "An error occurred while waiting for the server. Please try again.",
                                NamedTextColor.RED));
                    }
                    return false;
                });
    }

    /**
     * Connects a player to the target server
     */
    private CompletableFuture<Boolean> connectPlayerToServer(Player player) {
        if (!player.isActive()) {
            logger.debug("Player {} is no longer active, skipping connection", player.getUsername());
            return CompletableFuture.completedFuture(false);
        }

        logger.info("Connecting player {} to server {}", player.getUsername(), Config.TARGET_SERVER_NAME);

        return player.createConnectionRequest(targetServer).connect()
                .thenApply(result -> {
                    if (result.isSuccessful()) {
                        logger.info("Successfully connected player {} to server", player.getUsername());
                        return true;
                    } else {
                        Component reason = result.getReasonComponent().orElse(Component.text("Unknown reason"));
                        logger.warn("Failed to connect player {} to server: {}",
                                player.getUsername(), reason);

                        // Disconnect with custom message
                        if (player.isActive()) {
                            logger.info("Disconnecting {} with startup message due to connection failure",
                                    player.getUsername());
                            player.disconnect(kickMessage);
                        }
                        return false;
                    }
                })
                .exceptionally(ex -> {
                    logger.error("Exception connecting player {} to server", player.getUsername(), ex);
                    if (player.isActive()) {
                        // Disconnect with custom message
                        logger.info("Disconnecting {} with startup message due to exception",
                                player.getUsername());
                        player.disconnect(kickMessage);
                    }
                    return false;
                });
    }

    /**
     * Safely disconnects a player with a message
     */
    private void disconnectPlayer(Player player, Component message) {
        if (player.isActive()) {
            player.disconnect(message);
        }
    }
}