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
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;

public class TutorialModClient implements ClientModInitializer {

    // 60 seconds cooldown per skill+tier, in ms
    private static final long COOLDOWN_MS = 60_000L;

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

        long now = System.currentTimeMillis();

        // Only start a new cooldown if there is none yet or it has expired.
        Long existingEnd = cooldownEnd.get(skillTier);
        if (existingEnd == null || existingEnd <= now) {
            cooldownEnd.put(skillTier, now + COOLDOWN_MS);
            soundPlayed.put(skillTier, false);
        }
    }

    /**
     * Map a block break (held item + block) to a (Skill, Tier) pair.
     * This is where we match your server's knowledge categories.
     */
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

        // Later we can add: melee/ranged combat, crafting skills, etc.

        return null; // Not a skill/tier we track with block breaks
    }

    /**
     * Read tier from item ID.
     * Works for vanilla tools/armour and your server’s “copper = golden” setup.
     */
    private static Tier getToolTier(ItemStack held) {
        String id = held.getItem().toString().toLowerCase();

        if (id.contains("wooden"))    return Tier.WOOD;
        if (id.contains("stone"))     return Tier.STONE;

        // Server uses golden tools as "copper" tools (retextured)
        if (id.contains("golden"))    return Tier.COPPER;

        // If they ever add real copper_* items
        if (id.contains("copper"))    return Tier.COPPER;

        if (id.contains("iron"))      return Tier.IRON;
        if (id.contains("diamond"))   return Tier.DIAMOND;

        if (id.contains("leather"))   return Tier.LEATHER;
        if (id.contains("chainmail")) return Tier.CHAINMAIL;

        // If nothing matches, we don't know the tier
        return null;
    }

    /* ================== TICK LISTENER (SOUND) ================== */

    private void registerTickListener() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            long now = System.currentTimeMillis();

            for (var entry : cooldownEnd.entrySet()) {
                SkillTier key = entry.getKey();
                long end = entry.getValue();

                long remainingMs = end - now;
                if (remainingMs <= 0 && !soundPlayed.getOrDefault(key, false)) {
                    // Cooldown finished → play "ready" sound once (optional)
                    client.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 1.0f);
                    soundPlayed.put(key, true);
                }
            }
        });
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