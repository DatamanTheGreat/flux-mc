package net.datamanix.fluxMc;

import com.google.inject.Inject;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;

@Plugin(id = "flux-mc", name = "flux-mc", version = BuildConstants.VERSION)
public class FluxMc {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private ServerManager serverManager;
    private ServerMonitor serverMonitor;
    private PlayerConnectionHandler connectionHandler;

    @Inject
    public FluxMc(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        logger.info("Initializing FluxMc plugin...");

        // Load configuration
        if (!Config.load(dataDirectory, logger)) {
            logger.error("Failed to load configuration! Plugin will not be enabled.");
            logger.error("Please check the config.toml file in: {}", dataDirectory);
            return;
        }

        // Initialize components
        serverManager = new ServerManager(logger);
        serverMonitor = new ServerMonitor(proxy, serverManager, logger);
        connectionHandler = new PlayerConnectionHandler(proxy, serverManager, logger, this);

        // Register event listeners
        proxy.getEventManager().register(this, connectionHandler);

        // Start monitoring
        serverMonitor.startMonitoring();

        logger.info("FluxMc plugin initialized successfully!");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        logger.info("Shutting down FluxMc plugin...");

        if (serverMonitor != null) {
            serverMonitor.shutdown();
        }

        // Destroy Vultr instance on proxy shutdown
        if (serverManager != null) {
            logger.info("Velocity proxy shutting down - destroying Vultr instance...");
            try {
                boolean destroyed = serverManager.destroyInstance().get();
                if (destroyed) {
                    logger.info("Vultr instance destroyed successfully on proxy shutdown");
                } else {
                    logger.warn("Failed to destroy Vultr instance on proxy shutdown");
                }
            } catch (Exception e) {
                logger.error("Error destroying instance on proxy shutdown", e);
            }
        }

        logger.info("FluxMc plugin shut down successfully!");
    }

    public ProxyServer getProxy() {
        return proxy;
    }
}