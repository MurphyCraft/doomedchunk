package com.doomedchunks;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.*;

/**
 * Registered as a ServerTickEvents.END_SERVER_TICK listener.
 *
 * Lifecycle each Minecraft day:
 *  1. Detects dusk (time >= 13000 in the overworld).
 *  2. Records the exact chunk each online player occupies.
 *  3. Schedules destruction 5 minutes (6000 ticks) later.
 *  4. Sends global broadcast warnings at 5min / 3min / 1min / 30sec / 10sec countdowns.
 *  5. Sends personal warnings to players who are still standing in a doomed chunk.
 *  6. At t=0, fills every doomed chunk with air (Y = minBuildHeight to maxBuildHeight-1).
 *     Players inside are NOT teleported — they face the consequences.
 */
public class DuskChunkScheduler implements ServerTickEvents.EndTick {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    /** Minecraft day length in ticks. */
    private static final long DAY_TICKS = 24000L;

    /** In-game time at which dusk is detected (evening transition). */
    private static final long DUSK_TIME = 13000L;

    /** Delay in ticks between dusk detection and chunk destruction (5 minutes). */
    private static final long DELAY_TICKS = 6000L; // 5 min × 60 sec × 20 ticks

    // Countdown thresholds (ticks remaining before destruction)
    private static final long WARN_5MIN  = 6000L;
    private static final long WARN_3MIN  = 3600L;
    private static final long WARN_1MIN  = 1200L;
    private static final long WARN_30SEC =  600L;
    private static final long WARN_10SEC =  200L;
    private static final long WARN_5SEC  =  100L;

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    /** Whether dusk has already been triggered this in-game day. */
    private boolean duskTriggered = false;

    /** Last recorded in-game day index, used to reset duskTriggered each new day. */
    private long lastDay = -1L;

    /** Pending deletions across all server ticks. */
    private final List<ScheduledChunkDeletion> pendingDeletions = new ArrayList<>();

    /** Track which warning thresholds have already been sent per deletion, keyed by destroyAtTick. */
    private final Map<Long, Set<Long>> sentWarnings = new HashMap<>();

    /**
     * Persistent on-screen indicator (red boss bar, top-middle of the screen)
     * shown to any player currently standing inside a doomed chunk.
     */
    private final ServerBossEvent doomedChunkBar = new ServerBossEvent(
            UUID.randomUUID(),
            Component.literal("You Are In A Doomed Chunk").withStyle(ChatFormatting.RED),
            BossEvent.BossBarColor.RED,
            BossEvent.BossBarOverlay.PROGRESS);

    // -----------------------------------------------------------------------
    // Tick handler
    // -----------------------------------------------------------------------

    @Override
    public void onEndTick(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) return;

        long currentTick = overworld.getGameTime();
        long timeOfDay   = overworld.getOverworldClockTime() % DAY_TICKS;
        long currentDay  = overworld.getOverworldClockTime() / DAY_TICKS;

        // Reset dusk trigger each new in-game day
        if (currentDay != lastDay) {
            lastDay = currentDay;
            duskTriggered = false;
        }

        // ── 1. Dusk detection ──────────────────────────────────────────────
        if (!duskTriggered && timeOfDay >= DUSK_TIME) {
            duskTriggered = true;
            onDusk(server, overworld, currentTick);
        }

        // ── 2. Process pending deletions ───────────────────────────────────
        Iterator<ScheduledChunkDeletion> iter = pendingDeletions.iterator();
        while (iter.hasNext()) {
            ScheduledChunkDeletion deletion = iter.next();
            long ticksRemaining = deletion.destroyAtTick - currentTick;

            sendCountdownWarnings(server, deletion, ticksRemaining);

            if (ticksRemaining <= 0) {
                executeDestruction(server, overworld, deletion);
                sentWarnings.remove(deletion.destroyAtTick);
                iter.remove();
            }
        }

        // ── 3. Update the "you are in a doomed chunk" indicator ────────────
        updateDoomedChunkIndicator(server);
    }

    /**
     * Keeps a red boss bar pinned to the top-middle of the screen for any
     * online player currently standing inside a chunk scheduled for
     * destruction. The bar disappears the moment they walk out, or when the
     * chunk is destroyed and its deletion drops out of {@link #pendingDeletions}.
     *
     * {@code addPlayer}/{@code removePlayer} are idempotent in vanilla (guarded
     * by an internal Set), so invoking them every tick does not spam packets.
     */
    private void updateDoomedChunkIndicator(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ChunkPos playerChunk = player.chunkPosition();

            boolean inDoomedChunk = false;
            for (ScheduledChunkDeletion deletion : pendingDeletions) {
                if (deletion.chunks.contains(playerChunk)) {
                    inDoomedChunk = true;
                    break;
                }
            }

            if (inDoomedChunk) {
                doomedChunkBar.addPlayer(player);
            } else {
                doomedChunkBar.removePlayer(player);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Dusk handler
    // -----------------------------------------------------------------------

    private void onDusk(MinecraftServer server, ServerLevel overworld, long currentTick) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) return;

        Set<ChunkPos> doomedChunks = new LinkedHashSet<>();
        for (ServerPlayer player : players) {
            doomedChunks.add(player.chunkPosition());
        }

        long destroyAt = currentTick + DELAY_TICKS;
        ScheduledChunkDeletion deletion = new ScheduledChunkDeletion(doomedChunks, destroyAt);
        pendingDeletions.add(deletion);
        sentWarnings.put(destroyAt, new HashSet<>());

        DoomedChunksMod.LOGGER.info("[DoomedChunks] Dusk detected. {} chunk(s) doomed. Destruction at tick {}.",
                doomedChunks.size(), destroyAt);

        // Initial broadcast
        broadcastAll(server,
            "§c☠ [DOOMED CHUNKS] §eThe earth will reclaim §f" + doomedChunks.size() +
            " chunk(s)§e in §f5 minutes§e. You have been warned.");

        // Personal warning to players in doomed chunks
        sendPersonalWarnings(server, deletion, WARN_5MIN);
    }

    // -----------------------------------------------------------------------
    // Warning system
    // -----------------------------------------------------------------------

    private void sendCountdownWarnings(MinecraftServer server, ScheduledChunkDeletion deletion, long ticksRemaining) {
        Set<Long> sent = sentWarnings.getOrDefault(deletion.destroyAtTick, Collections.emptySet());

        checkAndSendWarning(server, deletion, ticksRemaining, WARN_3MIN, sent,
            "§c☠ [DOOMED CHUNKS] §eDoomed chunks will be destroyed in §f3 minutes§e!",
            "§4🔴 You are STILL in a doomed chunk! §c3 minutes left — get out!");

        checkAndSendWarning(server, deletion, ticksRemaining, WARN_1MIN, sent,
            "§c☠ [DOOMED CHUNKS] §e1 minute remaining before chunk destruction!",
            "§4🔴 FINAL WARNING: §cLeave your chunk! §f1 minute left!");

        checkAndSendWarning(server, deletion, ticksRemaining, WARN_30SEC, sent,
            "§c☠ [DOOMED CHUNKS] §e30 seconds until the earth crumbles!",
            "§4🔴 §cYour chunk will be destroyed in §f30 seconds§c!");

        checkAndSendWarning(server, deletion, ticksRemaining, WARN_10SEC, sent,
            "§c☠ [DOOMED CHUNKS] §e10 seconds...",
            "§4☠ §cYou have §f10 seconds§c. You have chosen your fate...");

        checkAndSendWarning(server, deletion, ticksRemaining, WARN_5SEC, sent,
            "§c☠ [DOOMED CHUNKS] §e5... 4... 3... 2... 1...",
            "§4☠ §c5... 4... 3... 2... 1...");
    }

    private void checkAndSendWarning(MinecraftServer server, ScheduledChunkDeletion deletion,
                                     long ticksRemaining, long threshold,
                                     Set<Long> alreadySent,
                                     String globalMsg, String personalMsg) {
        if (ticksRemaining <= threshold && !alreadySent.contains(threshold)) {
            alreadySent.add(threshold);
            sentWarnings.put(deletion.destroyAtTick, alreadySent);
            broadcastAll(server, globalMsg);
            sendPersonalWarnings(server, deletion, threshold);
        }
    }

    /**
     * Sends a personal warning to every player who is currently standing
     * inside any of the doomed chunks.
     */
    private void sendPersonalWarnings(MinecraftServer server, ScheduledChunkDeletion deletion, long threshold) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ChunkPos playerChunk = player.chunkPosition();
            if (deletion.chunks.contains(playerChunk)) {
                String msg = getPersonalMessage(threshold);
                player.sendSystemMessage(Component.literal(msg));
            }
        }
    }

    private String getPersonalMessage(long threshold) {
        if (threshold == WARN_5MIN)  return "§4🔴 [WARNING] §cYou are standing in a §lDOOMED CHUNK§c! Move now or face destruction!";
        if (threshold == WARN_3MIN)  return "§4🔴 §cYou are STILL in a doomed chunk! §f3 minutes §cleft — get out now!";
        if (threshold == WARN_1MIN)  return "§4🔴 FINAL WARNING: §cLeave your chunk §lIMMEDIATELY§c! §f1 minute left!";
        if (threshold == WARN_30SEC) return "§4☠ §cYour chunk will be destroyed in §f30 seconds§c!";
        if (threshold == WARN_10SEC) return "§4☠ §cYou have §f10 seconds§c. You have chosen your fate...";
        if (threshold == WARN_5SEC)  return "§4☠ §l5... 4... 3... 2... 1...";
        return "§4☠ §cDestruction is imminent!";
    }

    // -----------------------------------------------------------------------
    // Chunk destruction
    // -----------------------------------------------------------------------

    private void executeDestruction(MinecraftServer server, ServerLevel overworld,
                                    ScheduledChunkDeletion deletion) {
        broadcastAll(server, "§c☠ [DOOMED CHUNKS] §4The earth has spoken. Chunks are being destroyed!");

        int minY = overworld.getMinY();
        int maxY = overworld.getMaxY(); // inclusive (highest valid Y)

        for (ChunkPos chunkPos : deletion.chunks) {
            destroyChunk(overworld, chunkPos, minY, maxY);
            DoomedChunksMod.LOGGER.info("[DoomedChunks] Destroyed chunk at {}", chunkPos);
        }

        broadcastAll(server, "§8[DoomedChunks] §7" + deletion.chunks.size() + " chunk(s) have been consumed by the earth.");
    }

    /**
     * Fills every block column in the chunk with air from minY (inclusive)
     * to maxY (inclusive). Bedrock at the very bottom of the world remains
     * intact only if minY > the bedrock layer — in a standard world this means
     * ALL blocks including bedrock are removed, leaving players to fall into the void.
     *
     * If you want to keep bedrock, change the loop to start at (minY + 1).
     */
    private void destroyChunk(ServerLevel level, ChunkPos chunkPos, int minY, int maxY) {
        // Ensure chunk is loaded so we can modify it
        LevelChunk chunk = level.getChunk(chunkPos.x(), chunkPos.z());

        int startX = chunkPos.getMinBlockX(); // chunkPos.x << 4
        int startZ = chunkPos.getMinBlockZ(); // chunkPos.z << 4

        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        for (int x = startX; x < startX + 16; x++) {
            for (int z = startZ; z < startZ + 16; z++) {
                for (int y = minY; y <= maxY; y++) {
                    mutable.set(x, y, z);
                    // Only replace non-air blocks to skip unnecessary updates
                    if (!level.getBlockState(mutable).isAir()) {
                        level.setBlock(mutable, Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }

        // Force lighting and heightmap recalculation
        chunk.markUnsaved();
    }

    // -----------------------------------------------------------------------
    // Utility
    // -----------------------------------------------------------------------

    private void broadcastAll(MinecraftServer server, String message) {
        server.getPlayerList().broadcastSystemMessage(Component.literal(message), false);
    }
}
