package net.maxello.tutorialmod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import java.util.*;

public class TutorialModClient implements ClientModInitializer {

    // 60 seconds per skill/tool
    private static final long COOLDOWN_MS = 60_000L;

    // Toasts
    private static final long TOAST_DURATION_MS = 2500L;

    private enum Skill {
        MELEE_COMBAT,
        DIGGING,
        FORESTRY,
        HUSBANDRY,
        MINING,
        RANGED_COMBAT,
        TOOLSMITHING,
        WEAPONSMITHING,
        ARMOURING,
        FISHING
    }

    private enum Tier {
        WOOD,
        STONE,
        COPPER,
        IRON,
        DIAMOND,
        LEATHER,
        CHAINMAIL
    }

    // Skill key for cooldowns & logic
    private record SkillKey(Skill skill, Tier tier, String toolGroup) {}

    // Toast storage: which skill became ready when
    private record SkillToast(SkillKey key, long startTimeMs) {}

    // Cooldown end time per skill key
    private static final Map<SkillKey, Long> cooldownEnd = new HashMap<>();
    // Ensure we only play "ready" sound + toast once per cooldown
    private static final Map<SkillKey, Boolean> soundPlayed = new HashMap<>();
    // Icon for each skill key
    private static final Map<SkillKey, ItemStack> keyIcons = new HashMap<>();
    // Active toast popups
    private static final List<SkillToast> activeToasts = new ArrayList<>();

    @Override
    public void onInitializeClient() {
        registerMessageListener();
        registerTickListener();
        registerHudOverlay();
    }

    /* ================== MESSAGE LISTENER (CHAT / ACTION BAR) ================== */

    private void registerMessageListener() {
        // Game / action-bar / system messages
        ClientReceiveMessageEvents.GAME.register((message, overlay) ->
                handleIncomingMessage(message.getString())
        );

        // Chat messages (fallback, in case server uses normal chat)
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, timestamp) ->
                handleIncomingMessage(message.getString())
        );
    }

    private static void handleIncomingMessage(String rawMessage) {
        if (rawMessage == null || rawMessage.isEmpty()) return;

        String msg = rawMessage.trim();
        String lower = msg.toLowerCase(Locale.ROOT);

        // Pattern(s) we care about:
        // "You're learning Forestry!"
        // "You're learning Wooden Forestry!"
        final String learningPrefix = "you're learning ";
        if (!lower.startsWith(learningPrefix)) {
            return;
        }

        // Slice off the prefix, keeping original casing for nicer parsing
        String after = msg.substring(learningPrefix.length()).trim();

        // Strip trailing '!' if present
        if (!after.isEmpty() && after.charAt(after.length() - 1) == '!') {
            after = after.substring(0, after.length() - 1).trim();
        }
        if (after.isEmpty()) return;

        // Try to split into [maybeTier] [skill words...]
        String[] parts = after.split(" ", 2);
        String firstWord = parts[0].trim();
        String rest = (parts.length > 1) ? parts[1].trim() : "";

        Tier tier = parseTierWord(firstWord);
        String skillPart;

        if (tier != null && !rest.isEmpty()) {
            // Format: "<Tier> <Skill...>" → e.g. "Wooden Forestry"
            skillPart = rest;
        } else {
            // Format: "<Skill...>" only → e.g. "Forestry"
            tier = Tier.WOOD; // Default tier when not given
            skillPart = after;
        }

        if (skillPart.isEmpty()) return;

        handleLearningMessage(skillPart, tier);
    }

    private static Tier parseTierWord(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        if (w.startsWith("wood"))    return Tier.WOOD;
        if (w.startsWith("stone"))   return Tier.STONE;
        if (w.startsWith("copper"))  return Tier.COPPER;
        if (w.startsWith("gold"))    return Tier.COPPER;   // in case server says "Golden"
        if (w.startsWith("iron"))    return Tier.IRON;
        if (w.startsWith("diamond")) return Tier.DIAMOND;
        if (w.startsWith("leather")) return Tier.LEATHER;
        if (w.startsWith("chain"))   return Tier.CHAINMAIL;
        return null;
    }

    private static void handleLearningMessage(String skillPart, Tier tier) {
        String s = skillPart.toLowerCase(Locale.ROOT);

        Skill skill = null;

        if (s.contains("forestry")) {
            skill = Skill.FORESTRY;
        } else if (s.contains("mining")) {
            skill = Skill.MINING;
        } else if (s.contains("digging")) {
            skill = Skill.DIGGING;
        } else if (s.contains("husbandry") || s.contains("farming")) {
            skill = Skill.HUSBANDRY;
        } else if (s.contains("melee")) {
            skill = Skill.MELEE_COMBAT;
        } else if (s.contains("ranged")) {
            skill = Skill.RANGED_COMBAT;
        } else if (s.contains("weapon") && s.contains("smith")) {
            skill = Skill.WEAPONSMITHING;
        } else if (s.contains("tool") && s.contains("smith")) {
            skill = Skill.TOOLSMITHING;
        } else if (s.contains("armour") || s.contains("armor")) {
            skill = Skill.ARMOURING;
        } else if (s.contains("fish")) {
            skill = Skill.FISHING;
        }

        if (skill == null) {
            // Unknown skill text -> ignore safely
            return;
        }

        // For smithing skills we collapse tier to WOOD so they share ONE cooldown
        Tier keyTier = tier;
        if (skill == Skill.WEAPONSMITHING || skill == Skill.TOOLSMITHING || skill == Skill.ARMOURING) {
            keyTier = Tier.WOOD;
        }

        SkillKey key = new SkillKey(skill, keyTier, null);
        ItemStack icon = getIconFor(skill, tier);

        startCooldown(key, icon);
    }

    /* ================== ICON SELECTION ================== */

    private static ItemStack getIconFor(Skill skill, Tier tier) {
        // Helper lambdas for tiered tools
        java.util.function.Function<Tier, ItemStack> axeByTier = t -> switch (t) {
            case STONE   -> new ItemStack(Items.STONE_AXE);
            case COPPER  -> new ItemStack(Items.GOLDEN_AXE);   // copper retexture
            case IRON    -> new ItemStack(Items.IRON_AXE);
            case DIAMOND -> new ItemStack(Items.DIAMOND_AXE);
            default      -> new ItemStack(Items.WOODEN_AXE);
        };

        java.util.function.Function<Tier, ItemStack> pickByTier = t -> switch (t) {
            case STONE   -> new ItemStack(Items.STONE_PICKAXE);
            case COPPER  -> new ItemStack(Items.GOLDEN_PICKAXE);
            case IRON    -> new ItemStack(Items.IRON_PICKAXE);
            case DIAMOND -> new ItemStack(Items.DIAMOND_PICKAXE);
            default      -> new ItemStack(Items.WOODEN_PICKAXE);
        };

        java.util.function.Function<Tier, ItemStack> shovelByTier = t -> switch (t) {
            case STONE   -> new ItemStack(Items.STONE_SHOVEL);
            case COPPER  -> new ItemStack(Items.GOLDEN_SHOVEL);
            case IRON    -> new ItemStack(Items.IRON_SHOVEL);
            case DIAMOND -> new ItemStack(Items.DIAMOND_SHOVEL);
            default      -> new ItemStack(Items.WOODEN_SHOVEL);
        };

        java.util.function.Function<Tier, ItemStack> hoeByTier = t -> switch (t) {
            case STONE   -> new ItemStack(Items.STONE_HOE);
            case COPPER  -> new ItemStack(Items.GOLDEN_HOE);
            case IRON    -> new ItemStack(Items.IRON_HOE);
            case DIAMOND -> new ItemStack(Items.DIAMOND_HOE);
            default      -> new ItemStack(Items.WOODEN_HOE);
        };

        java.util.function.Function<Tier, ItemStack> swordByTier = t -> switch (t) {
            case STONE   -> new ItemStack(Items.STONE_SWORD);
            case COPPER  -> new ItemStack(Items.GOLDEN_SWORD);
            case IRON    -> new ItemStack(Items.IRON_SWORD);
            case DIAMOND -> new ItemStack(Items.DIAMOND_SWORD);
            default      -> new ItemStack(Items.WOODEN_SWORD);
        };

        return switch (skill) {
            case FORESTRY       -> axeByTier.apply(tier);
            case MINING         -> pickByTier.apply(tier);
            case DIGGING        -> shovelByTier.apply(tier);
            case HUSBANDRY      -> hoeByTier.apply(tier);
            case MELEE_COMBAT   -> swordByTier.apply(tier);
            case RANGED_COMBAT  -> new ItemStack(Items.BOW);
            case TOOLSMITHING   -> new ItemStack(Items.IRON_PICKAXE);
            case WEAPONSMITHING -> new ItemStack(Items.IRON_SWORD);
            case ARMOURING      -> new ItemStack(Items.IRON_CHESTPLATE);
            case FISHING        -> new ItemStack(Items.FISHING_ROD);
        };
    }

    /* ================== COOLDOWNS + TICK ================== */

    private void registerTickListener() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            long now = System.currentTimeMillis();

            // Handle cooldown expiry + sound + toast
            for (var entry : cooldownEnd.entrySet()) {
                SkillKey key = entry.getKey();
                long end = entry.getValue();

                if (end <= now && !soundPlayed.getOrDefault(key, false)) {
                    client.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 1.0f);
                    showReadyToast(key);
                    soundPlayed.put(key, true);
                }
            }
        });
    }

    private static void startCooldown(SkillKey key, ItemStack iconSource) {
        long now = System.currentTimeMillis();
        Long existingEnd = cooldownEnd.get(key);

        // Only start a new cooldown if there isn't one already active
        if (existingEnd == null || existingEnd <= now) {
            cooldownEnd.put(key, now + COOLDOWN_MS);
            soundPlayed.put(key, false);
        }

        // Always update the icon to the latest tool state
        keyIcons.put(key, iconSource.copy());
    }

    /* ================== CUSTOM TOAST HANDLING ================== */

    private static void showReadyToast(SkillKey key) {
        activeToasts.add(new SkillToast(key, System.currentTimeMillis()));
    }

    /* ================== HUD OVERLAY ================== */

    private void registerHudOverlay() {
        HudRenderCallback.EVENT.register((DrawContext context, float tickDelta) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) return;

            long now = System.currentTimeMillis();

            // --- LEFT SIDE: ACTIVE COOLDOWNS AS ICON + "Xs" ---
            int x = 10;
            int y = 10;
            int iconSize = 16;
            int padding = 4;

            for (var entry : cooldownEnd.entrySet()) {
                SkillKey key = entry.getKey();
                long end = entry.getValue();
                long remainingMs = end - now;

                if (remainingMs <= 0) continue;

                long seconds = (remainingMs + 999) / 1000;

                ItemStack icon = keyIcons.getOrDefault(key, new ItemStack(Items.CLOCK));

                boolean isSmithing =
                        key.skill() == Skill.TOOLSMITHING ||
                                key.skill() == Skill.WEAPONSMITHING ||
                                key.skill() == Skill.ARMOURING;

                // Crafting-table-ish background for smithing skills
                if (isSmithing) {
                    int bgPad = 2;
                    int x1 = x - bgPad;
                    int y1 = y - bgPad;
                    int x2 = x + iconSize + bgPad;
                    int y2 = y + iconSize + bgPad;
                    // Dark brown base
                    context.fill(x1, y1, x2, y2, 0xFF3B200A);
                    // Lighter inner square
                    context.fill(x1 + 2, y1 + 2, x2 - 2, y2 - 2, 0xFF8B5A2B);
                }

                context.drawItem(icon, x, y);

                String text = seconds + "s";
                context.drawText(client.textRenderer, text, x + iconSize + 3, y + 4, 0xFFFFFF, true);

                y += iconSize + padding;
            }

            // --- TOP-RIGHT: CUSTOM "TOAST" POPUPS FOR READY SKILLS ---
            int screenWidth = client.getWindow().getScaledWidth();
            int toastWidth = 150;
            int toastHeight = 24;
            int baseX = screenWidth - toastWidth - 10;
            int baseY = 10;

            Iterator<SkillToast> it = activeToasts.iterator();
            int index = 0;

            while (it.hasNext()) {
                SkillToast toast = it.next();
                long age = now - toast.startTimeMs();
                if (age > TOAST_DURATION_MS) {
                    it.remove();
                    continue;
                }

                SkillKey key = toast.key();
                ItemStack icon = keyIcons.getOrDefault(key, new ItemStack(Items.CLOCK));

                int tx = baseX;
                int ty = baseY + index * (toastHeight + 4);

                // Simple background
                context.fill(tx, ty, tx + toastWidth, ty + toastHeight, 0xCC000000); // semi-transparent black

                // Border
                context.fill(tx, ty, tx + toastWidth, ty + 1, 0xFFFFFFFF);
                context.fill(tx, ty + toastHeight - 1, tx + toastWidth, ty + toastHeight, 0xFFFFFFFF);
                context.fill(tx, ty, tx + 1, ty + toastHeight, 0xFFFFFFFF);
                context.fill(tx + toastWidth - 1, ty, tx + toastWidth, ty + toastHeight, 0xFFFFFFFF);

                boolean isSmithing =
                        key.skill() == Skill.TOOLSMITHING ||
                                key.skill() == Skill.WEAPONSMITHING ||
                                key.skill() == Skill.ARMOURING;

                // Small crafting-table-ish background behind icon on toast
                if (isSmithing) {
                    int ix = tx + 4;
                    int iy = ty + 4;
                    int x1 = ix - 1;
                    int y1 = iy - 1;
                    int x2 = ix + iconSize + 1;
                    int y2 = iy + iconSize + 1;
                    context.fill(x1, y1, x2, y2, 0xFF3B200A);
                    context.fill(x1 + 2, y1 + 2, x2 - 2, y2 - 2, 0xFF8B5A2B);
                }

                // Tool icon
                context.drawItem(icon, tx + 4, ty + 4);

                String skillName = key.skill().name().replace("_", " ");
                String tierName = key.tier().name().toLowerCase(Locale.ROOT);

                Text title = Text.literal(skillName + " READY");
                Text subtitle = Text.literal("(" + tierName + ")");

                context.drawText(client.textRenderer, title, tx + 24, ty + 5, 0xFFFFFF, false);
                context.drawText(client.textRenderer, subtitle, tx + 24, ty + 14, 0xAAAAAA, false);

                index++;
            }
        });
    }
}
