package net.maxello.tutorialmod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;

import net.minecraft.block.BlockState;
import net.minecraft.block.CropBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.*;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;

public class TutorialModClient implements ClientModInitializer {

    // 60 seconds cooldown per key, in ms
    private static final long COOLDOWN_MS = 60_000L;

    // Max range for ranged "aim" checks (in blocks)
    private static final double MAX_RANGED_DISTANCE = 50.0D;

    // Max angle from look direction (in degrees) to still count as "aimed at"
    private static final double MAX_RANGED_ANGLE_DEGREES = 12.0D;

    // Track last attack / use key state
    private static boolean lastAttackPressed = false;
    private static boolean lastUsePressed = false;

    // Bow draw tracking
    private static boolean wasUsingBow = false;
    private static long bowUseStartTick = 0L;

    // Crossbow: track "first click" vs "second click"
    private static boolean crossbowPrimed = false;

    // Tick counter to measure durations
    private static long tickCounter = 0L;

    // All skills the server tracks knowledge for
    private enum Skill {
        MELEE_COMBAT,
        DIGGING,
        FORESTRY,
        FARMING,
        MINING,
        RANGED_COMBAT,
        ARMOURING,
        WEAPONSMITHING,
        TOOLSMITHING
    }

    // Generic tiers
    private enum Tier {
        WOOD,
        STONE,
        COPPER,
        IRON,
        DIAMOND,
        LEATHER,
        CHAINMAIL
    }

    // One cooldown entry = one Skill + one Tier + (optional) tool group ("bow", "crossbow")
    private record SkillKey(Skill skill, Tier tier, String toolGroup) {}

    // When each key is ready again (system millis)
    private static final Map<SkillKey, Long> cooldownEnd = new HashMap<>();
    // To only play the "ready" sound once
    private static final Map<SkillKey, Boolean> soundPlayed = new HashMap<>();

    @Override
    public void onInitializeClient() {
        registerBlockBreakListener();
        registerTickListener();
        registerHudOverlay();
    }

    /* ================== BLOCK BREAK LISTENER ================== */

    private void registerBlockBreakListener() {
        ClientPlayerBlockBreakEvents.AFTER.register(TutorialModClient::onClientBlockBreak);
    }

    private static void onClientBlockBreak(ClientWorld world, ClientPlayerEntity player, BlockPos pos, BlockState state) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != player) return;

        ItemStack held = player.getMainHandStack();
        SkillKey key = detectSkillKeyFromBlock(held, state);
        if (key == null) return;

        startCooldown(key);
    }

    /* ================== MELEE & RANGED VIA TICK ================== */

    private void registerTickListener() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            tickCounter++;
            long now = System.currentTimeMillis();

            // --- 1) Handle cooldown expiry + sounds ---
            for (var entry : cooldownEnd.entrySet()) {
                SkillKey key = entry.getKey();
                long end = entry.getValue();

                if (end <= now && !soundPlayed.getOrDefault(key, false)) {
                    client.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 1.0f);
                    soundPlayed.put(key, true);
                }
            }

            // --- 2) Melee: attack key edge ---
            boolean attackNow = client.options.attackKey.isPressed();
            if (attackNow && !lastAttackPressed) {
                handleMeleeAttack(client);
            }
            lastAttackPressed = attackNow;

            // --- 3) Crossbow: use key edge ---
            boolean useNow = client.options.useKey.isPressed();
            if (useNow && !lastUsePressed) {
                handleUseKeyPressed(client);
            }
            lastUsePressed = useNow;

            // --- 4) Bow: detect draw + release ---
            handleBowUsageTick(client);
        });
    }

    private static void handleMeleeAttack(MinecraftClient client) {
        if (client.player == null) return;

        ItemStack held = client.player.getMainHandStack();
        Tier tier = getToolTier(held);
        if (tier == null) return;

        boolean isMelee =
                held.isIn(ItemTags.SWORDS) ||
                        held.isIn(ItemTags.AXES) ||
                        held.getItem() instanceof SwordItem ||
                        held.getItem() instanceof AxeItem;

        if (!isMelee) return;

        if (!(client.crosshairTarget instanceof EntityHitResult ehr)) return;
        Entity target = ehr.getEntity();
        if (!(target instanceof LivingEntity)) return;

        SkillKey key = new SkillKey(Skill.MELEE_COMBAT, tier, null);
        startCooldown(key);
    }

    /**
     * Called on right-click edge.
     * We implement simple "two-click" logic for crossbows:
     *  - First right-click with crossbow: start charging (we just mark primed, ignore)
     *  - Second right-click with crossbow while aiming at mob (within cone/range): treat as shot → start timer
     */
    private static void handleUseKeyPressed(MinecraftClient client) {
        if (client.player == null) return;

        ItemStack held = client.player.getMainHandStack();

        // Only care about crossbows here
        if (!(held.getItem() instanceof CrossbowItem)) {
            // If they're not even holding a crossbow, clear primed flag
            crossbowPrimed = false;
            return;
        }

        // No previous click → this is the "start charging" click, just prime and ignore
        if (!crossbowPrimed) {
            crossbowPrimed = true;
            return;
        }

        // If we get here: crossbowPrimed == true → treat this as the "shot" click
        LivingEntity target = getAimedLivingEntity(client, MAX_RANGED_DISTANCE, MAX_RANGED_ANGLE_DEGREES);
        if (target == null) {
            crossbowPrimed = false;
            return;
        }

        Tier tier = getToolTier(held);
        if (tier != null) {
            SkillKey key = new SkillKey(Skill.RANGED_COMBAT, tier, "crossbow");
            startCooldown(key);
        }

        // We consumed the primed state for this shot
        crossbowPrimed = false;
    }

    /**
     * Track when a bow is being drawn and released.
     * Approx shot moment = "player stopped using bow after holding for a while".
     */
    private static void handleBowUsageTick(MinecraftClient client) {
        if (client.player == null) {
            wasUsingBow = false;
            return;
        }

        ItemStack active = client.player.getActiveItem();
        boolean isUsingBowNow = !active.isEmpty() && active.getItem() instanceof BowItem;

        // Just started drawing the bow
        if (!wasUsingBow && isUsingBowNow) {
            bowUseStartTick = tickCounter;
        }

        // Just released the bow
        if (wasUsingBow && !isUsingBowNow) {
            long usedTicks = tickCounter - bowUseStartTick;

            // Only treat as a "shot" if drawn for at least a few ticks
            if (usedTicks >= 5) {
                handleBowShot(client);
            }
        }

        wasUsingBow = isUsingBowNow;
    }

    private static void handleBowShot(MinecraftClient client) {
        if (client.player == null) return;

        ItemStack held = client.player.getMainHandStack();
        if (!(held.getItem() instanceof BowItem)) return;

        Tier tier = getToolTier(held);
        if (tier == null) return;

        LivingEntity target = getAimedLivingEntity(client, MAX_RANGED_DISTANCE, MAX_RANGED_ANGLE_DEGREES);
        if (target == null) return;

        SkillKey key = new SkillKey(Skill.RANGED_COMBAT, tier, "bow");
        startCooldown(key);
    }

    /* ================== COOLDOWN HANDLING ================== */

    private static void startCooldown(SkillKey key) {
        long now = System.currentTimeMillis();
        Long existingEnd = cooldownEnd.get(key);

        if (existingEnd == null || existingEnd <= now) {
            cooldownEnd.put(key, now + COOLDOWN_MS);
            soundPlayed.put(key, false);
        }
    }

    /* ================== BLOCK → SKILL LOGIC ================== */

    private static SkillKey detectSkillKeyFromBlock(ItemStack held, BlockState state) {
        if (held.isEmpty()) return null;

        Tier tier = getToolTier(held);
        if (tier == null) return null;

        // Forestry: logs + axe
        if (held.isIn(ItemTags.AXES) && state.isIn(BlockTags.LOGS)) {
            return new SkillKey(Skill.FORESTRY, tier, null);
        }

        // Mining: anything mineable with pickaxe (stone/ores/metal blocks etc.)
        if (held.isIn(ItemTags.PICKAXES) && state.isIn(BlockTags.PICKAXE_MINEABLE)) {
            return new SkillKey(Skill.MINING, tier, null);
        }

        // Digging: shovel blocks (dirt, sand, gravel, etc.)
        if (held.isIn(ItemTags.SHOVELS) && state.isIn(BlockTags.SHOVEL_MINEABLE)) {
            return new SkillKey(Skill.DIGGING, tier, null);
        }

        // Farming: breaking crops while holding a hoe
        if (held.isIn(ItemTags.HOES) && state.getBlock() instanceof CropBlock) {
            return new SkillKey(Skill.FARMING, tier, null);
        }

        return null;
    }

    /* ================== ITEM → TIER LOGIC ================== */

    private static Tier getToolTier(ItemStack held) {
        String id = held.getItem().toString().toLowerCase();

        if (id.contains("wooden"))    return Tier.WOOD;
        if (id.contains("stone"))     return Tier.STONE;

        // Server uses golden tools as "copper" tools (retextured)
        if (id.contains("golden"))    return Tier.COPPER;
        if (id.contains("copper"))    return Tier.COPPER;

        if (id.contains("iron"))      return Tier.IRON;
        if (id.contains("diamond"))   return Tier.DIAMOND;

        if (id.contains("leather"))   return Tier.LEATHER;
        if (id.contains("chainmail")) return Tier.CHAINMAIL;

        // Bows / crossbows / modded ranged weapons → treat as WOOD tier by default
        if (id.contains("bow") || id.contains("crossbow")) {
            return Tier.WOOD;
        }

        return null;
    }

    /* ================== LONG-DISTANCE "AIM CONE" ENTITY DETECTION ================== */

    /**
     * Returns the living entity roughly in the player's aim direction, within a certain
     * max distance and angle cone. This lets you aim slightly above a mob (for arrow drop)
     * and still have it count.
     */
    private static LivingEntity getAimedLivingEntity(MinecraftClient client, double maxDistance, double maxAngleDeg) {
        if (client.player == null || client.world == null) return null;

        Vec3d eyePos = client.player.getCameraPosVec(1.0f);
        Vec3d lookDir = client.player.getRotationVec(1.0f).normalize();

        double maxAngleRad = Math.toRadians(maxAngleDeg);
        double minDot = Math.cos(maxAngleRad); // dot product threshold

        Box searchBox = client.player.getBoundingBox().expand(maxDistance);
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;

        for (Entity e : client.world.getOtherEntities(client.player, searchBox, entity ->
                entity instanceof LivingEntity && !entity.isSpectator() && entity.isAlive()
        )) {
            LivingEntity le = (LivingEntity) e;
            Vec3d toEntity = le.getBoundingBox().getCenter().subtract(eyePos);
            double dist = toEntity.length();
            if (dist < 0.1 || dist > maxDistance) continue;

            Vec3d dirToEntity = toEntity.normalize();
            double dot = lookDir.dotProduct(dirToEntity);
            if (dot < minDot) continue; // outside the cone

            // Prefer the closest valid entity in the cone
            if (dist < bestDistance) {
                bestDistance = dist;
                best = le;
            }
        }

        return best;
    }

    /* ================== HUD OVERLAY ================== */

    private void registerHudOverlay() {
        HudRenderCallback.EVENT.register((DrawContext context, float tickDelta) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) return;

            long now = System.currentTimeMillis();
            int x = 10;
            int y = 10;

            for (var entry : cooldownEnd.entrySet()) {
                SkillKey key = entry.getKey();
                long end = entry.getValue();
                long remainingMs = end - now;

                String text;
                int color;

                if (remainingMs <= 0) {
                    text = formatSkillKey(key) + ": READY";
                    color = 0x00FF00;
                } else {
                    long seconds = (remainingMs + 999) / 1000;
                    text = formatSkillKey(key) + ": " + seconds + "s";
                    color = 0xFFFFFF;
                }

                context.drawText(client.textRenderer, text, x, y, color, true);
                y += 10;
            }
        });
    }

    private static String formatSkillKey(SkillKey key) {
        String tool = key.toolGroup() == null ? "" : " " + key.toolGroup().toUpperCase();
        return key.skill().name() + tool + " (" + key.tier().name() + ")";
    }
}
