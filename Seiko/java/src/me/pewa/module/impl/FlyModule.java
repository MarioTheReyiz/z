package me.pewa.module.impl;

import me.pewa.mapping.MinecraftMapper;
import me.pewa.module.Category;
import me.pewa.module.Module;
import me.pewa.setting.BooleanOption;
import me.pewa.setting.NumberOption;
import me.pewa.setting.StringOption;
import me.pewa.util.MappingUtils;
import org.lwjgl.input.Keyboard;

import java.lang.reflect.Field;
import java.util.Random;

public class FlyModule extends Module {

    public FlyModule() {
        super("Fly", "Fly bypass module", Category.MOVEMENT, Keyboard.KEY_NONE);
        addOptions(
            new StringOption("Mode", "Glide", this, "Glide", "MotionPlusGlide", "Motion", "C03Cancel"),
            new NumberOption("Speed",          0.24, 0.1,  8.0,  0.01, this),
            new NumberOption("Vertical Speed", 0.22, 0.05, 5.0,  0.01, this),
            new BooleanOption("Disable Y",     false, this),
            new BooleanOption("Packet Hold",   true,  this).setDependency("Mode:MotionPlusGlide"),
            new NumberOption("Hold Duration",  8.0,  1.0,  20.0, 0.5,  this).setDependency("Mode:MotionPlusGlide"),
            new NumberOption("Teleport Speed", 0.3,  0.1,  2.0,  0.1,  this).setDependency("Mode:MotionPlusGlide")
        );
    }

    // ── Settings ──────────────────────────────────────────────────────────────
    private String  flyMode          () { return ((StringOption)  getOption("Mode"))           .getValue(); }
    private double  flySpeed         () { return ((NumberOption)  getOption("Speed"))          .getValue().doubleValue(); }
    private double  flyVerticalSpeed () { return ((NumberOption)  getOption("Vertical Speed")) .getValue().doubleValue(); }
    private boolean enablePacketHold () { return ((BooleanOption) getOption("Packet Hold"))    .getValue(); }
    private boolean disableY         () { return ((BooleanOption) getOption("Disable Y"))       .getValue(); }
    private double  holdDuration     () { return ((NumberOption)  getOption("Hold Duration"))  .getValue().doubleValue(); }
    private double  teleportSpeed    () { return ((NumberOption)  getOption("Teleport Speed")) .getValue().doubleValue(); }

    // ── Packet holding (MotionPlusGlide) ──────────────────────────────────────
    private long     packetHoldStartTime = 0;
    private double[] heldPosition        = null;
    private boolean  isHoldingPackets    = false;

    // ── Motion tracking ───────────────────────────────────────────────────────
    private double lastMotionX = 0.0;
    private double lastMotionY = 0.0;
    private double lastMotionZ = 0.0;

    // ── Fall distance simulation ──────────────────────────────────────────────
    private float simulatedFallDistance = 0f;

    // ── Speed smoothing ───────────────────────────────────────────────────────
    private double lastSpeed = 0.0;

    // ── General state ─────────────────────────────────────────────────────────
    private int    tickCounter    = 0;
    private long   flyStartTime   = 0;
    private int    flyTicks       = 0;
    private double lastSentY      = 0;
    private boolean flyInitialized = false;

    // ── C03Cancel state ───────────────────────────────────────────────────────
    private double  lastX1, lastY1, lastZ1;
    private boolean wasFlying = false;

    private final Random rand = new Random();

    // ── Packet field cache ────────────────────────────────────────────────────
    private Field xFieldCache = null;
    private Field yFieldCache = null;
    private Field zFieldCache = null;

    // ── Helpers ───────────────────────────────────────────────────────────────
    static float degreesToRadians(float degrees) {
        return degrees * (3.14159265358979323846f / 180.0f);
    }

    @Override
    public void onEnable() {
        flyInitialized      = false;
        tickCounter         = 0;
        flyTicks            = 0;
        isHoldingPackets    = false;
        heldPosition        = null;
        lastSpeed           = 0.0;
        simulatedFallDistance = 0f;
        wasFlying           = false;
    }

    @Override
    public void onDisable() {
        flyInitialized   = false;
        isHoldingPackets = false;
        heldPosition     = null;
        wasFlying        = false;
    }

    @Override
    public void onUpdate() {
        if (MinecraftMapper.getPlayer() == null) return;

        switch (flyMode()) {
            case "Glide":          flyGlide();          break;
            case "MotionPlusGlide": flyMotionPlusGlide(); break;
            case "Motion":         flyMotion();         break;
            case "C03Cancel":      flyC03Cancel();      break;
        }
    }

    // ==================== GLIDE MODE ====================
    private void flyGlide() {
        if (!flyInitialized) {
            flyStartTime   = System.currentTimeMillis();
            lastSentY      = MinecraftMapper.getPosY();
            flyInitialized = true;
        }
        flyTicks++;

        double tps              = 20.0;
        long   ping             = 1;
        double tpsMultiplier    = Math.max(0.5, tps / 20.0);
        double pingCompensation = 1.0 + (ping / 100.0 * 0.1);

        double speed  = (flySpeed()         * 0.55) * tpsMultiplier * pingCompensation;
        double vSpeed = (flyVerticalSpeed()  * 0.55) * tpsMultiplier * pingCompensation;

        float rotationYaw = MinecraftMapper.getRotationYaw();

        float yawVariation = (rand.nextFloat() - 0.5f) * 0.3f;
        float yawRad       = degreesToRadians(rotationYaw + yawVariation);

        double motionX = 0.0, motionY = 0.0, motionZ = 0.0;

        if (Keyboard.isKeyDown(17)) { // W
            double v = 1.0 + (rand.nextDouble() - 0.5) * 0.03;
            motionX += -Math.sin(yawRad) * speed * v;
            motionZ +=  Math.cos(yawRad) * speed * v;
        }
        if (Keyboard.isKeyDown(31)) { // S
            double v = 1.0 + (rand.nextDouble() - 0.5) * 0.03;
            motionX +=  Math.sin(yawRad) * speed * v;
            motionZ += -Math.cos(yawRad) * speed * v;
        }
        if (Keyboard.isKeyDown(30)) { // A
            double v = 1.0 + (rand.nextDouble() - 0.5) * 0.03;
            double sideYaw = yawRad - Math.PI / 2;
            motionX += -Math.sin(sideYaw) * speed * v;
            motionZ +=  Math.cos(sideYaw) * speed * v;
        }
        if (Keyboard.isKeyDown(32)) { // D
            double v = 1.0 + (rand.nextDouble() - 0.5) * 0.03;
            double sideYaw = yawRad + Math.PI / 2;
            motionX += -Math.sin(sideYaw) * speed * v;
            motionZ +=  Math.cos(sideYaw) * speed * v;
        }
        if (Keyboard.isKeyDown(57)) { // SPACE
            double v = 1.0 + (rand.nextDouble() - 0.5) * 0.03;
            if (!disableY()) motionY += vSpeed * v;
        }
        if (Keyboard.isKeyDown(42)) { // SHIFT
            double v = 1.0 + (rand.nextDouble() - 0.5) * 0.03;
            if (!disableY()) motionY -= vSpeed * v;
        }
        if (!disableY() && !Keyboard.isKeyDown(57) && !Keyboard.isKeyDown(42)) {
            motionY -= 0.04;
        }

        double s = 0.8;
        MinecraftMapper.setMotionX((MinecraftMapper.getMotionX() * s) + (motionX * (1.0 - s)));
        if (!disableY()) MinecraftMapper.setMotionY((MinecraftMapper.getMotionY() * s) + (motionY * (1.0 - s)));
        MinecraftMapper.setMotionZ((MinecraftMapper.getMotionZ() * s) + (motionZ * (1.0 - s)));
        lastSentY = MinecraftMapper.getPosY();
    }

    // ==================== MOTION PLUS GLIDE MODE (AAC BYPASS) ====================
    private void flyMotionPlusGlide() {
        long currentTime = System.currentTimeMillis();
        tickCounter++;

        double tps              = 20.0;
        long   ping             = 1;
        double tpsMultiplier    = Math.max(0.3, Math.min(1.2, tps / 20.0));
        double pingCompensation = Math.min(1.5, 1.0 + (ping / 100.0 * 0.05));

        double targetSpeed = flySpeed() * tpsMultiplier * pingCompensation;
        double speed       = lastSpeed + (targetSpeed - lastSpeed) * 0.1;
        lastSpeed = speed;

        if (enablePacketHold() && !isHoldingPackets) {
            packetHoldStartTime = currentTime;
            heldPosition = new double[]{
                MinecraftMapper.getPosX(),
                MinecraftMapper.getPosY(),
                MinecraftMapper.getPosZ()
            };
            isHoldingPackets = true;
            System.out.println("[Fly] Packet holding started at: "
                + heldPosition[0] + ", " + heldPosition[1] + ", " + heldPosition[2]);
        }

        float yawRad   = degreesToRadians(MinecraftMapper.getRotationYaw());
        float pitchRad = degreesToRadians(MinecraftMapper.getRotationPitch());

        double motionX = 0.0, motionY = 0.0, motionZ = 0.0;
        boolean isMoving = false, verticalMovement = false;

        if (Keyboard.isKeyDown(17)) {
            motionX += -Math.sin(yawRad) * speed * 0.8;
            motionZ +=  Math.cos(yawRad) * speed * 0.8;
            motionY += -Math.sin(pitchRad) * speed * 0.15;
            isMoving = true;
        }
        if (Keyboard.isKeyDown(31)) {
            motionX +=  Math.sin(yawRad) * speed * 0.7;
            motionZ += -Math.cos(yawRad) * speed * 0.7;
            motionY +=  Math.sin(pitchRad) * speed * 0.15;
            isMoving = true;
        }
        if (Keyboard.isKeyDown(30)) {
            double sideYaw = yawRad - Math.PI / 2;
            motionX += -Math.sin(sideYaw) * speed * 0.7;
            motionZ +=  Math.cos(sideYaw) * speed * 0.7;
            isMoving = true;
        }
        if (Keyboard.isKeyDown(32)) {
            double sideYaw = yawRad + Math.PI / 2;
            motionX += -Math.sin(sideYaw) * speed * 0.7;
            motionZ +=  Math.cos(sideYaw) * speed * 0.7;
            isMoving = true;
        }
        if (Keyboard.isKeyDown(57)) {
            motionY += speed * 0.18;
            isMoving = true; verticalMovement = true;
        }
        if (Keyboard.isKeyDown(42)) {
            motionY -= speed * 0.18;
            isMoving = true; verticalMovement = true;
        }

        if (!isMoving) {
            motionX = lastMotionX * 0.7;
            if (!disableY()) motionY = lastMotionY * 0.7;
            motionZ = lastMotionZ * 0.7;
            if (Math.abs(motionX) < 0.01) motionX = 0;
            if (Math.abs(motionY) < 0.01) motionY = 0;
            if (Math.abs(motionZ) < 0.01) motionZ = 0;
        }

        double sm = verticalMovement ? 0.5 : 0.4;
        double smX = lastMotionX + (motionX - lastMotionX) * sm;
        double smY = disableY() ? lastMotionY : lastMotionY + (motionY - lastMotionY) * sm;
        double smZ = lastMotionZ + (motionZ - lastMotionZ) * sm;

        double fs = flySpeed();
        smX = Math.max(-fs, Math.min(fs, smX));
        smY = Math.max(-fs, Math.min(fs, smY));
        smZ = Math.max(-fs, Math.min(fs, smZ));

        MinecraftMapper.setMotionX(smX);
        if (!disableY()) MinecraftMapper.setMotionY(smY);
        MinecraftMapper.setMotionZ(smZ);

        lastMotionX = smX; lastMotionY = smY; lastMotionZ = smZ;

        manageFallDistance();

        if (tickCounter % 5 == 0) {
            try { MinecraftMapper.setOnGround(true); } catch (Exception ignored) {}
        }

        long holdDurationMs = (long)(holdDuration() * 1000);
        if (enablePacketHold() && isHoldingPackets
                && (currentTime - packetHoldStartTime) >= holdDurationMs) {
            performSoftTeleport();
        }

        if (rand.nextInt(500) < 1) {
            try { Thread.sleep(rand.nextInt(3) + 1); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private void manageFallDistance() {
        if (simulatedFallDistance > 0) {
            simulatedFallDistance -= 0.05f;
            if (simulatedFallDistance < 0) simulatedFallDistance = 0;
        }
        if (tickCounter % 15 == 0) {
            try { MinecraftMapper.setFallDistance(simulatedFallDistance); }
            catch (Exception ignored) {}
        }
    }

    private void performSoftTeleport() {
        double targetX = MinecraftMapper.getPosX();
        double targetY = MinecraftMapper.getPosY();
        double targetZ = MinecraftMapper.getPosZ();

        double deltaX = targetX - heldPosition[0];
        double deltaY = targetY - heldPosition[1];
        double deltaZ = targetZ - heldPosition[2];
        double distance = Math.sqrt(deltaX*deltaX + deltaY*deltaY + deltaZ*deltaZ);

        System.out.println("[Fly] Soft teleport - Distance: " + String.format("%.2f", distance)
            + " | From: " + String.format("%.1f,%.1f,%.1f", heldPosition[0], heldPosition[1], heldPosition[2])
            + " | To: "   + String.format("%.1f,%.1f,%.1f", targetX, targetY, targetZ));

        if (distance > 0.5) {
            double ratio = Math.min(teleportSpeed() / distance, 0.3);
            heldPosition[0] += deltaX * ratio;
            heldPosition[1] += deltaY * ratio;
            heldPosition[2] += deltaZ * ratio;
            simulatedFallDistance += 0.01f;
            System.out.println("[Fly] Moving held position by " + String.format("%.2f%%", ratio * 100));
        } else {
            isHoldingPackets = false;
            heldPosition     = null;
            System.out.println("[Fly] Packet holding released - target reached");
        }
    }

    // ==================== MOTION MODE ====================
    private void flyMotion() {
        if (!flyInitialized) {
            flyStartTime   = System.currentTimeMillis();
            lastSentY      = MinecraftMapper.getPosY();
            flyInitialized = true;
        }
        flyTicks++;

        double tps              = 20.0;
        long   ping             = 1;
        double tpsMultiplier    = Math.max(0.5, tps / 20.0);
        double pingCompensation = 1.0 + (ping / 100.0 * 0.1);
        double speed            = flySpeed() * tpsMultiplier * pingCompensation;

        float yawRad = degreesToRadians(MinecraftMapper.getRotationYaw());

        double motionX = 0, motionY = 0, motionZ = 0;

        if (Keyboard.isKeyDown(17)) { motionX += -Math.sin(yawRad)*speed; motionZ +=  Math.cos(yawRad)*speed; }
        if (Keyboard.isKeyDown(31)) { motionX +=  Math.sin(yawRad)*speed; motionZ += -Math.cos(yawRad)*speed; }
        if (Keyboard.isKeyDown(30)) { double sy = yawRad-Math.PI/2; motionX += -Math.sin(sy)*speed; motionZ +=  Math.cos(sy)*speed; }
        if (Keyboard.isKeyDown(32)) { double sy = yawRad+Math.PI/2; motionX += -Math.sin(sy)*speed; motionZ +=  Math.cos(sy)*speed; }
        if (Keyboard.isKeyDown(57)) { motionY += speed; }
        if (Keyboard.isKeyDown(42)) { motionY -= speed; }

        try { MinecraftMapper.setOnGround(true); MinecraftMapper.setFallDistance(0); }
        catch (Exception ignored) {}

        MinecraftMapper.setMotionX(motionX);
        if (!disableY()) MinecraftMapper.setMotionY(motionY);
        MinecraftMapper.setMotionZ(motionZ);

        lastSentY = MinecraftMapper.getPosY();
    }

    // ==================== C03 CANCEL MODE ====================
    private void flyC03Cancel() {
        Object player = MinecraftMapper.getPlayer();
        if (player == null) return;

        lastX1 = MinecraftMapper.getPosX(player);
        lastY1 = MinecraftMapper.getPosY(player);
        lastZ1 = MinecraftMapper.getPosZ(player);
        wasFlying = true;

        double tps           = 20.0;
        long   ping          = 1;
        double tpsMultiplier = Math.max(0.8, tps / 20.0);
        double pingComp      = 1.0 + (ping * 0.002);
        double speed         = flySpeed() * tpsMultiplier * pingComp;

        float yawRad = degreesToRadians(MinecraftMapper.getRotationYaw());

        double motionX = 0.0, motionY = 0.0, motionZ = 0.0;

        if (Keyboard.isKeyDown(17)) { motionX += -Math.sin(yawRad)*speed; motionZ +=  Math.cos(yawRad)*speed; }
        if (Keyboard.isKeyDown(31)) { motionX +=  Math.sin(yawRad)*speed; motionZ += -Math.cos(yawRad)*speed; }
        if (Keyboard.isKeyDown(30)) { double sy = yawRad-Math.PI/2; motionX += -Math.sin(sy)*speed; motionZ +=  Math.cos(sy)*speed; }
        if (Keyboard.isKeyDown(32)) { double sy = yawRad+Math.PI/2; motionX += -Math.sin(sy)*speed; motionZ +=  Math.cos(sy)*speed; }
        if (Keyboard.isKeyDown(57)) { motionY += speed; }
        if (Keyboard.isKeyDown(42)) { motionY -= speed; }

        MinecraftMapper.setMotionX(motionX);
        if (!disableY()) MinecraftMapper.setMotionY(motionY);
        MinecraftMapper.setMotionZ(motionZ);

        try { MinecraftMapper.setFallDistance(0); MinecraftMapper.setOnGround(true); }
        catch (Exception ignored) {}
    }

    // ==================== PACKET MANIPULATION ====================
    // Not: PacketSendEvent entegrasyonu event bus'a bağlandığında aktif olacak
    public void onPacketSend(Object event) {
        if (!isEnabled()) return;
        if (!flyMode().equals("MotionPlusGlide") || !enablePacketHold()) return;
        if (!isHoldingPackets || heldPosition == null) return;

        try {
            Class<?> c03 = MappingUtils.get("C03PacketPlayer");
            if (c03 == null) return;

            // event'ten paketi al
            java.lang.reflect.Method getPacket = event.getClass().getMethod("getPacket");
            Object packet = getPacket.invoke(event);
            if (!c03.isInstance(packet)) return;

            if (xFieldCache == null || yFieldCache == null || zFieldCache == null) {
                xFieldCache = MappingUtils.getField("C03PacketPlayer.x");
                yFieldCache = MappingUtils.getField("C03PacketPlayer.y");
                zFieldCache = MappingUtils.getField("C03PacketPlayer.z");
                if (xFieldCache != null) xFieldCache.setAccessible(true);
                if (yFieldCache != null) yFieldCache.setAccessible(true);
                if (zFieldCache != null) zFieldCache.setAccessible(true);
            }

            if (xFieldCache != null && yFieldCache != null && zFieldCache != null) {
                double origX = xFieldCache.getDouble(packet);
                double origY = yFieldCache.getDouble(packet);
                double origZ = zFieldCache.getDouble(packet);

                xFieldCache.setDouble(packet, heldPosition[0]);
                yFieldCache.setDouble(packet, heldPosition[1]);
                zFieldCache.setDouble(packet, heldPosition[2]);

                if (tickCounter % 20 == 0) {
                    System.out.println("[Fly] Packet modified - Real: "
                        + String.format("%.1f,%.1f,%.1f", origX, origY, origZ)
                        + " | Sent: "
                        + String.format("%.1f,%.1f,%.1f", heldPosition[0], heldPosition[1], heldPosition[2]));
                }
            }
        } catch (Exception e) {
            System.out.println("[Fly] Packet manipulation error: " + e.getMessage());
        }
    }
}
