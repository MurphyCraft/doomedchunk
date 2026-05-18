package com.doomedchunks;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;

import java.util.Set;

/**
 * Holds a set of ChunkPos entries that are scheduled to be destroyed,
 * along with the server tick timestamp at which destruction should fire.
 */
public class ScheduledChunkDeletion {

    /** The chunks to be destroyed. */
    public final Set<ChunkPos> chunks;

    /** The absolute server tick at which destruction fires. */
    public final long destroyAtTick;

    public ScheduledChunkDeletion(Set<ChunkPos> chunks, long destroyAtTick) {
        this.chunks = chunks;
        this.destroyAtTick = destroyAtTick;
    }
}
