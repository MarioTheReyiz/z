package me.pewa.module.impl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import me.pewa.mapping.MinecraftMapper;
import me.pewa.module.Category;
import me.pewa.module.Module;
import me.pewa.setting.BooleanOption;
import me.pewa.setting.NumberOption;
import me.pewa.util.Logger;
import me.pewa.util.MappingUtils;
import org.lwjgl.input.Keyboard;

/**
 * AimBotModule — rotates the player's view toward the nearest valid player.
 *
 * Visually turns the player's head (Body mode only — no silent packet tricks).
 * Speed controls how fast the aim snaps to the target (0.05 = very slow, 1.0 = instant).
 */
public class AimBotModule extends Module {

    private final NumberOption range;
    private final NumberOption speed;
    private final BooleanOption onlyOnClick;

    private float lastYaw   = 0.0f;
    private float lastPitch = 0.0f;
    private boolean initialized = false;

    public AimBotModule() {
        super("AimBot", "Aims at the nearest player", Category.COMBAT, Keyboard.KEY_NONE);

        range       = new NumberOption("Range",   7.0D, 1.0D, 10.0D, 0.5D, this);
        speed       = new NumberOption("Speed",   0.15D, 0.05D, 1.0D, 0.05D, this);
        onlyOnClick = new BooleanOption("OnClick", false, this);

        range.setGroup("Combat");
        speed.setGroup("Combat");
        onlyOnClick.setGroup("Combat");

        addOptions(range, speed, onlyOnClick);
    }

    @Override
    public void onEnable() {
        initialized = false;
        Logger.info("AimBot enabled");
    }

    @Override
    public void onDisable() {
        initialized = false;
        Logger.info("AimBot disabled");
    }

    @Override
    public void onUpdate() {
        if (!isEnabled()) return;

        // OnClick: only aim while left mouse button is held
        if (onlyOnClick.getValue()) {
            try {
                if (!org.lwjgl.input.Mouse.isButtonDown(0)) return;
            } catch (Throwable ignored) {}
        }

        Object player = getThePlayer();
        if (player == null || getTheWorld() == null || getCurrentScreen() != null) return;

        Object target = findNearestTarget(range.getValue());
        if (target == null) return;

        float[] rotations = getRotationsTo(target);
        if (rotations == null) return;

        float targetYaw   = rotations[0];
        float targetPitch = clamp(rotations[1], -90.0f, 90.0f);

        if (!initialized) {
            lastYaw   = getPlayerYaw(player);
            lastPitch = getPlayerPitch(player);
            initialized = true;
        }

        float spd = speed.getValue().floatValue();
        float smoothYaw   = smoothAngle(lastYaw,   targetYaw,   spd);
        float smoothPitch = smoothAngle(lastPitch, targetPitch, spd);

        lastYaw   = smoothYaw;
        lastPitch = smoothPitch;

        setBodyRotation(player, smoothYaw, smoothPitch);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Object findNearestTarget(double maxRange) {
        Object nearest = null;
        double nearestDist = maxRange + 1.0;
        for (Object entity : MinecraftMapper.getPlayerEntitiesInWorld()) {
            if (!isValidTarget(entity)) continue;
            double dist = distanceTo(entity);
            if (dist <= maxRange && dist < nearestDist) {
                nearestDist = dist;
                nearest = entity;
            }
        }
        return nearest;
    }

    private boolean isValidTarget(Object entity) {
        if (entity == null) return false;
        try {
            Object player = getThePlayer();
            if (entity == player) return false;
            // AntiBot check
            if (me.pewa.util.BotTracker.isBot(entity)) return false;
            Method getHealth = MappingUtils.getMethod("EntityLivingBase.getHealth");
            if (getHealth != null) {
                getHealth.setAccessible(true);
                float hp = ((Number) getHealth.invoke(entity)).floatValue();
                if (hp <= 0) return false;
            }
        } catch (Throwable ignored) {}
        return true;
    }

    private float[] getRotationsTo(Object target) {
        Object player = getThePlayer();
        if (player == null || target == null) return null;

        double px = getPosField(player, "Entity.posX");
        double py = getPosField(player, "Entity.posY") + getEyeHeight(player);
        double pz = getPosField(player, "Entity.posZ");

        double tx = getPosField(target, "Entity.posX");
        double ty = getPosField(target, "Entity.posY") + getEyeHeight(target);
        double tz = getPosField(target, "Entity.posZ");

        double dx = tx - px;
        double dy = ty - py;
        double dz = tz - pz;
        double dist = Math.sqrt(dx * dx + dz * dz);

        float yaw   = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, dist));
        return new float[]{ yaw, pitch };
    }

    private float smoothAngle(float current, float target, float spd) {
        float diff = target - current;
        while (diff >  180.0f) diff -= 360.0f;
        while (diff < -180.0f) diff += 360.0f;
        return current + diff * spd;
    }

    private float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private float getPlayerYaw(Object player) {
        try {
            Field f = MappingUtils.getField("Entity.rotationYaw");
            if (f != null) { f.setAccessible(true); return f.getFloat(player); }
        } catch (Throwable ignored) {}
        return 0.0f;
    }

    private float getPlayerPitch(Object player) {
        try {
            Field f = MappingUtils.getField("Entity.rotationPitch");
            if (f != null) { f.setAccessible(true); return f.getFloat(player); }
        } catch (Throwable ignored) {}
        return 0.0f;
    }

    private void setBodyRotation(Object player, float yaw, float pitch) {
        try {
            Field yf = MappingUtils.getField("Entity.rotationYaw");
            Field pf = MappingUtils.getField("Entity.rotationPitch");
            if (yf != null) { yf.setAccessible(true); yf.setFloat(player, yaw); }
            if (pf != null) { pf.setAccessible(true); pf.setFloat(player, pitch); }
        } catch (Throwable ignored) {}
    }

    private double distanceTo(Object entity) {
        Object player = getThePlayer();
        if (player == null || entity == null) return Double.MAX_VALUE;
        try {
            Method m = MappingUtils.getMethod("Entity.getDistanceToEntity");
            if (m != null) {
                m.setAccessible(true);
                return ((Number) m.invoke(player, entity)).doubleValue();
            }
        } catch (Throwable ignored) {}
        double dx = getPosField(player, "Entity.posX") - getPosField(entity, "Entity.posX");
        double dy = getPosField(player, "Entity.posY") - getPosField(entity, "Entity.posY");
        double dz = getPosField(player, "Entity.posZ") - getPosField(entity, "Entity.posZ");
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double getPosField(Object e, String mapping) {
        try {
            Field f = MappingUtils.getField(mapping);
            if (f != null) { f.setAccessible(true); return f.getDouble(e); }
        } catch (Throwable ignored) {}
        return 0.0;
    }

    private double getEyeHeight(Object entity) {
        try {
            Method m = MappingUtils.getMethod("Entity.getEyeHeight");
            if (m != null) { m.setAccessible(true); return ((Number) m.invoke(entity)).doubleValue(); }
        } catch (Throwable ignored) {}
        return 1.62;
    }

    private Object getCurrentScreen() {
        try {
            Object mc = getMinecraft();
            Field f = MappingUtils.getField("Minecraft.currentScreen");
            if (mc != null && f != null) { f.setAccessible(true); return f.get(mc); }
        } catch (Throwable ignored) {}
        return null;
    }
}
