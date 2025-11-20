package net.maxello.tutorialmod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;

import java.util.EnumMap;
import java.util.Map;

public class TutorialModClient implements ClientModInitializer {

    // 60 seconds
    private static final long COOLDOWN_MS = 60_000L;

    /** Categories that match your server’s XP actions */
    private enum XpCategory {
        LOGS_AXE,
        STONE_PICKAXE
        // You can easily add more later
    }

    /** When each category is ready again (system millis). */
    private static final Map<XpCategory, Long> cooldownEnd = new EnumMap<>(XpCategory.class);

    /** To only play sound once per cooldown completion. */
    private static final Map<XpCategory, Boolean> soundPlayed = new EnumMap<>(XpCategory.class);

    @Override
    public void onInitializeClient() {
        // This runs once when the client starts
        registerBlockBreakListener();
        registerTickListener();
        registerHudOverlay();
    }

    /* ---------- BLOCK BREAK LISTENER ---------- */

    private void registerBlockBreakListener() {
        ClientPlayerBlockBreakEvents.AFTER.register(TutorialModClient::onClientBlockBreak);
    }

    private static void onClientBlockBreak(ClientWorld world, ClientPlayerEntity player, BlockPos pos, BlockState state) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != player) return; // Just sanity check

        ItemStack held = player.getMainHandStack();
        XpCategory category = detectCategory(held, state);
        if (category == null) return;

        long now = System.currentTimeMillis();
        cooldownEnd.put(category, now + COOLDOWN_MS);
        soundPlayed.put(category, false);
    }

    /** Decide which XP timer this block break belongs to. */
    private static XpCategory detectCategory(ItemStack held, BlockState state) {
        // Axe + logs
        if (held.isIn(ItemTags.AXES) && state.isIn(BlockTags.LOGS)) {
            return XpCategory.LOGS_AXE;
        }

        // Pickaxe + stone-ish stuff
        if (held.isIn(ItemTags.PICKAXES) && state.isIn(BlockTags.PICKAXE_MINEABLE)) {
            return XpCategory.STONE_PICKAXE;
        }

        return null;
    }

    /* ---------- TICK LISTENER ---------- */

    private void registerTickListener() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            long now = System.currentTimeMillis();

            for (XpCategory cat : XpCategory.values()) {
                Long end = cooldownEnd.get(cat);
                if (end == null) continue;

                long remainingMs = end - now;
                if (remainingMs <= 0 && !soundPlayed.getOrDefault(cat, false)) {
                    // Cooldown finished -> Optional sound ONCE
                    client.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 1.0f);
                    soundPlayed.put(cat, true);
                }
            }
        });
    }

    /* ---------- HUD OVERLAY ---------- */

    private void registerHudOverlay() {
        HudRenderCallback.EVENT.register((DrawContext context, float tickDelta) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) return;

            long now = System.currentTimeMillis();

            int x = 10;
            int y = 10;

            for (XpCategory cat : XpCategory.values()) {
                Long end = cooldownEnd.get(cat);
                if (end == null) continue;

                long remainingMs = end - now;

                String text;
                int color;
                if (remainingMs <= 0) {
                    text = formatCategory(cat) + ": READY";
                    color = 0x00FF00; // green
                } else {
                    long seconds = (remainingMs + 999) / 1000;
                    text = formatCategory(cat) + ": " + seconds + "s";
                    color = 0xFFFFFF; // white
                }

                context.drawText(client.textRenderer, text, x, y, color, true);
                y += 10; // move down for next line
            }
        });
    }

    private static String formatCategory(XpCategory cat) {
        return switch (cat) {
            case LOGS_AXE -> "Logs (Axe)";
            case STONE_PICKAXE -> "Stone (Pickaxe)";
        };
    }
}