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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.hit.EntityHitResult;

import java.util.HashMap;
import java.util.Map;

public class TutorialModClient implements ClientModInitializer {

    // 60 seconds cooldown per skill+tier, in ms
    private static final long COOLDOWN_MS = 60_000L;

    // Track last attack key state (for melee)
    private static boolean lastAttackPressed = false;

    // Track ranged-weapon usage to approximate arrow shot moment
    private static boolean wasUsingRanged = false;
    private static long rangedUseStartTick = 0L;
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

    // Generic tiers. For armour we just reuse these names (LEATHER/CHAINMAIL)
    private enum Tier {
        WOOD,
        STONE,
        COPPER,
        IRON,
        DIAMOND,
        LEATHER,
        CHAINMAIL
    }

    // One cooldown entry = one Skill + one Tier
    private record SkillTier(Skill skill, Tier tier) {}

    // When each skill+tier is ready again (system millis)
    private static final Map<SkillTier, Long> cooldownEnd = new HashMap<>();

    // To only play the "ready" sound once
    private static final Map<SkillTier, Boolean> soundPlayed = new HashMap<>();

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
        if (mc.player != player) return; // just sanity

        ItemStack held = player.getMainHandStack();
        SkillTier skillTier = detectSkillTier(held, state);
        if (skillTier == null) return;

        startCooldown(skillTier);
    }

    /* ================== MELEE & RANGED VIA TICK ================== */

    private void registerTickListener() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            tickCounter++;
            long now = System.currentTimeMillis();

            // --- 1) Handle cooldown expiry + sounds ---
            for (var entry : cooldownEnd.entrySet()) {
                SkillTier key = entry.getKey();
                long end = entry.getValue();

                if (end <= now && !soundPlayed.getOrDefault(key, false)) {
                    client.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 1.0f);
                    soundPlayed.put(key, true);
                }
            }

            // --- 2) Detect melee attack (left click edge) ---
            boolean attackNow = client.options.attackKey.isPressed();
            if (attackNow && !lastAttackPressed) {
                handleMeleeAttack(client);
            }
            lastAttackPressed = attackNow;

            // --- 3) Track ranged weapon usage to detect "shot" on release ---
            handleRangedUsageTick(client);
        });
    }

    private static void handleMeleeAttack(MinecraftClient client) {
        if (client.player == null) return;

        ItemStack held = client.player.getMainHandStack();
        Tier tier = getToolTier(held);
        if (tier == null) return;

        // What counts as melee weapon
        boolean isMelee =
                held.isIn(ItemTags.SWORDS) ||
                        held.isIn(ItemTags.AXES) ||
                        held.getItem() instanceof SwordItem ||
                        held.getItem() instanceof AxeItem;

        if (!isMelee) return;

        // Only if our crosshair is actually on an entity
        if (!(client.crosshairTarget instanceof EntityHitResult ehr)) return;
        Entity target = ehr.getEntity();
        if (!(target instanceof LivingEntity)) return; // only living things give melee XP

        SkillTier st = new SkillTier(Skill.MELEE_COMBAT, tier);
        startCooldown(st);
    }

    /**
     * Track when a bow/crossbow is being drawn and released.
     * We approximate the arrow shot as: "player stopped using a ranged weapon after
     * holding it for at least a few ticks".
     */
    private static void handleRangedUsageTick(MinecraftClient client) {
        if (client.player == null) {
            wasUsingRanged = false;
            return;
        }

        // Is the player currently using a ranged weapon (bow/crossbow/etc.)?
        ItemStack active = client.player.getActiveItem();
        boolean isUsingRangedNow = !active.isEmpty() &&
                (active.getItem() instanceof BowItem ||
                        active.getItem() instanceof CrossbowItem ||
                        active.getItem() instanceof RangedWeaponItem);

        // Just started drawing the bow/crossbow
        if (!wasUsingRanged && isUsingRangedNow) {
            rangedUseStartTick = tickCounter;
        }

        // Just released the bow/crossbow
        if (wasUsingRanged && !isUsingRangedNow) {
            long usedTicks = tickCounter - rangedUseStartTick;

            // Only treat as a "shot" if drawn for at least a short time
            if (usedTicks >= 5) { // ~5 ticks threshold, tweak if needed
                handleRangedShot(client);
            }
        }

        wasUsingRanged = isUsingRangedNow;
    }

    /**
     * Called when we think an arrow/bolt has actually been fired.
     * Requires: holding a ranged weapon, decent draw time, and crosshair on a living entity.
     */
    private static void handleRangedShot(MinecraftClient client) {
        if (client.player == null) return;

        ItemStack held = client.player.getMainHandStack();
        Tier tier = getToolTier(held);
        if (tier == null) return;

        boolean isRanged =
                held.getItem() instanceof BowItem ||
                        held.getItem() instanceof CrossbowItem ||
                        held.getItem() instanceof RangedWeaponItem;

        if (!isRanged) return;

        // Only start timer if we are actually aiming at a living entity when the shot is released
        if (!(client.crosshairTarget instanceof EntityHitResult ehr)) return;
        Entity target = ehr.getEntity();
        if (!(target instanceof LivingEntity)) return;

        SkillTier st = new SkillTier(Skill.RANGED_COMBAT, tier);
        startCooldown(st);
    }

    /* ================== COOLDOWN HANDLING ================== */

    private static void startCooldown(SkillTier st) {
        long now = System.currentTimeMillis();
        Long existingEnd = cooldownEnd.get(st);

        // Only start a new cooldown if there is none yet or it has expired.
        if (existingEnd == null || existingEnd <= now) {
            cooldownEnd.put(st, now + COOLDOWN_MS);
            soundPlayed.put(st, false);
        }
    }

    /* ================== BLOCK → SKILL LOGIC ================== */

    private static SkillTier detectSkillTier(ItemStack held, BlockState state) {
        if (held.isEmpty()) return null;

        Tier tier = getToolTier(held);
        if (tier == null) return null;

        // Forestry: logs + axe
        if (held.isIn(ItemTags.AXES) && state.isIn(BlockTags.LOGS)) {
            return new SkillTier(Skill.FORESTRY, tier);
        }

        // Mining: anything mineable with pickaxe (stone/ores/metal blocks etc.)
        if (held.isIn(ItemTags.PICKAXES) && state.isIn(BlockTags.PICKAXE_MINEABLE)) {
            return new SkillTier(Skill.MINING, tier);
        }

        // Digging: shovel blocks (dirt, sand, gravel, etc.)
        if (held.isIn(ItemTags.SHOVELS) && state.isIn(BlockTags.SHOVEL_MINEABLE)) {
            return new SkillTier(Skill.DIGGING, tier);
        }

        // Farming: breaking crops while holding a hoe
        if (held.isIn(ItemTags.HOES) && state.getBlock() instanceof CropBlock) {
            return new SkillTier(Skill.FARMING, tier);
        }

        return null; // Not a skill/tier we track with block breaks
    }

    /* ================== ITEM → TIER LOGIC ================== */

    private static Tier getToolTier(ItemStack held) {
        String id = held.getItem().toString().toLowerCase();

        // Tool / armor tiers
        if (id.contains("wooden"))    return Tier.WOOD;
        if (id.contains("stone"))     return Tier.STONE;

        // Server uses golden tools as "copper" tools (retextured)
        if (id.contains("golden"))    return Tier.COPPER;
        if (id.contains("copper"))    return Tier.COPPER;

        if (id.contains("iron"))      return Tier.IRON;
        if (id.contains("diamond"))   return Tier.DIAMOND;

        if (id.contains("leather"))   return Tier.LEATHER;
        if (id.contains("chainmail")) return Tier.CHAINMAIL;

        // Ranged-only items: bow / crossbow / custom bows
        // If they don't encode tier in the ID, treat as WOOD by default.
        if (id.contains("bow") || id.contains("crossbow")) {
            return Tier.WOOD;
        }

        // If nothing matches, we don't know the tier
        return null;
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
                SkillTier key = entry.getKey();
                long end = entry.getValue();
                long remainingMs = end - now;

                String text;
                int color;

                if (remainingMs <= 0) {
                    text = formatSkillTier(key) + ": READY";
                    color = 0x00FF00; // green
                } else {
                    long seconds = (remainingMs + 999) / 1000; // round up
                    text = formatSkillTier(key) + ": " + seconds + "s";
                    color = 0xFFFFFF; // white
                }

                context.drawText(client.textRenderer, text, x, y, color, true);
                y += 10;
            }
        });
    }

    private static String formatSkillTier(SkillTier st) {
        // e.g. "FORESTRY (COPPER)" – can prettify later if you like
        return st.skill().name() + " (" + st.tier().name() + ")";
    }
}