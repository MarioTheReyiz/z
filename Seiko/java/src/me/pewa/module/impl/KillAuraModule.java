package me.pewa.module.impl;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.pewa.mapping.MinecraftMapper;
import me.pewa.module.Category;
import me.pewa.module.Module;
import me.pewa.setting.BooleanOption;
import me.pewa.setting.NumberOption;
import me.pewa.setting.StringOption;
import me.pewa.util.Logger;
import me.pewa.util.MappingUtils;
import org.lwjgl.input.Keyboard;

public class KillAuraModule extends Module {
    private static final Pattern POSITION_PATTERN = Pattern.compile("x=([-\\d,.]+), y=([-\\d,.]+), z=([-\\d,.]+)");

    private final NumberOption range;
    private final NumberOption minCps;
    private final NumberOption maxCps;
    private final NumberOption failRate;
    private final StringOption keepSprint;
    private final StringOption attackMode;
    private final StringOption targetLock;
    private final BooleanOption rotationEnabled;
    private final StringOption rotationMode;
    private final NumberOption rotationSpeed;

    // Rotation state
    private float lastYaw   = 0.0f;
    private float lastPitch = 0.0f;
    private boolean rotationInitialized = false;

    private final Random rand = new SecureRandom();
    private final Random patternRandom = new SecureRandom();
    private final Random humanRandom = new Random();
    private final Set<String> friends = new HashSet<String>();
    private final Map<String, Double> behaviorProfile = new HashMap<String, Double>();
    private final Map<Object, Long> targetAttackHistory = new WeakHashMap<Object, Long>();
    private final Map<Object, double[]> lastEntityPos = Collections.synchronizedMap(new WeakHashMap<Object, double[]>());
    private final List<Long> lastAttackTimes = new ArrayList<Long>();
    private final Deque<Long> rhythmMemory = new ArrayDeque<Long>(20);
    private final Map<Long, Float> historicalJitter = new HashMap<Long, Float>();

    private Object closestTarget;
    private Object primaryTarget;
    private Object lastClosestTarget;
    private double[] lastTargetPos;

    private long lastAttackTime;
    private long lastSwingTime;
    private long lastTargetSwitchTime;
    private long lastBehaviorUpdateTick = System.currentTimeMillis();
    private long lastVelocityPatternChange = System.currentTimeMillis();
    private long lastVelocityCheck = System.currentTimeMillis();
    private long lastMicroAdjustment = System.currentTimeMillis();
    private long lastPatternReset = System.currentTimeMillis();
    private long lastReflexDelayCheck = System.currentTimeMillis();
    private long lastAACBurstTime;
    private long lastMovementChangeTime;
    private long lastSmoothPacketTime;
    private long humanLastCallTime = System.currentTimeMillis();
    private final long sessionStartTime = System.currentTimeMillis();

    private boolean reflexDelayActive;
    private long reflexDelayStart;
    private long reflexDelayDuration;

    private double reactionReadiness = 0.8D + Math.random() * 0.15D;
    private double concentrationDrift = Math.random();
    private double randomWalkMood = 0.5D;
    private double mentalEnergy = 82.0D + Math.random() * 16.0D;
    private double mentalFocusLevel = 0.65D + Math.random() * 0.25D;
    private double bodyFatigueLevel = 0.08D + Math.random() * 0.18D;
    private double flowStateIntensity = 0.45D + Math.random() * 0.45D;
    private double stressLevel;
    private double singleModeMinCPS = 6.0D;
    private double singleModeMaxCPS = 8.0D;
    private double humanSmoothedDelay = 150.0D;
    private double humanFatigue = 1.0D;
    private float playerMovementFactor = 1.0F;

    private int sessionPhase;
    private int attackPatternCounter;
    private int missCounter;
    private int consecutiveHits;
    private int consecutiveMisses;
    private int totalAttackCount;
    private int aacBurstCounter;
    private int multiTargetIndex;
    private int humanBurstRemaining;
    private String lastSelectedAACPattern;

    public KillAuraModule() {
        super("KillAura", "Attacks nearby valid players", Category.COMBAT, Keyboard.KEY_R);

        range = new NumberOption("Range", 3.5D, 1.0D, 6.0D, 0.1D, this);
        minCps = new NumberOption("MinCPS", 7.0D, 6.0D, 14.0D, 0.1D, this);
        maxCps = new NumberOption("MaxCPS", 13.0D, 7.0D, 15.0D, 0.1D, this);
        failRate = new NumberOption("FailRate", 0.2D, 0.0D, 1.0D, 0.01D, this);
        keepSprint = new StringOption("KeepSprint", "Off", this, "On", "Off");
        attackMode = new StringOption("AttackMode", "Single", this, "Single", "Multi");
        targetLock = new StringOption("TargetLock", "Off", this, "On", "Off");
        rotationEnabled = new BooleanOption("Rotation", true, this);
        rotationMode = new StringOption("RotMode", "Silent", this, "Silent", "Body");
        rotationSpeed = new NumberOption("RotSpeed", 0.65D, 0.1D, 1.0D, 0.05D, this);

        range.setGroup("Combat");
        minCps.setGroup("Timing");
        maxCps.setGroup("Timing");
        failRate.setGroup("Timing");
        keepSprint.setGroup("Behavior");
        attackMode.setGroup("Behavior");
        targetLock.setGroup("Behavior");
        rotationEnabled.setGroup("Rotation");
        rotationMode.setGroup("Rotation");
        rotationSpeed.setGroup("Rotation");

        addOptions(range, minCps, maxCps, failRate, keepSprint, attackMode, targetLock,
                rotationEnabled, rotationMode, rotationSpeed);
        patternRandom.setSeed(System.nanoTime() ^ Runtime.getRuntime().freeMemory());
        initializeNewBehaviorPattern();
    }

    @Override
    public void onEnable() {
        lastAttackTime = 0L;
        lastSwingTime = 0L;
        rotationInitialized = false;
        initializeNewBehaviorPattern();
        Logger.kaInfo("=== KillAura ENABLED === range=" + range.getValue()
                + " minCPS=" + minCps.getValue()
                + " maxCPS=" + maxCps.getValue()
                + " mode=" + attackMode.getValue()
                + " targetLock=" + targetLock.getValue());
    }

    @Override
    public void onDisable() {
        Logger.kaInfo("=== KillAura DISABLED === totalAttacks=" + totalAttackCount
                + " consecutiveHits=" + consecutiveHits
                + " sessionPhase=" + sessionPhase);
        resetAllPatterns();
    }

    @Override
    public void onUpdate() {
        if (!isEnabled()) {
            resetAllPatterns();
            return;
        }

        try {
            driftHumanState();
            applyReflexBypassPatterns();
            updateAdvancedAntiPatternSystem();
            executeDynamicTimingManipulation();

            if (!processEnhancedReflexDelay()) {
                return;
            }

            double min = minCps.getValue();
            double max = maxCps.getValue();
            double auraRange = range.getValue();

            if (getCurrentScreen() != null || getThePlayer() == null || getTheWorld() == null) {
                if (closestTarget != null) {
                    Logger.kaDebug("onUpdate: screen/player/world null -> clearing targets");
                }
                closestTarget = null;
                primaryTarget = null;
                return;
            }

            updateCombatModeBehavior();
            selectClosestTarget(auraRange);

            boolean targetLockEnabled = "On".equals(targetLock.getValue());
            if ("Single".equals(getAttackMode()) && closestTarget != null) {
                if (primaryTarget == null || !isValidTarget(primaryTarget)
                        || distanceTo(primaryTarget) > auraRange * 1.15D) {
                    primaryTarget = closestTarget;
                } else {
                    double primaryDist = distanceTo(primaryTarget);
                    if (primaryDist <= auraRange
                            && rand.nextDouble() > (0.7D + mentalFocusLevel * 0.2D - bodyFatigueLevel * 0.3D)) {
                        closestTarget = primaryTarget;
                    }
                }
            }

            if (targetLockEnabled) {
                if (primaryTarget != null && isValidTarget(primaryTarget) && getHealth(primaryTarget) > 0.0F) {
                    double lockedDist = distanceTo(primaryTarget);
                    if (lockedDist <= auraRange * 1.5D) {
                        closestTarget = primaryTarget;
                    } else {
                        primaryTarget = closestTarget;
                    }
                } else {
                    primaryTarget = closestTarget;
                }
            }

            if (closestTarget == null) {
                primaryTarget = null;
                Logger.kaDebug("onUpdate: no target in range=" + auraRange);
                return;
            }

            lastTargetPos = getEntityPos(closestTarget);
            long now = System.currentTimeMillis();
            double effectiveMinCps = "Single".equals(getAttackMode()) ? singleModeMinCPS : min;
            double effectiveMaxCps = "Single".equals(getAttackMode()) ? singleModeMaxCPS : max;
            long delayMs = calculateAAC364OptimizedDelay(effectiveMinCps, effectiveMaxCps, closestTarget);

            Logger.kaDebug("onUpdate: target=" + getName(closestTarget)
                    + " dist=" + String.format("%.2f", distanceTo(closestTarget))
                    + " delayMs=" + delayMs
                    + " sinceLastAttack=" + (now - lastAttackTime)
                    + " mode=" + getAttackMode());

            if (now - lastAttackTime < delayMs) {
                storeEntityPos(closestTarget);
                return;
            }

            float targetHealth = getHealth(closestTarget);
            if (targetHealth <= 0.0F) {
                Logger.kaInfo("onUpdate: target dead -> " + getName(closestTarget) + " health=" + targetHealth);
                handleTargetDeath(closestTarget);
                return;
            }

            double visibilityTolerance = 0.75D + flowStateIntensity * 0.15D - bodyFatigueLevel * 0.2D;
            if (!isEntityVisible(closestTarget) && rand.nextDouble() < visibilityTolerance) {
                Logger.kaDebug("onUpdate: target invisible, skipping -> " + getName(closestTarget));
                return;
            }

            handleTargetSwitch(now);
            boolean willMiss = shouldFailAAC364Attack();
            lastClosestTarget = closestTarget;

            if (now - lastAttackTime >= delayMs) {
                targetAttackHistory.put(closestTarget, now);
                sendAAC364HumanizedSwing();

                if (!willMiss) {
                    if ("Multi".equals(getAttackMode())) {
                        Logger.kaAttack("MULTI-ATTACK swing -> target=" + getName(closestTarget)
                                + " health=" + getHealth(closestTarget)
                                + " dist=" + String.format("%.2f", distanceTo(closestTarget)));
                        attackNextMultiTarget(auraRange, now);
                    } else if (isTargetInRange(closestTarget, auraRange)) {
                        Logger.kaAttack("SINGLE-ATTACK -> target=" + getName(closestTarget)
                                + " health=" + getHealth(closestTarget)
                                + " dist=" + String.format("%.2f", distanceTo(closestTarget))
                                + " consecutiveHits=" + (consecutiveHits + 1)
                                + " totalAttacks=" + (totalAttackCount + 1));
                        boolean aimReady = applyRotation(closestTarget);
                        if (!aimReady) {
                            // Rotation not on target yet — skip attack this tick
                            storeEntityPos(closestTarget);
                            return;
                        }
                        attackEntity(closestTarget);
                        lastAttackTime = now;
                        rememberAttack(now);
                    } else {
                        Logger.kaDebug("ATTACK SKIPPED: target out of range -> "
                                + getName(closestTarget)
                                + " dist=" + String.format("%.2f", distanceTo(closestTarget))
                                + " range=" + auraRange);
                    }

                    int counterInc = (int) (1 + Math.floor(flowStateIntensity * 1.5D * rand.nextDouble()));
                    attackPatternCounter += counterInc;
                    consecutiveHits++;
                    consecutiveMisses = 0;
                    totalAttackCount++;

                    double resetProb = 0.02D + Math.min(0.3D, consecutiveHits / 18.0D);
                    resetProb *= 1.0D + bodyFatigueLevel * 0.5D;
                    if (rand.nextDouble() < resetProb) {
                        Logger.kaDebug("Pattern reset triggered: consecutiveHits=" + consecutiveHits);
                        attackPatternCounter = rand.nextInt(3);
                        consecutiveHits = rand.nextInt(2);
                    }
                } else {
                    Logger.kaDebug("MISS -> target=" + getName(closestTarget)
                            + " consecutiveMisses=" + (consecutiveMisses + 1));
                    handleMiss();
                    if (consecutiveMisses >= 2 && rand.nextDouble() < 0.55D) {
                        Logger.kaDebug("MicroAdjust after " + consecutiveMisses + " consecutive misses");
                        executeAAC364MicroAdjustment();
                    }

                    double adjustProb = 0.35D + (1.0D - flowStateIntensity) * 0.25D + bodyFatigueLevel * 0.2D;
                    if (rand.nextDouble() < adjustProb) {
                        executeAAC364MicroAdjustment();
                    }
                }
            }
        } catch (Throwable t) {
            Logger.warn("KillAura update error: " + String.valueOf(t.getMessage()));
            Logger.kaWarn("onUpdate EXCEPTION: " + t.getClass().getSimpleName() + " -> " + t.getMessage());
        }
    }

    private void selectClosestTarget(double auraRange) {
        closestTarget = null;
        double closestDistance = auraRange + 1.0D;
        int candidateCount = 0;

        for (Object entity : MinecraftMapper.getPlayerEntitiesInWorld()) {
            if (!isValidTarget(entity)) {
                continue;
            }

            double dist = distanceTo(entity);
            if (dist > auraRange) {
                continue;
            }

            candidateCount++;
            boolean pick = false;
            if (closestTarget == null) {
                pick = rand.nextDouble() < 0.92D + mentalFocusLevel * 0.08D;
            } else {
                double distDelta = closestDistance - dist;
                double thresholdBase = 0.08D + concentrationDrift * 0.12D;
                double focusBonus = (mentalFocusLevel - 0.5D) * 0.1D;
                double threshold = thresholdBase + focusBonus;

                if (distDelta > threshold) {
                    pick = true;
                } else if (distDelta > -threshold) {
                    double ambiguityProb = 0.5D + concentrationDrift * 0.3D - bodyFatigueLevel * 0.15D;
                    pick = rand.nextDouble() < ambiguityProb;
                }

                if (!pick && rand.nextDouble() < 0.02D * mentalFocusLevel) {
                    pick = rand.nextDouble() < 0.35D;
                }
            }

            if (pick) {
                closestDistance = dist;
                closestTarget = entity;
            }
        }

        if (closestTarget != null) {
            Logger.kaDebug("selectClosestTarget: picked=" + getName(closestTarget)
                    + " dist=" + String.format("%.2f", closestDistance)
                    + " candidates=" + candidateCount
                    + " range=" + auraRange);
        } else if (candidateCount > 0) {
            Logger.kaDebug("selectClosestTarget: " + candidateCount + " candidates but none selected (focus drift)");
        }
    }

    private void handleTargetSwitch(long now) {
        if (closestTarget == lastClosestTarget || lastClosestTarget == null) {
            return;
        }

        long switchDelayBase = (long) (110.0D * (1.0D + (1.0D - mentalFocusLevel) * 0.4D));
        long switchVariance = (long) (50.0D + concentrationDrift * 60.0D - flowStateIntensity * 40.0D);
        long totalSwitchDelay = switchDelayBase + rand.nextInt((int) Math.max(10L, switchVariance));

        if (now - lastTargetSwitchTime < totalSwitchDelay) {
            Logger.kaDebug("handleTargetSwitch: switch suppressed (delay=" + totalSwitchDelay
                    + "ms) keeping=" + getName(lastClosestTarget)
                    + " -> wanted=" + getName(closestTarget));
            closestTarget = lastClosestTarget;
        } else {
            Logger.kaInfo("handleTargetSwitch: switched " + getName(lastClosestTarget)
                    + " -> " + getName(closestTarget)
                    + " after " + (now - lastTargetSwitchTime) + "ms");
            consecutiveMisses = 0;
            consecutiveHits = Math.max(0, consecutiveHits - 1);
            lastTargetSwitchTime = now;
        }
    }

    private void attackNextMultiTarget(double auraRange, long now) {
        List<Object> targets = new ArrayList<Object>();
        for (Object entity : MinecraftMapper.getPlayerEntitiesInWorld()) {
            if (!isValidTarget(entity)) {
                continue;
            }
            if (!isTargetInRange(entity, auraRange)) {
                continue;
            }
            targets.add(entity);
        }

        Collections.sort(targets, new Comparator<Object>() {
            @Override
            public int compare(Object a, Object b) {
                return Double.compare(distanceTo(a), distanceTo(b));
            }
        });

        if (!targets.isEmpty()) {
            multiTargetIndex = multiTargetIndex % targets.size();
            Object multiTarget = targets.get(multiTargetIndex);
            multiTargetIndex++;
            closestTarget = multiTarget;
            Logger.kaAttack("MULTI-ATTACK -> target=" + getName(multiTarget)
                    + " health=" + getHealth(multiTarget)
                    + " dist=" + String.format("%.2f", distanceTo(multiTarget))
                    + " targetIndex=" + (multiTargetIndex - 1)
                    + " totalTargets=" + targets.size());
            attackEntity(multiTarget);
            lastAttackTime = now;
            rememberAttack(now);
        } else {
            Logger.kaDebug("attackNextMultiTarget: no valid targets in range=" + auraRange);
        }
    }

    private boolean shouldFailAAC364Attack() {
        double baseFail = failRate.getValue();
        double recentAccuracy = clamp(calculateRecentAccuracy(), 0.0D, 1.0D);
        double accModifier = clamp(1.0D + (0.58D - recentAccuracy) * 0.85D, 0.3D, 2.0D);
        double energyModifier = clamp(1.0D + Math.max(0.0D, (100.0D - mentalEnergy) / 80.0D) * 0.35D, 0.5D, 2.0D);
        double fatigueModifier = clamp(1.0D + bodyFatigueLevel * 1.2D, 0.5D, 2.5D);
        double focusModifier = clamp(1.0D - mentalFocusLevel * 0.4D, 0.4D, 1.5D);
        double flowModifier = clamp(1.0D - flowStateIntensity * 0.5D, 0.3D, 1.2D);
        double sessionModifier = clamp(0.85D + sessionPhase * 0.15D, 0.7D, 1.3D);

        double combatIntensity = clamp(calculateCombatIntensity(), 0.0D, 1.0D);
        double combatModifier = 1.0D;
        if (combatIntensity > 0.8D) {
            combatModifier = 1.35D + rand.nextDouble() * 0.2D;
        } else if (combatIntensity > 0.5D) {
            combatModifier = 1.1D + rand.nextDouble() * 0.12D;
        }

        double jitter = clamp(rand.nextGaussian() * 0.12D, -0.25D, 0.35D);
        double mentalLapseProb = clamp(0.01D + (1.0D - mentalFocusLevel) * 0.04D, 0.0D, 0.15D);
        double mentalLapse = 1.0D;
        if (rand.nextDouble() < mentalLapseProb && consecutiveMisses < 3) {
            mentalLapse = 1.6D + rand.nextDouble() * 1.2D;
        }

        double combined = accModifier * energyModifier * fatigueModifier * focusModifier
                * flowModifier * sessionModifier * combatModifier * mentalLapse;
        combined = clamp(combined + jitter, 0.3D, 3.5D);

        if (isPlayerMoving(closestTarget)) {
            double moveVelocity = clamp(getEntityVelocity(getThePlayer()), 0.0D, 2.0D);
            combined *= 1.0D + moveVelocity * 0.25D;
        }

        double finalFailRate = baseFail * combined;
        double dynamicMaxFail = consecutiveMisses >= 3 ? 0.35D : 0.65D;
        finalFailRate = clamp(finalFailRate, 0.02D, dynamicMaxFail);

        if (rand.nextDouble() < 0.005D && combatIntensity > 0.6D) {
            finalFailRate = Math.min(0.95D, finalFailRate + 0.25D);
        }
        if (consecutiveMisses >= 2) {
            finalFailRate *= clamp(1.0D - consecutiveMisses * 0.22D, 0.25D, 1.0D);
        }
        if (consecutiveMisses >= 4) {
            finalFailRate *= 0.15D;
        }

        return rand.nextDouble() < finalFailRate;
    }

    private void updateCombatModeBehavior() {
        if ("Single".equals(getAttackMode())) {
            handleSingleTargetMode();
        }
    }

    private void handleSingleTargetMode() {
        if (primaryTarget != null && isValidTarget(primaryTarget) && distanceTo(primaryTarget) <= range.getValue()) {
            closestTarget = primaryTarget;
        } else {
            primaryTarget = findOptimalSingleTarget();
            closestTarget = primaryTarget;
        }
        adjustTimingForSingleMode();
    }

    private Object findOptimalSingleTarget() {
        double auraRange = range.getValue();
        Object bestTarget = null;
        double bestDistance = auraRange + 1.0D;

        for (Object entity : MinecraftMapper.getPlayerEntitiesInWorld()) {
            if (!isValidTarget(entity)) {
                continue;
            }

            double distance = distanceTo(entity);
            if (distance > auraRange) {
                continue;
            }

            if (distance < bestDistance) {
                bestDistance = distance;
                bestTarget = entity;
            }
        }

        return bestTarget;
    }

    private void adjustTimingForSingleMode() {
        double min = minCps.getValue();
        double max = maxCps.getValue();
        singleModeMinCPS = Math.max(min * 0.88D, min - 0.6D);
        singleModeMaxCPS = Math.min(max * 1.12D, max + 0.6D);
    }

    private void driftHumanState() {
        long now = System.currentTimeMillis();
        long delta = now - lastBehaviorUpdateTick;
        if (delta < 500L) {
            return;
        }
        lastBehaviorUpdateTick = now;

        bodyFatigueLevel = clamp(bodyFatigueLevel + (rand.nextDouble() - 0.45D) * 0.008D, 0.0D, 1.0D);
        double stressFactor = stressLevel / 100.0D;
        double fatigueDownside = bodyFatigueLevel * 0.3D;
        mentalFocusLevel += (rand.nextDouble() - 0.5D) * 0.012D - fatigueDownside - stressFactor * 0.008D;
        mentalFocusLevel = clamp(mentalFocusLevel, 0.4D, 1.0D);
        reactionReadiness = clamp(0.7D + mentalFocusLevel * 0.25D + rand.nextGaussian() * 0.05D, 0.6D, 1.0D);

        double combatIntensity = calculateCombatIntensity();
        flowStateIntensity += (combatIntensity - flowStateIntensity) * 0.04D;
        flowStateIntensity = clamp(flowStateIntensity - bodyFatigueLevel * 0.02D, 0.3D, 1.0D);
        concentrationDrift = clamp(concentrationDrift + (rand.nextDouble() - 0.5D) * 0.15D, 0.0D, 1.0D);
        randomWalkMood = clamp(randomWalkMood + (rand.nextDouble() - 0.5D) * 0.08D, 0.0D, 1.0D);
    }

    private void updateAdvancedAntiPatternSystem() {
        long now = System.currentTimeMillis();
        long sessionDuration = now - sessionStartTime;
        sessionPhase = calculateDynamicSessionPhase(sessionDuration);
        double mood = 0.35D + 0.65D * (1.0D - Math.exp(-sessionPhase / 3.0D));
        double inertia = 0.6D + 0.4D * (1.0D - mood);

        if (timeSince(lastMicroAdjustment) > 12000L + patternRandom.nextInt(10000)
                - (long) (2000.0D * (mood - 0.5D))) {
            if (patternRandom.nextDouble() < 0.85D * mood) {
                executeAAC364CompliantAdjustment();
            } else if (patternRandom.nextDouble() < 0.5D) {
                singleModeMinCPS = clamp(singleModeMinCPS * (1.0D + jitter(0.02D)), 3.5D, 20.0D);
            }
            lastMicroAdjustment = now;
        }

        if (timeSince(lastPatternReset) > 25000L + patternRandom.nextInt(20000)) {
            if (patternRandom.nextDouble() < 0.6D * (1.0D - inertia)) {
                executeAdvancedPatternReset();
            } else {
                attackPatternCounter = Math.max(0, attackPatternCounter - patternRandom.nextInt(2));
            }
            lastPatternReset = now;
        }

        int adaptiveResetThreshold = calculateAdaptiveResetThreshold();
        boolean bursty = consecutiveHits > 18 + patternRandom.nextInt(8) && patternRandom.nextDouble() < 0.35D;
        if (totalAttackCount > adaptiveResetThreshold || bursty) {
            executeStealthPatternReset();
            totalAttackCount = patternRandom.nextInt(Math.max(1, adaptiveResetThreshold / 4));
            aacBurstCounter = 0;
        }

        if (consecutiveHits > 12 + patternRandom.nextInt(6)) {
            if (patternRandom.nextDouble() < 0.8D) {
                aacBurstCounter++;
            }
            if (aacBurstCounter > 2 + patternRandom.nextInt(2)) {
                if (patternRandom.nextDouble() < 0.7D) {
                    executePreventiveMicroAdjustment();
                } else {
                    lastAttackTime += 10L + patternRandom.nextInt(20);
                }
                aacBurstCounter = 0;
            }
        } else if (aacBurstCounter > 0 && patternRandom.nextDouble() < 0.4D) {
            aacBurstCounter--;
        }

        if (now - lastVelocityPatternChange > 15000L + patternRandom.nextInt(15000)) {
            randomizeVelocityPatterns();
            lastVelocityPatternChange = now;
        }

        if (now % 45000L < 80L && patternRandom.nextDouble() < 0.18D + 0.2D * (1.0D - mood)) {
            injectTimingAnomaly();
        }

        updateDynamicBehaviorProfile();
        simulateHumanVariance();
        manageSessionLongevity(now);
    }

    private int calculateDynamicSessionPhase(long sessionDuration) {
        double base = sessionDuration / (90000.0D + patternRandom.nextInt(45000));
        double combatIntensity = calculateCombatIntensity();
        double accuracy = calculateRecentAccuracy();

        double bias = 0.0D;
        if (combatIntensity > 0.7D) {
            bias += 0.9D;
        }
        if (accuracy < 0.6D) {
            bias += 0.5D;
        }

        int phase = (int) Math.floor(base + bias);
        return Math.max(0, Math.min(6, phase));
    }

    private void executeAAC364CompliantAdjustment() {
        double variation = 0.9D + patternRandom.nextDouble() * 0.18D;
        double sessionMod = 0.92D + sessionPhase * 0.04D;
        variation *= sessionMod * (1.0D + jitter(0.012D));

        singleModeMinCPS = Math.max(3.8D, lerp(singleModeMinCPS, singleModeMinCPS * variation, 0.45D));
        singleModeMaxCPS = Math.min(16.5D, lerp(singleModeMaxCPS, singleModeMaxCPS * variation, 0.35D));

        if (patternRandom.nextDouble() < 0.45D) {
            int adjustment = patternRandom.nextInt(3);
            attackPatternCounter = Math.max(0, attackPatternCounter + adjustment - patternRandom.nextInt(2));
        }

        if (patternRandom.nextDouble() < 0.3D) {
            int timingShift = 6 + patternRandom.nextInt(16);
            if (patternRandom.nextDouble() < 0.3D) {
                timingShift = -timingShift;
            }
            lastAttackTime += patternRandom.nextDouble() < 0.65D ? timingShift : -timingShift / 2;
        }
    }

    private void executeAdvancedPatternReset() {
        double resetVariation = 0.75D + patternRandom.nextDouble() * 0.5D;
        double resetIntensity = 0.85D + sessionPhase * 0.06D;
        resetVariation *= resetIntensity * (1.0D + jitter(0.03D));

        singleModeMinCPS = Math.max(4.0D, lerp(singleModeMinCPS, singleModeMinCPS * resetVariation, 0.45D));
        singleModeMaxCPS = Math.min(15.8D, lerp(singleModeMaxCPS, singleModeMaxCPS * resetVariation, 0.35D));
        attackPatternCounter = Math.max(0, attackPatternCounter - patternRandom.nextInt(4));
        consecutiveHits = Math.max(0, consecutiveHits - patternRandom.nextInt(4));
        consecutiveMisses = Math.max(0, consecutiveMisses - patternRandom.nextInt(3));

        if (patternRandom.nextDouble() < 0.5D) {
            rhythmMemory.clear();
        }
        if (patternRandom.nextDouble() < 0.35D) {
            lastAttackTimes.clear();
        }

        long cutoff = System.currentTimeMillis() - 30000L;
        List<Long> remove = new ArrayList<Long>();
        for (Long key : historicalJitter.keySet()) {
            if (patternRandom.nextDouble() < 0.65D || key.longValue() < cutoff) {
                remove.add(key);
            }
        }
        historicalJitter.keySet().removeAll(remove);
    }

    private int calculateAdaptiveResetThreshold() {
        int baseThreshold = 260 + patternRandom.nextInt(220);
        double phaseModifier = 1.0D - sessionPhase * 0.07D;
        double accuracyModifier = calculateRecentAccuracy() > 0.7D ? 1.15D : 0.92D;
        double jitterFactor = 0.9D + patternRandom.nextDouble() * 0.3D;
        int val = (int) (baseThreshold * phaseModifier * accuracyModifier * jitterFactor);
        return Math.max(120, val);
    }

    private void executeStealthPatternReset() {
        for (Map.Entry<String, Double> entry : new ArrayList<Map.Entry<String, Double>>(behaviorProfile.entrySet())) {
            behaviorProfile.put(entry.getKey(), entry.getValue() * (0.85D + patternRandom.nextDouble() * 0.25D));
        }

        attackPatternCounter = patternRandom.nextInt(3);
        consecutiveHits = patternRandom.nextInt(4);
        consecutiveMisses = patternRandom.nextInt(2);
        missCounter = 0;

        if (patternRandom.nextDouble() < 0.5D && !rhythmMemory.isEmpty()) {
            int remove = Math.max(1, rhythmMemory.size() / (2 + patternRandom.nextInt(3)));
            for (int i = 0; i < remove && !rhythmMemory.isEmpty(); i++) {
                rhythmMemory.removeFirst();
            }
        }

        if (patternRandom.nextDouble() < 0.25D) {
            lastAttackTimes.clear();
        }

        List<Object> stale = new ArrayList<Object>();
        for (Map.Entry<Object, Long> entry : targetAttackHistory.entrySet()) {
            if (patternRandom.nextDouble() < 0.7D || System.currentTimeMillis() - entry.getValue() > 15000L) {
                stale.add(entry.getKey());
            }
        }
        for (Object key : stale) {
            targetAttackHistory.remove(key);
        }

        initializeAAC364CompliantPattern();
    }

    private void executePreventiveMicroAdjustment() {
        double preventiveVariation = 0.94D + patternRandom.nextDouble() * 0.12D;
        singleModeMinCPS = clamp(singleModeMinCPS * preventiveVariation * (1.0D + jitter(0.01D)), 3.5D, 18.0D);
        singleModeMaxCPS = clamp(singleModeMaxCPS * preventiveVariation * (1.0D + jitter(0.01D)), 6.0D, 20.0D);

        if (patternRandom.nextDouble() < 0.35D) {
            attackPatternCounter = Math.max(0, attackPatternCounter + (patternRandom.nextInt(3) - 1));
        }

        if (patternRandom.nextDouble() < 0.28D) {
            lastAttackTime += 4L + patternRandom.nextInt(14);
        }
    }

    private void randomizeVelocityPatterns() {
        double target = 0.7D + patternRandom.nextDouble() * 0.6D;
        playerMovementFactor = (float) lerp(playerMovementFactor, (float) target, 0.35D);
        if (patternRandom.nextDouble() < 0.35D) {
            lastMovementChangeTime = System.currentTimeMillis();
        }
    }

    private void injectTimingAnomaly() {
        int anomalyType = patternRandom.nextInt(3);
        if (anomalyType == 0) {
            lastAttackTime += 6L + patternRandom.nextInt(16);
        } else if (anomalyType == 1) {
            lastAttackTime -= 4L + patternRandom.nextInt(12);
        } else {
            attackPatternCounter = Math.max(0, attackPatternCounter + (patternRandom.nextInt(3) - 1));
        }
    }

    private void updateDynamicBehaviorProfile() {
        double recentAccuracy = calculateRecentAccuracy();
        double combatIntensity = calculateCombatIntensity();

        if (recentAccuracy < 0.5D && patternRandom.nextDouble() < 0.38D) {
            double current = behaviorProfile.containsKey("cps_variance") ? behaviorProfile.get("cps_variance") : 0.2D;
            behaviorProfile.put("cps_variance",
                    clamp(lerp(current, Math.min(0.35D, current * 1.25D), 0.4D + jitter(0.03D)), 0.02D, 0.6D));
        }

        if (combatIntensity > 0.75D && patternRandom.nextDouble() < 0.48D) {
            double current = behaviorProfile.containsKey("fail_rate_base") ? behaviorProfile.get("fail_rate_base") : 0.1D;
            behaviorProfile.put("fail_rate_base",
                    clamp(lerp(current, Math.min(0.28D, current * 1.18D), 0.35D + jitter(0.03D)), 0.01D, 0.4D));
        }

        if (sessionPhase > 3) {
            double currentRotation = behaviorProfile.containsKey("rotation_speed") ? behaviorProfile.get("rotation_speed") : 0.65D;
            behaviorProfile.put("rotation_speed",
                    clamp(lerp(currentRotation, currentRotation * 0.92D, 0.25D + jitter(0.02D)), 0.25D, 1.3D));
        }
    }

    private void simulateHumanVariance() {
        if (patternRandom.nextDouble() < 0.06D) {
            singleModeMinCPS *= 0.88D + jitter(0.02D);
            singleModeMaxCPS *= 0.88D + jitter(0.02D);
        }
        if (patternRandom.nextDouble() < 0.05D) {
            singleModeMinCPS *= 1.12D + jitter(0.02D);
            singleModeMaxCPS *= 1.12D + jitter(0.02D);
        }
    }

    private void manageSessionLongevity(long now) {
        if (sessionPhase <= 4) {
            return;
        }
        if (patternRandom.nextDouble() < 0.095D) {
            executeStealthPatternReset();
        }
        if (now % 60000L < 100L) {
            double current = behaviorProfile.containsKey("cps_variance") ? behaviorProfile.get("cps_variance") : 0.2D;
            double varianceBoost = 1.04D + sessionPhase * 0.03D + jitter(0.02D);
            behaviorProfile.put("cps_variance", clamp(current * varianceBoost, 0.01D, 0.6D));
        }
        if (patternRandom.nextDouble() < 0.03D) {
            singleModeMinCPS = Math.max(3.5D, singleModeMinCPS * (0.96D + jitter(0.01D)));
            singleModeMaxCPS = Math.max(5.0D, singleModeMaxCPS * (0.98D + jitter(0.01D)));
        }
    }

    private void initializeAAC364CompliantPattern() {
        String[] patterns = { "BALANCED", "STEADY", "ADAPTIVE", "PRECISE" };
        double sessionMood = 0.4D + 0.6D * (1.0D - Math.exp(-sessionPhase / 4.0D));
        double[] weights = new double[patterns.length];
        for (int i = 0; i < weights.length; i++) {
            weights[i] = 1.0D;
            if (patterns[i].equals(lastSelectedAACPattern)) {
                weights[i] *= 1.2D + patternRandom.nextDouble() * 0.6D * sessionMood;
            }
        }

        double sum = 0.0D;
        for (double weight : weights) {
            sum += weight;
        }

        double pick = patternRandom.nextDouble() * sum;
        String selected = patterns[0];
        for (int i = 0; i < weights.length; i++) {
            pick -= weights[i];
            if (pick <= 0.0D) {
                selected = patterns[i];
                break;
            }
        }

        double cpsVariance = 0.15D;
        double rotationSpeed = 0.55D;
        if ("BALANCED".equals(selected)) {
            cpsVariance = 0.16D + patternRandom.nextDouble() * 0.06D;
            rotationSpeed = 0.58D + patternRandom.nextDouble() * 0.12D;
        } else if ("STEADY".equals(selected)) {
            cpsVariance = 0.10D + patternRandom.nextDouble() * 0.05D;
            rotationSpeed = 0.48D + patternRandom.nextDouble() * 0.10D;
        } else if ("ADAPTIVE".equals(selected)) {
            cpsVariance = 0.20D + patternRandom.nextDouble() * 0.10D;
            rotationSpeed = 0.65D + patternRandom.nextDouble() * 0.18D;
        } else if ("PRECISE".equals(selected)) {
            cpsVariance = 0.09D + patternRandom.nextDouble() * 0.04D;
            rotationSpeed = 0.44D + patternRandom.nextDouble() * 0.08D;
        }

        double stabilization = 1.0D - 0.12D * (1.0D - Math.exp(-sessionPhase / 6.0D));
        behaviorProfile.put("cps_variance", clamp(cpsVariance * stabilization + jitter(0.02D), 0.0D, 1.0D));
        behaviorProfile.put("rotation_speed", clamp(rotationSpeed * (1.0D + 0.06D * (1.0D - sessionMood)) + jitter(0.02D), 0.0D, 1.0D));
        lastSelectedAACPattern = selected;
    }

    private void applyReflexBypassPatterns() {
        long currentTime = System.currentTimeMillis();
        long periodicWindow = 25000L + patternRandom.nextInt(15000);
        if (currentTime % periodicWindow < 140L) {
            int offset = (int) Math.round(patternRandom.nextGaussian() * 6.0D);
            lastAttackTime += offset;
            double switchProb = 0.10D + 0.18D * calculateCombatIntensity();
            if (patternRandom.nextDouble() < switchProb) {
                attackPatternCounter = (attackPatternCounter + 1 + patternRandom.nextInt(2)) % 4;
            }
            humanFatigue = Math.max(0.4D, humanFatigue - patternRandom.nextDouble() * 0.02D);
        }

        long velocityInterval = 8000L + patternRandom.nextInt(12000);
        if (currentTime - lastVelocityCheck > velocityInterval) {
            if (patternRandom.nextDouble() < 0.85D) {
                modifyVelocityPatterns();
                lastVelocityCheck = currentTime;
            } else {
                lastVelocityCheck = currentTime - patternRandom.nextInt(4000);
            }
        }
    }

    private long calculateAAC364OptimizedDelay(double mincps, double maxcps, Object target) {
        long now = System.currentTimeMillis();
        double deltaSec = (now - humanLastCallTime) / 1000.0D;
        humanLastCallTime = now;

        double min = Math.max(1.0D, mincps);
        double max = Math.max(min + 0.1D, maxcps);
        double baseMean = (min + max) / 2.0D * (0.92D + humanRandom.nextDouble() * 0.16D);
        double std = (max - min) * (0.10D + humanRandom.nextDouble() * 0.06D);
        double sample = baseMean + humanRandom.nextGaussian() * std;

        if (humanRandom.nextDouble() < 0.06D) {
            sample *= 0.75D + humanRandom.nextDouble() * 1.35D;
        }
        sample = clamp(sample, min, max);

        double rhythm = 1.0D + Math.sin(now * 0.00072D) * 0.065D + Math.cos(now * 0.00185D) * 0.045D
                + Math.sin(now * 0.00027D) * 0.02D;
        sample *= rhythm;

        humanFatigue = clamp(humanFatigue + (humanRandom.nextDouble() - 0.5D) * 0.012D, 0.88D, 1.12D);
        sample *= humanFatigue * (1.0D + sessionPhase * 0.032D);

        if (target != null) {
            double distance = distanceTo(target);
            if (distance > 6.0D) {
                sample *= 0.89D;
            } else if (distance < 1.8D) {
                sample *= 1.11D;
            }
        }

        if (humanBurstRemaining > 0) {
            sample *= 1.06D;
            humanBurstRemaining--;
        } else if (humanRandom.nextDouble() < 0.045D) {
            humanBurstRemaining = 1 + humanRandom.nextInt(3);
        }

        if (deltaSec > 2.0D && humanRandom.nextDouble() < 0.5D) {
            sample *= 0.78D + humanRandom.nextDouble() * 0.18D;
        }
        if (humanRandom.nextDouble() < 0.018D) {
            sample /= 1.25D + humanRandom.nextDouble() * 1.6D;
        }

        double delayMs = 1000.0D / Math.max(1.0D, sample);
        delayMs += clamp(humanRandom.nextGaussian() * 7.0D, -28.0D, 28.0D);
        delayMs += (humanRandom.nextDouble() - 0.5D) * 9.0D;

        if (humanRandom.nextDouble() < 0.007D) {
            delayMs += 60.0D + humanRandom.nextDouble() * 220.0D;
        }

        double alpha = clamp(1.0D - Math.exp(-Math.max(0.01D, deltaSec) * 1.6D), 0.08D, 0.45D);
        humanSmoothedDelay = humanSmoothedDelay * (1.0D - alpha) + delayMs * alpha;
        return (long) clamp(humanSmoothedDelay, 35.0D, 800.0D);
    }

    private void executeAAC364MicroAdjustment() {
        double microVariation = 0.92D + patternRandom.nextDouble() * 0.14D;
        singleModeMinCPS = Math.max(4.2D, singleModeMinCPS * microVariation);
        singleModeMaxCPS = Math.min(15.5D, singleModeMaxCPS * microVariation);

        if (patternRandom.nextDouble() < 0.35D) {
            attackPatternCounter = Math.max(0, attackPatternCounter + (patternRandom.nextInt(3) - 1));
        }
        if (patternRandom.nextDouble() < 0.25D) {
            lastAttackTime -= 8L + patternRandom.nextInt(20);
        }
    }

    private void sendAAC364HumanizedSwing() {
        long currentTime = System.currentTimeMillis();
        long minSwingDelay = 30L + patternRandom.nextInt(25);
        if (currentTime - lastSwingTime < minSwingDelay) {
            return;
        }

        sendSwing();
        lastSwingTime = currentTime;
    }

    private boolean processEnhancedReflexDelay() {
        long currentTime = System.currentTimeMillis();

        if (currentTime - lastReflexDelayCheck > 3000L + patternRandom.nextInt(5000)) {
            if (patternRandom.nextDouble() < 0.32D) {
                reflexDelayActive = true;
                reflexDelayStart = currentTime;
                reflexDelayDuration = 120L + patternRandom.nextInt(300);
            }
            lastReflexDelayCheck = currentTime;
        }

        if (reflexDelayActive && currentTime - reflexDelayStart < reflexDelayDuration) {
            if (patternRandom.nextDouble() < 0.35D) {
                executeAAC364MicroAdjustment();
            }
            return false;
        }
        reflexDelayActive = false;
        return true;
    }

    private void modifyVelocityPatterns() {
        if (patternRandom.nextDouble() < 0.4D) {
            playerMovementFactor = 0.8F + patternRandom.nextFloat() * 0.4F;
        }
        if (patternRandom.nextDouble() < 0.25D) {
            lastMovementChangeTime = System.currentTimeMillis();
        }
    }

    private void executeDynamicTimingManipulation() {
        long currentTime = System.currentTimeMillis();

        if (patternRandom.nextDouble() < 0.15D) {
            lastSmoothPacketTime = currentTime - (15L + patternRandom.nextInt(30));
        }

        if (currentTime % 20000L < 50L && patternRandom.nextDouble() < 0.3D) {
            lastAttackTime += 20L + patternRandom.nextInt(25);
        }

        if (sessionPhase > 2 && patternRandom.nextDouble() < 0.1D) {
            lastAttackTime -= patternRandom.nextInt(35);
        }
    }

    private void handleTargetDeath(Object target) {
        Logger.kaInfo("handleTargetDeath: target=" + getName(target)
                + " consecutiveHits=" + consecutiveHits
                + " totalAttacks=" + totalAttackCount);
        lastTargetPos = getEntityPos(target);
        targetAttackHistory.remove(target);
        if (primaryTarget == target) {
            primaryTarget = null;
        }
        if (closestTarget == target) {
            closestTarget = null;
        }
        attackPatternCounter = Math.max(0, attackPatternCounter - patternRandom.nextInt(3));
        consecutiveHits = 0;
    }

    private void handleMiss() {
        lastAttackTime = System.currentTimeMillis();
        consecutiveMisses++;
        consecutiveHits = 0;
        missCounter++;
        totalAttackCount++;

        Logger.kaDebug("handleMiss: consecutiveMisses=" + consecutiveMisses
                + " missCounter=" + missCounter
                + " totalAttacks=" + totalAttackCount);

        if (missCounter > 1 + patternRandom.nextInt(2) && patternRandom.nextDouble() < 0.65D) {
            Logger.kaDebug("handleMiss: pattern counter reset after " + missCounter + " misses");
            attackPatternCounter = patternRandom.nextInt(3);
        }
    }

    private boolean isTargetInRange(Object target, double auraRange) {
        return target != null && distanceTo(target) <= auraRange * 1.05D;
    }

    private boolean isEntityVisible(Object entity) {
        return !isInvisible(entity);
    }

    private void initializeNewBehaviorPattern() {
        behaviorProfile.clear();
        String[] patterns = { "STEADY", "AGGRESSIVE", "DEFENSIVE", "ERRATIC", "PRECISE", "ADAPTIVE", "BALANCED" };
        String selected = patterns[patternRandom.nextInt(patterns.length)];

        if ("STEADY".equals(selected)) {            behaviorProfile.put("cps_variance", 0.14D + patternRandom.nextDouble() * 0.07D);
            behaviorProfile.put("fail_rate_base", 0.07D + patternRandom.nextDouble() * 0.05D);
            behaviorProfile.put("rotation_speed", 0.60D + patternRandom.nextDouble() * 0.12D);
        } else if ("AGGRESSIVE".equals(selected)) {
            behaviorProfile.put("cps_variance", 0.24D + patternRandom.nextDouble() * 0.10D);
            behaviorProfile.put("fail_rate_base", 0.11D + patternRandom.nextDouble() * 0.06D);
            behaviorProfile.put("rotation_speed", 0.80D + patternRandom.nextDouble() * 0.18D);
        } else if ("DEFENSIVE".equals(selected)) {
            behaviorProfile.put("cps_variance", 0.10D + patternRandom.nextDouble() * 0.06D);
            behaviorProfile.put("fail_rate_base", 0.05D + patternRandom.nextDouble() * 0.04D);
            behaviorProfile.put("rotation_speed", 0.50D + patternRandom.nextDouble() * 0.12D);
        } else if ("ERRATIC".equals(selected)) {
            behaviorProfile.put("cps_variance", 0.38D + patternRandom.nextDouble() * 0.18D);
            behaviorProfile.put("fail_rate_base", 0.16D + patternRandom.nextDouble() * 0.10D);
            behaviorProfile.put("rotation_speed", 1.05D + patternRandom.nextDouble() * 0.35D);
        } else if ("PRECISE".equals(selected)) {
            behaviorProfile.put("cps_variance", 0.08D + patternRandom.nextDouble() * 0.05D);
            behaviorProfile.put("fail_rate_base", 0.03D + patternRandom.nextDouble() * 0.03D);
            behaviorProfile.put("rotation_speed", 0.40D + patternRandom.nextDouble() * 0.12D);
        } else if ("ADAPTIVE".equals(selected)) {
            behaviorProfile.put("cps_variance", 0.17D + patternRandom.nextDouble() * 0.12D);
            behaviorProfile.put("fail_rate_base", 0.08D + patternRandom.nextDouble() * 0.06D);
            behaviorProfile.put("rotation_speed", 0.70D + patternRandom.nextDouble() * 0.22D);
        } else {
            behaviorProfile.put("cps_variance", 0.20D + patternRandom.nextDouble() * 0.10D);
            behaviorProfile.put("fail_rate_base", 0.09D + patternRandom.nextDouble() * 0.05D);
            behaviorProfile.put("rotation_speed", 0.65D + patternRandom.nextDouble() * 0.18D);
        }

        double phaseFactor = 0.70D + Math.log(sessionPhase + 1.0D) * 0.12D;
        for (Map.Entry<String, Double> entry : new ArrayList<Map.Entry<String, Double>>(behaviorProfile.entrySet())) {
            behaviorProfile.put(entry.getKey(), entry.getValue() * phaseFactor);
        }
        Logger.kaInfo("initializeNewBehaviorPattern: pattern=" + selected
                + " phaseFactor=" + String.format("%.3f", phaseFactor)
                + " sessionPhase=" + sessionPhase
                + " cps_variance=" + String.format("%.3f", behaviorProfile.containsKey("cps_variance") ? behaviorProfile.get("cps_variance") : 0.0)
                + " fail_rate_base=" + String.format("%.3f", behaviorProfile.containsKey("fail_rate_base") ? behaviorProfile.get("fail_rate_base") : 0.0)
                + " rotation_speed=" + String.format("%.3f", behaviorProfile.containsKey("rotation_speed") ? behaviorProfile.get("rotation_speed") : 0.0));
    }

    private double calculateCombatIntensity() {
        if (closestTarget == null) {
            return 0.0D;
        }

        long currentTime = System.currentTimeMillis();
        double intensity = 0.0D;
        intensity += Math.min(1.0D, calculateAttackFrequency() / 13.0D) * (0.30D + (currentTime % 45L) * 0.004D);
        intensity += Math.min(1.0D, getNearbyEntities().size() / 7.0D) * (0.20D + (currentTime % 35L) * 0.003D);
        intensity += (1.0D - Math.min(1.0D, distanceTo(closestTarget) / 10.0D))
                * (0.35D + (currentTime % 55L) * 0.003D);
        intensity += calculateDamageExchangeRate() * (0.25D + (currentTime % 40L) * 0.004D);
        return Math.min(1.0D, intensity * (0.85D + (System.nanoTime() % 250L) / 1000.0D));
    }

    private double calculateAttackFrequency() {
        return Math.min(13.0D, lastAttackTimes.size() / 9.0D) * (0.75D + (System.nanoTime() % 500L) / 1000.0D);
    }

    private double calculateDamageExchangeRate() {
        return (System.nanoTime() % 700L) / 1000.0D * 0.55D;
    }

    private double calculateRecentAccuracy() {
        return 0.60D + (System.nanoTime() % 550L) / 1000.0D * 0.35D;
    }

    private void resetAllPatterns() {
        Logger.kaInfo("resetAllPatterns: clearing all state"
                + " totalAttacks=" + totalAttackCount
                + " consecutiveHits=" + consecutiveHits
                + " sessionPhase=" + sessionPhase);
        closestTarget = null;
        primaryTarget = null;
        lastTargetPos = null;
        lastClosestTarget = null;
        behaviorProfile.clear();
        attackPatternCounter = patternRandom.nextInt(3);
        consecutiveHits = patternRandom.nextInt(3);
        consecutiveMisses = 0;
        missCounter = 0;
        rhythmMemory.clear();
        lastAttackTimes.clear();
        historicalJitter.clear();
        targetAttackHistory.clear();
        aacBurstCounter = 0;
        lastAACBurstTime = System.currentTimeMillis();
        initializeNewBehaviorPattern();
    }

    private boolean isValidTarget(Object entity) {
        if (entity == null || entity == getThePlayer()) {
            return false;
        }
        if (isBot(entity)) {
            return false;
        }

        String name = getName(entity);
        if (name == null) {
            return false;
        }

        String clean = stripColor(name).trim();
        if (friends.contains(clean.toLowerCase()) || friends.contains(clean)) {
            return false;
        }
        if (clean.length() == 0 || clean.contains(" ") || clean.matches(".*[\\\\/\\[\\]].*")) {
            return false;
        }
        return getHealth(entity) > 0.0F;
    }

    private boolean isBot(Object entity) {
        return false;
    }

    private String getAttackMode() {
        return attackMode.getValue();
    }

    private List<Object> getNearbyEntities() {
        Object player = getThePlayer();
        double radius = range.getValue() + 10.0D;
        List<Object> nearby = new ArrayList<Object>();
        if (player == null) {
            return nearby;
        }

        double px = getPosX(player);
        double py = getPosY(player);
        double pz = getPosZ(player);

        for (Object entity : MinecraftMapper.getPlayerEntitiesInWorld()) {
            if (entity == null || entity == player) {
                continue;
            }
            double dx = getPosX(entity) - px;
            double dy = getPosY(entity) - py;
            double dz = getPosZ(entity) - pz;
            if (dx * dx + dy * dy + dz * dz <= radius * radius) {
                nearby.add(entity);
            }
        }
        return nearby;
    }

    private void attackEntity(Object entity) {
        try {
            String targetName = getName(entity);
            float targetHealth = getHealth(entity);
            double targetDist = distanceTo(entity);

            Logger.kaAttack("attackEntity -> name=" + targetName
                    + " health=" + targetHealth
                    + " dist=" + String.format("%.2f", targetDist)
                    + " totalAttacks=" + totalAttackCount
                    + " consecutiveHits=" + consecutiveHits
                    + " sessionPhase=" + sessionPhase
                    + " focus=" + String.format("%.3f", mentalFocusLevel)
                    + " fatigue=" + String.format("%.3f", bodyFatigueLevel)
                    + " flow=" + String.format("%.3f", flowStateIntensity));

            boolean success = MinecraftMapper.attackEntity(entity);
            if (!success) {
                Logger.kaWarn("attackEntity: raw attack returned false -> target=" + targetName);
                Logger.warn("KillAura raw attack failed");
            } else {
                Logger.kaDebug("attackEntity: SUCCESS -> " + targetName);
            }
        } catch (Throwable t) {
            Logger.kaWarn("attackEntity EXCEPTION: " + t.getClass().getSimpleName()
                    + " -> " + t.getMessage());
            Logger.warn("KillAura attack failed: " + String.valueOf(t.getMessage()));
        }
    }

    private Object createC02(Object entity) {
        try {
            if (entity == null) {
                return null;
            }

            Class<?> packetClass = MappingUtils.get("C02PacketUseEntity");
            Class<?> actionClass = MappingUtils.get("C02PacketUseEntityAction");
            if (packetClass == null || actionClass == null) {
                return null;
            }

            for (Constructor<?> constructor : packetClass.getDeclaredConstructors()) {
                Class<?>[] params = constructor.getParameterTypes();
                if (params.length == 2 && params[1] == actionClass) {
                    constructor.setAccessible(true);
                    Object attack = getAttackEnum(actionClass);
                    return constructor.newInstance(entity, attack);
                }
            }

            Object packet = packetClass.getDeclaredConstructor().newInstance();
            Field entityIdField = findPrivateIntField(packetClass);
            Field actionField = getFieldByType(packetClass, actionClass);
            if (entityIdField == null || actionField == null) {
                return null;
            }

            entityIdField.setAccessible(true);
            actionField.setAccessible(true);
            entityIdField.setInt(packet, getId(entity));
            actionField.set(packet, getAttackEnum(actionClass));
            return packet;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object getAttackEnum(Class<?> actionClass) throws Exception {
        Field attackField = actionClass.getField("ATTACK");
        attackField.setAccessible(true);
        return attackField.get(null);
    }

    // ── Rotation ──────────────────────────────────────────────────────────────

    /**
     * Calculates target yaw/pitch from player eye to target eye center.
     */
    private float[] getRotationsTo(Object target) {
        Object player = getThePlayer();
        if (player == null || target == null) return null;

        double px = getPosX(player);
        double py = getPosY(player) + getEyeHeight(player);
        double pz = getPosZ(player);

        double tx = getPosX(target);
        double ty = getPosY(target) + getEyeHeight(target);
        double tz = getPosZ(target);

        double dx = tx - px;
        double dy = ty - py;
        double dz = tz - pz;
        double dist = Math.sqrt(dx * dx + dz * dz);

        float yaw   = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, dist));

        return new float[]{ yaw, pitch };
    }

    /**
     * Smoothly interpolates an angle toward target, respecting the 180° wrap.
     */
    private float smoothAngle(float current, float target, float speed) {
        float diff = target - current;
        // Wrap to [-180, 180]
        while (diff > 180.0f)  diff -= 360.0f;
        while (diff < -180.0f) diff += 360.0f;
        float step = diff * speed;
        // Add small human jitter
        step += (float)(rand.nextGaussian() * 0.4);
        return current + step;
    }

    private double getEyeHeight(Object entity) {
        try {
            Method m = MappingUtils.getMethod("Entity.getEyeHeight");
            if (m != null) {
                m.setAccessible(true);
                return ((Number) m.invoke(entity)).doubleValue();
            }
        } catch (Throwable ignored) {}
        return 1.62;
    }

    /**
     * Applies rotation toward the target.
     * Returns true if the rotation is close enough to the target to allow attacking.
     *
     * Body mode:  sets actual player rotation + crosshair check (must be within threshold).
     * Silent mode: sends C05 packet + looser threshold (no visual, but still needs to be
     *              reasonably aimed to avoid obvious desync).
     */
    private boolean applyRotation(Object target) {
        if (!rotationEnabled.getValue()) return true; // rotation off → always allow attack

        float[] rotations = getRotationsTo(target);
        if (rotations == null) return true;

        float targetYaw   = rotations[0];
        float targetPitch = rotations[1];

        // Clamp pitch
        if (targetPitch > 90.0f)  targetPitch = 90.0f;
        if (targetPitch < -90.0f) targetPitch = -90.0f;

        // Init lastYaw/lastPitch from player on first use
        if (!rotationInitialized) {
            Object player = getThePlayer();
            if (player != null) {
                lastYaw   = getPlayerRotationYaw(player);
                lastPitch = getPlayerRotationPitch(player);
            }
            rotationInitialized = true;
        }

        float speed = rotationSpeed.getValue().floatValue();
        float smoothYaw   = smoothAngle(lastYaw,   targetYaw,   speed);
        float smoothPitch = smoothAngle(lastPitch, targetPitch, speed);

        lastYaw   = smoothYaw;
        lastPitch = smoothPitch;

        boolean silent = "Silent".equals(rotationMode.getValue());

        if (silent) {
            sendSilentRotation(smoothYaw, smoothPitch);
            // Silent: allow attack if yaw diff < 45° and pitch diff < 30°
            float yawDiff   = angleDiff(smoothYaw,   targetYaw);
            float pitchDiff = angleDiff(smoothPitch, targetPitch);
            return yawDiff < 45.0f && pitchDiff < 30.0f;
        } else {
            // Body mode: set actual player rotation
            setPlayerRotation(smoothYaw, smoothPitch);
            // Only allow attack if crosshair is within threshold of target
            float yawDiff   = angleDiff(smoothYaw,   targetYaw);
            float pitchDiff = angleDiff(smoothPitch, targetPitch);
            return yawDiff < 25.0f && pitchDiff < 20.0f;
        }
    }

    /** Returns the absolute angular difference between two angles (0-180). */
    private static float angleDiff(float a, float b) {
        float diff = Math.abs(a - b) % 360.0f;
        return diff > 180.0f ? 360.0f - diff : diff;
    }

    private float getPlayerRotationYaw(Object player) {
        try {
            Field f = MappingUtils.getField("Entity.rotationYaw");
            if (f != null) { f.setAccessible(true); return f.getFloat(player); }
        } catch (Throwable ignored) {}
        return 0.0f;
    }

    private float getPlayerRotationPitch(Object player) {
        try {
            Field f = MappingUtils.getField("Entity.rotationPitch");
            if (f != null) { f.setAccessible(true); return f.getFloat(player); }
        } catch (Throwable ignored) {}
        return 0.0f;
    }

    private void setPlayerRotation(float yaw, float pitch) {
        try {
            Object player = getThePlayer();
            if (player == null) return;
            Field yawF = MappingUtils.getField("Entity.rotationYaw");
            Field pitchF = MappingUtils.getField("Entity.rotationPitch");
            if (yawF != null)   { yawF.setAccessible(true);   yawF.setFloat(player, yaw); }
            if (pitchF != null) { pitchF.setAccessible(true); pitchF.setFloat(player, pitch); }
        } catch (Throwable ignored) {}
    }

    private void sendSilentRotation(float yaw, float pitch) {
        try {
            // Try C05PacketPlayerLook first
            Class<?> c05 = MappingUtils.get("C05PacketPlayerLook");
            if (c05 != null) {
                Object player = getThePlayer();
                boolean onGround = isOnGround(player);
                Object packet = c05.getConstructor(float.class, float.class, boolean.class)
                        .newInstance(yaw, pitch, onGround);
                sendPacket(packet);
                return;
            }
            // Fallback: C03PacketPlayer with yaw/pitch fields
            Class<?> c03 = MappingUtils.get("C03PacketPlayer");
            if (c03 != null) {
                Object packet = c03.getConstructor().newInstance();
                Field yawF   = MappingUtils.getField("C03PacketPlayer.yaw");
                Field pitchF = MappingUtils.getField("C03PacketPlayer.pitch");
                Field groundF = MappingUtils.getField("C03PacketPlayer.onGround");
                if (yawF != null && pitchF != null) {
                    yawF.setAccessible(true);
                    pitchF.setAccessible(true);
                    yawF.setFloat(packet, yaw);
                    pitchF.setFloat(packet, pitch);
                    if (groundF != null) {
                        groundF.setAccessible(true);
                        Object player = getThePlayer();
                        groundF.setBoolean(packet, isOnGround(player));
                    }
                    sendPacket(packet);
                }
            }
        } catch (Throwable ignored) {}
    }

    private boolean isOnGround(Object player) {
        try {
            Field f = MappingUtils.getField("Entity.onGround");
            if (f != null) { f.setAccessible(true); return f.getBoolean(player); }
        } catch (Throwable ignored) {}
        return true;
    }

    private void sendSwing() {
        try {
            Method swing = MappingUtils.getMethod("EntityPlayerSP.swingItem");
            Object player = getThePlayer();
            if (swing != null && player != null) {
                swing.setAccessible(true);
                swing.invoke(player);
            }
        } catch (Throwable ignored) {
        }
    }

    private void sendPacket(Object packet) {
        try {
            Object netHandler = getNetHandler();
            Method sendPacket = MappingUtils.getMethod("NetHandlerPlayClient.sendPacket");
            if (packet != null && netHandler != null && sendPacket != null) {
                sendPacket.setAccessible(true);
                sendPacket.invoke(netHandler, packet);
            }
        } catch (Throwable ignored) {
        }
    }

    private Object getCurrentScreen() {
        try {
            Object minecraft = getMinecraft();
            Field field = MappingUtils.getField("Minecraft.currentScreen");
            if (minecraft != null && field != null) {
                field.setAccessible(true);
                return field.get(minecraft);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Object getNetHandler() {
        try {
            Field field = MappingUtils.getField("EntityPlayerSP.sendQueue");
            Object player = getThePlayer();
            if (field != null && player != null) {
                field.setAccessible(true);
                return field.get(player);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private int getId(Object entity) {
        if (entity == null) {
            return -1;
        }
        try {
            Method hashCode = entity.getClass().getMethod("hashCode");
            return ((Integer) hashCode.invoke(entity)).intValue();
        } catch (Throwable ignored) {
            return entity.hashCode();
        }
    }

    private double distanceTo(Object entity) {
        if (entity == null) {
            return Double.MAX_VALUE;
        }
        try {
            Method method = MappingUtils.getMethod("Entity.getDistanceToEntity");
            Object player = getThePlayer();
            if (method != null && player != null) {
                method.setAccessible(true);
                return ((Number) method.invoke(player, entity)).doubleValue();
            }
        } catch (Throwable ignored) {
        }

        Object player = getThePlayer();
        if (player == null) {
            return Double.MAX_VALUE;
        }

        double dx = getPosX(player) - getPosX(entity);
        double dy = getPosY(player) - getPosY(entity);
        double dz = getPosZ(player) - getPosZ(entity);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double getPosX(Object entity) {
        return getDoubleField(entity, "Entity.posX", 0, 1);
    }

    private double getPosY(Object entity) {
        return getDoubleField(entity, "Entity.posY", 1, 1);
    }

    private double getPosZ(Object entity) {
        return getDoubleField(entity, "Entity.posZ", 2, 1);
    }

    private double getDoubleField(Object entity, String mapping, int posIndex, double fallback) {
        if (entity == null) {
            return fallback;
        }
        try {
            Field field = MappingUtils.getField(mapping);
            if (field != null) {
                field.setAccessible(true);
                return field.getDouble(entity);
            }
        } catch (Throwable ignored) {
        }

        double[] parsed = parseEntityPosition(entity);
        return parsed == null ? fallback : parsed[posIndex];
    }

    private double[] parseEntityPosition(Object entity) {
        try {
            Matcher matcher = POSITION_PATTERN.matcher(String.valueOf(entity));
            if (matcher.find()) {
                return new double[] {
                        Double.parseDouble(matcher.group(1).replace(",", ".")),
                        Double.parseDouble(matcher.group(2).replace(",", ".")),
                        Double.parseDouble(matcher.group(3).replace(",", "."))
                };
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private double[] getEntityPos(Object entity) {
        if (entity == null) {
            return null;
        }
        return new double[] { getPosX(entity), getPosY(entity), getPosZ(entity) };
    }

    private float getHealth(Object entity) {
        try {
            Method method = MappingUtils.getMethod("EntityLivingBase.getHealth");
            if (method != null && entity != null) {
                method.setAccessible(true);
                return ((Number) method.invoke(entity)).floatValue();
            }
        } catch (Throwable ignored) {
        }
        return 20.0F;
    }

    private String getName(Object entity) {
        return MinecraftMapper.getName(entity);
    }

    private boolean isInvisible(Object entity) {
        try {
            Method method = MappingUtils.getMethod("Entity.getFlag");
            if (method != null && entity != null) {
                method.setAccessible(true);
                // Flag index 1 = invisible in Minecraft 1.8 (bit mask 0x02)
                return ((Boolean) method.invoke(entity, Integer.valueOf(1))).booleanValue();
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean isPlayerMoving(Object entity) {
        return getEntityVelocity(entity) > 1.0E-4D;
    }

    private double getEntityVelocity(Object entity) {
        double x = getMotion(entity, "Entity.motionX");
        double y = getMotion(entity, "Entity.motionY");
        double z = getMotion(entity, "Entity.motionZ");
        return Math.sqrt(x * x + y * y + z * z) * playerMovementFactor;
    }

    private double getMotion(Object entity, String mapping) {
        try {
            Field field = MappingUtils.getField(mapping);
            Method getter = MappingUtils.getMethod("MotionContainer.getDoubleValue");
            if (field != null && getter != null && entity != null) {
                field.setAccessible(true);
                Object motion = field.get(entity);
                if (motion != null) {
                    getter.setAccessible(true);
                    return ((Number) getter.invoke(motion)).doubleValue();
                }
            }
        } catch (Throwable ignored) {
        }
        return 0.0D;
    }

    private void storeEntityPos(Object entity) {
        double[] pos = getEntityPos(entity);
        if (pos != null) {
            lastEntityPos.put(entity, pos);
        }
    }

    private void rememberAttack(long now) {
        lastAttackTimes.add(Long.valueOf(now));
        while (lastAttackTimes.size() > 40) {
            lastAttackTimes.remove(0);
        }
        rhythmMemory.addLast(Long.valueOf(now));
        while (rhythmMemory.size() > 20) {
            rhythmMemory.removeFirst();
        }
    }

    private Field findPrivateIntField(Class<?> clazz) {
        for (Field field : clazz.getDeclaredFields()) {
            int mods = field.getModifiers();
            if (field.getType() == int.class && !java.lang.reflect.Modifier.isStatic(mods)) {
                return field;
            }
        }
        return null;
    }

    private Field getFieldByType(Class<?> clazz, Class<?> type) {
        for (Field field : clazz.getDeclaredFields()) {
            if (field.getType() == type) {
                return field;
            }
        }
        return null;
    }

    private long timeSince(long time) {
        return System.currentTimeMillis() - time;
    }

    private double jitter(double scale) {
        return patternRandom.nextGaussian() * scale;
    }

    private double lerp(double from, double to, double amount) {
        return from + amount * (to - from);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String stripColor(String value) {
        return value == null ? "" : value.replaceAll("(?i)\\u00A7.", "");
    }
}
