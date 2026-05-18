package com.doomedchunks;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DoomedChunksMod implements ModInitializer {

    public static final String MOD_ID = "doomedchunks";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[DoomedChunks] Mod initialized. The earth will reclaim what is hers.");

        // Register the server tick event to drive our dusk detection and countdown logic
        ServerTickEvents.END_SERVER_TICK.register(new DuskChunkScheduler());
    }
}
