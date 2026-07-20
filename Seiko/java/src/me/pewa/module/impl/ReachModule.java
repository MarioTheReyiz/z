package me.pewa.module.impl;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import me.pewa.mapping.MinecraftMapper;
import me.pewa.module.Category;
import me.pewa.module.Module;
import me.pewa.setting.NumberOption;
import me.pewa.util.Logger;
import me.pewa.util.MappingUtils;
import org.lwjgl.input.Keyboard;

/**
 * ReachModule
 *
 * On every swing (C0A packet), finds the entity in the player's crosshair
 * within Value blocks and sends a raw C02 ATTACK packet for it.
 *
 * Checks:
 *   1. Entity within Value blocks
 *   2. Entity in crosshair (~45° cone)
 *   3. canEntityBeSeen (LOS) if mapping available
 *
 * Value: 3.10 – 6.00, step 0.05
 */
public class ReachModule extends Module {

    private final NumberOption value;

    private Class<?> c0aClass = null;

    @SuppressWarnings("rawtypes")
    private Enum cachedAttackEnum = null;

    private Method cachedEntityIdMethod = null;
    private Field  cachedEntityIdField  = null;
    private boolean entityIdChecked     = false;

    private Method cachedDistMethod   = null;
    private boolean distMethodChecked = false;

    private Method cachedCanSeeMethod  = null;
    private boolean canSeeChecked      = false;

    public ReachModule() {
        super("Reach", "Extends attack reach", Category.COMBAT, Keyboard.KEY_NONE);
        value = new NumberOption("Value", 3.5D, 3.1D, 6.0D, 0.05D, this);
        value.setGroup("Combat");
        addOptions(value);
    }

    @Override
    public void onEnable() {
        clearCache();
        Logger.info("Reach enabled: " + value.getValue());
    }

    @Override
    public void onDisable() {
        clearCache();
        Logger.info("Reach disabled");
    }

    private void clearCache() {
        cachedAttackEnum     = null;
        cachedEntityIdMethod = null;
        cachedEntityIdField  = null;
        entityIdChecked      = false;
        cachedDistMethod     = null;
        distMethodChecked    = false;
        cachedCanSeeMethod   = null;
        canSeeChecked        = false;
    }

    @Override
    public void onUpdate() { /* passive */ }

    // ── Packet hook ───────────────────────────────────────────────────────────

    public void onPacketSend(Object packet) {
        if (!isEnabled() || packet == null) return;
        if (c0aClass == null) c0aClass = MappingUtils.get("C0APacketAnimation");
        if (c0aClass == null || !c0aClass.isInstance(packet)) return;

        // Player swung — find entity in crosshair within reach range
        Object target = findCrosshairTarget(value.getValue());
        if (target == null) return;

        sendRawAttack(target);
    }

    // ── Crosshair target ──────────────────────────────────────────────────────

    private Object findCrosshairTarget(double maxRange) {
        Object player = getThePlayer();
        if (player == null) return null;

        double px = dbl(player, "Entity.posX");
        double py = dbl(player, "Entity.posY") + getEyeHeight(player);
        double pz = dbl(player, "Entity.posZ");

        float yaw   = getFloat(player, "Entity.rotationYaw");
        float pitch = getFloat(player, "Entity.rotationPitch");

        // Look direction vector
        double yawRad   = Math.toRadians(yaw + 90.0);
        double pitchRad = Math.toRadians(-pitch);
        double lx = Math.cos(pitchRad) * Math.cos(yawRad);
        double ly = Math.sin(pitchRad);
        double lz = Math.cos(pitchRad) * Math.sin(yawRad);

        Object best    = null;
        double bestDot = 0.7; // minimum dot product (~45° cone)

        try {
            for (Object entity : MinecraftMapper.getPlayerEntitiesInWorld()) {
                if (!isValidTarget(entity)) continue;

                double dist = fastDist(player, entity);
                if (dist > maxRange || dist < 0.1) continue;

                // Entity center
                double tx = dbl(entity, "Entity.posX");
                double ty = dbl(entity, "Entity.posY") + getEyeHeight(entity);
                double tz = dbl(entity, "Entity.posZ");

                double dx = tx - px, dy = ty - py, dz = tz - pz;
                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (len < 0.001) continue;

                double dot = (dx * lx + dy * ly + dz * lz) / len;
                if (dot <= bestDot) continue;

                // LOS check
                if (!canSee(player, entity)) continue;

                bestDot = dot;
                best    = entity;
            }
        } catch (Throwable ignored) {}

        return best;
    }

    private boolean isValidTarget(Object entity) {
        if (entity == null) return false;
        try {
            if (entity == getThePlayer()) return false;
            // AntiBot check
            if (me.pewa.util.BotTracker.isBot(entity)) return false;
            Method m = MappingUtils.getMethod("EntityLivingBase.getHealth");
            if (m != null) {
                m.setAccessible(true);
                return ((Number) m.invoke(entity)).floatValue() > 0;
            }
        } catch (Throwable ignored) {}
        return true;
    }

    private boolean canSee(Object player, Object entity) {
        if (!canSeeChecked) {
            canSeeChecked = true;
            cachedCanSeeMethod = MappingUtils.getMethod("EntityLivingBase.canEntityBeSeen");
            if (cachedCanSeeMethod != null) cachedCanSeeMethod.setAccessible(true);
        }
        if (cachedCanSeeMethod != null) {
            try {
                Object result = cachedCanSeeMethod.invoke(player, entity);
                if (result instanceof Boolean) return (Boolean) result;
            } catch (Throwable ignored) {}
        }
        return true; // fail-open
    }

    // ── Raw C02 attack ────────────────────────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void sendRawAttack(Object entity) {
        try {
            int id = getEntityId(entity);
            if (id < 0) return;

            Channel ch;
            try { ch = MinecraftMapper.getNetworkChannel(); } catch (Exception e) { return; }
            if (ch == null || !ch.isActive()) return;

            if (cachedAttackEnum == null) {
                Class<?> ac = MappingUtils.get("C02PacketUseEntityAction");
                if (ac == null) return;
                cachedAttackEnum = Enum.valueOf((Class<Enum>) ac, "ATTACK");
            }

            ByteBuf buf = Unpooled.buffer();
            buf.writeByte(2);
            writeVarInt(buf, id);
            buf.writeByte(cachedAttackEnum.ordinal());
            buf.writeFloat(1.65f);
            ch.writeAndFlush(buf);
        } catch (Throwable ignored) {}
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private double fastDist(Object player, Object entity) {
        if (!distMethodChecked) {
            distMethodChecked = true;
            cachedDistMethod = MappingUtils.getMethod("Entity.getDistanceToEntity");
            if (cachedDistMethod != null) cachedDistMethod.setAccessible(true);
        }
        if (cachedDistMethod != null) {
            try { return ((Number) cachedDistMethod.invoke(player, entity)).doubleValue(); }
            catch (Throwable ignored) {}
        }
        double dx = dbl(player, "Entity.posX") - dbl(entity, "Entity.posX");
        double dy = dbl(player, "Entity.posY") - dbl(entity, "Entity.posY");
        double dz = dbl(player, "Entity.posZ") - dbl(entity, "Entity.posZ");
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double dbl(Object e, String mapping) {
        try {
            Field f = MappingUtils.getField(mapping);
            if (f != null) { f.setAccessible(true); return f.getDouble(e); }
        } catch (Throwable ignored) {}
        return 0.0;
    }

    private float getFloat(Object e, String mapping) {
        try {
            Field f = MappingUtils.getField(mapping);
            if (f != null) { f.setAccessible(true); return f.getFloat(e); }
        } catch (Throwable ignored) {}
        return 0.0f;
    }

    private double getEyeHeight(Object entity) {
        try {
            Method m = MappingUtils.getMethod("Entity.getEyeHeight");
            if (m != null) { m.setAccessible(true); return ((Number) m.invoke(entity)).doubleValue(); }
        } catch (Throwable ignored) {}
        return 1.62;
    }

    private int getEntityId(Object entity) {
        if (!entityIdChecked) {
            entityIdChecked = true;
            cachedEntityIdMethod = MappingUtils.getMethod("Entity.getEntityId");
            if (cachedEntityIdMethod != null) cachedEntityIdMethod.setAccessible(true);
            else {
                cachedEntityIdField = MappingUtils.getField("Entity.entityId");
                if (cachedEntityIdField != null) cachedEntityIdField.setAccessible(true);
            }
        }
        try {
            if (cachedEntityIdMethod != null) return ((Number) cachedEntityIdMethod.invoke(entity)).intValue();
            if (cachedEntityIdField  != null) return cachedEntityIdField.getInt(entity);
        } catch (Throwable ignored) {}
        return -1;
    }

    private static void writeVarInt(ByteBuf buf, int value) {
        while (true) {
            if ((value & ~0x7F) == 0) { buf.writeByte(value); return; }
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }
}
