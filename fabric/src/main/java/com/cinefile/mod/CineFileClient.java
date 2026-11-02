package com.cinefile.mod;

import com.cinefile.core.CineFileConfig;
import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CineFileClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("cinefile");
    private static CineFileConfig config = new CineFileConfig();

    public static CineFileConfig config() {
        return config;
    }

    public static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("cinefile.json");
    }

    @Override
    public void onInitializeClient() {
        config = CineFileConfig.load(configPath());
    }
}
