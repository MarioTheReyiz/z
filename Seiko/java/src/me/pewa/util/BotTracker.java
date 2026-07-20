package me.pewa.util;

import me.pewa.mapping.MinecraftMapper;
import me.pewa.util.MappingUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BotTracker — per-entity-object bot detection using hurtTime.
 *
 * Key insight: the visible bot shares the same UUID as the real player it mimics.
 * So UUID-based tracking is unreliable. We track by object identity (WeakHashMap)
 * so each entity object is tracked independently.
 *
 * Logic:
 *  - Observe each entity object every tick, track its hurtTime.
 *  - If hurtTime ever goes > 0 → real player (server sent damage packet).
 *  - If observed for 60+ ticks and hurtTime never changed → bot.
 *
 * UUID version-3 check is kept as a fast-path for invisible bots
 * that don't share a UUID with a real player.
 */
public final class BotTracker {

    // Per-entity-object tracking (WeakHashMap so GC can collect dead entities)
    private static final WeakHashMap<Object, EntityState> entityStates =
            new WeakHashMap<Object, EntityState>();

    // Sync lock for the WeakHashMap
    private static final Object LOCK = new Object();

    private static long lastCleanup = 0;

    private BotTracker() {}

    private static final class EntityState {
        int lastHurtTime = 0;
        int hurtTimeChanges = 0;
        int observationCount = 0;
        long firstSeenTime = System.currentTimeMillis();
        boolean confirmedReal = false;
        boolean confirmedBot = false;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** For logging only */
    public static String getUUIDForLog(Object entity) {
        String uuid = getEntityUUID(entity);
        if (uuid == null) return "?";
        if (uuid.startsWith("id_")) return uuid;
        return uuid.length() >= 8 ? uuid.substring(0, 8) : uuid;
    }

    /** Observe all entities in the world. Call every tick. */
    public static void observeAll() {
        try {
            for (Object entity : MinecraftMapper.getPlayerEntitiesInWorld()) {
                observe(entity);
            }
        } catch (Throwable ignored) {}
    }

    /** Observe a single entity — track hurtTime changes. */
    public static void observe(Object entity) {
        if (entity == null) return;
        synchronized (LOCK) {
            EntityState state = entityStates.get(entity);
            if (state == null) {
                state = new EntityState();
                entityStates.put(entity, state);
            }

            state.observationCount++;

            int ht = getHurtTime(entity);
            if (ht != state.lastHurtTime) {
                if (ht > 0 && !state.confirmedReal) {
                    // hurtTime went up → entity received damage → real player
                    state.hurtTimeChanges++;
                    state.confirmedReal = true;
                    state.confirmedBot = false;
                    Logger.info("[BotTracker] REAL (hurtTime=" + ht + ") uuid=" + getUUIDForLog(entity));
                }
                state.lastHurtTime = ht;
            }
        }
    }

    /**
     * Returns true if the entity is a bot.
     */
    public static boolean isBot(Object entity) {
        if (entity == null) return false;

        synchronized (LOCK) {
            EntityState state = entityStates.get(entity);

            // Not yet observed — give it a chance
            if (state == null) return false;

            // Confirmed real → never a bot
            if (state.confirmedReal) return false;

            // Already confirmed bot
            if (state.confirmedBot) return true;

            // Fast path: UUID version 3 = offline/fake UUID → bot
            // Only use this for entities that don't share UUID with a real player
            String uuid = getEntityUUID(entity);
            if (uuid != null && !uuid.startsWith("id_")) {
                try {
                    int version = UUID.fromString(uuid).version();
                    if (version == 3) {
                        state.confirmedBot = true;
                        Logger.info("[BotTracker] BOT (uuid v3) uuid=" + uuid.substring(0, 8));
                        return true;
                    }
                } catch (IllegalArgumentException ignored) {}
            }

            // hurtTime-based: observed 60+ ticks, hurtTime never changed → bot
            if (state.observationCount >= 60 && state.hurtTimeChanges == 0) {
                state.confirmedBot = true;
                Logger.info("[BotTracker] BOT (no hurtTime in " + state.observationCount + " ticks) uuid=" + getUUIDForLog(entity));
                return true;
            }
        }

        return false;
    }

    /** Clear all data (call on disconnect/world change). */
    public static void reset() {
        synchronized (LOCK) {
            entityStates.clear();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String getEntityUUID(Object entity) {
        if (entity == null) return null;
        try {
            try {
                Method m = entity.getClass().getMethod("getUniqueID");
                Object r = m.invoke(entity);
                if (r instanceof UUID) return r.toString();
            } catch (NoSuchMethodException ignored) {}

            try {
                Method gp = entity.getClass().getMethod("getGameProfile");
                Object profile = gp.invoke(entity);
                if (profile != null) {
                    Method getUUID = MappingUtils.getMethod("GameProfile.getUUID");
                    if (getUUID != null) {
                        getUUID.setAccessible(true);
                        Object r = getUUID.invoke(profile);
                        if (r instanceof UUID) return r.toString();
                    }
                    try {
                        Method getId = profile.getClass().getMethod("getId");
                        Object r = getId.invoke(profile);
                        if (r instanceof UUID) return r.toString();
                    } catch (Exception ignored) {}
                    Field uuidField = MappingUtils.getField("GameProfile.uuid");
                    if (uuidField != null) {
                        uuidField.setAccessible(true);
                        Object r = uuidField.get(profile);
                        if (r instanceof UUID) return r.toString();
                    }
                }
            } catch (Exception ignored) {}

            Field gpField = MappingUtils.getField("EntityPlayer.gameProfile");
            if (gpField != null) {
                gpField.setAccessible(true);
                Object profile = gpField.get(entity);
                if (profile != null) {
                    Field uuidField = MappingUtils.getField("GameProfile.uuid");
                    if (uuidField != null) {
                        uuidField.setAccessible(true);
                        Object r = uuidField.get(profile);
                        if (r instanceof UUID) return r.toString();
                    }
                }
            }

            Class<?> clazz = entity.getClass();
            while (clazz != null && !clazz.equals(Object.class)) {
                for (Field f : clazz.getDeclaredFields()) {
                    if (f.getType().equals(UUID.class)) {
                        f.setAccessible(true);
                        Object r = f.get(entity);
                        if (r instanceof UUID) return r.toString();
                    }
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Throwable ignored) {}

        return "id_" + System.identityHashCode(entity);
    }

    private static int getHurtTime(Object entity) {
        try {
            Field f = MappingUtils.getField("EntityLivingBase.hurtTime");
            if (f != null) {
                f.setAccessible(true);
                return f.getInt(entity);
            }
        } catch (Throwable ignored) {}

        try {
            Class<?> clazz = entity.getClass();
            while (clazz != null && !clazz.equals(Object.class)) {
                for (Field f : clazz.getDeclaredFields()) {
                    if (f.getType() == int.class && f.getName().equals("hurtTime")) {
                        f.setAccessible(true);
                        return f.getInt(entity);
                    }
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Throwable ignored) {}

        return 0;
    }
}
