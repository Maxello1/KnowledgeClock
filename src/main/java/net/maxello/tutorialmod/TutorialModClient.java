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
import net.minecraft.item.ArmorItem;
import net.minecraft.item.AxeItem;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;

public class TutorialModClient implements ClientModInitializer {

    // 60 seconds per skill/tool
    private static final long COOLDOWN_MS = 60_000L;

    // Bow / crossbow detection
    private static final double MAX_RANGED_DISTANCE = 20.0D;
    private static final double MAX_RANGED_ANGLE_DEGREES = 12.0D;

    // Toasts
    private static final long TOAST_DURATION_MS = 2500L;

    private static boolean lastAttackPressed = false;
    private static boolean lastUsePressed = false;

    private static boolean wasUsingBow = false;
    private static long bowUseStartTick = 0L;

    private static boolean crossbowPrimed = false;

    private static long tickCounter = 0L;

    // Snapshot of last inventory state for crafting detection
    private static ItemStack[] lastInventory = null;

    private enum Skill {
        MELEE_COMBAT,
        DIGGING,
        FORESTRY,
        FARMING,
        MINING,
        RANGED_COMBAT,
        TOOLSMITHING,
        WEAPONSMITHING,
        ARMOURING
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

    // Skill key for cooldowns & logic (NO icon here)
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

        startCooldown(key, held);
    }

    /* ================== TICK LOGIC ================== */

    private void registerTickListener() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            tickCounter++;
            long now = System.currentTimeMillis();

            // Handle cooldown expiry + sound + toast
            for (var entry : cooldownEnd.entrySet()) {
                SkillKey key = entry.getKey();
                long end = entry.getValue();

                if (end <= now && !soundPlayed.getOrDefault(key, false)) {
                    // Play a small "ready" sound
                    client.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 1.0f);

                    // Show custom toast popup
                    showReadyToast(key);

                    soundPlayed.put(key, true);
                }
            }

            // Melee attack edge
            boolean attackNow = client.options.attackKey.isPressed();
            if (attackNow && !lastAttackPressed) {
                handleMeleeAttack(client);
            }
            lastAttackPressed = attackNow;

            // Crossbow two-click
            boolean useNow = client.options.useKey.isPressed();
            if (useNow && !lastUsePressed) {
                handleUseKeyPressed(client);
            }
            lastUsePressed = useNow;

            // Bow draw + release
            handleBowUsageTick(client);

            // Crafting-based smithing detection
            handleCraftingGains(client);
        });
    }

    /* ================== MELEE ================== */

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
        startCooldown(key, held);
    }

    /* ================== CROSSBOW TWO-CLICK SYSTEM ================== */

    private static void handleUseKeyPressed(MinecraftClient client) {
        if (client.player == null) return;
        ItemStack held = client.player.getMainHandStack();

        if (!(held.getItem() instanceof CrossbowItem)) {
            crossbowPrimed = false;
            return;
        }

        // First click = charge (NO cooldown here)
        if (!crossbowPrimed) {
            crossbowPrimed = true;
            return;
        }

        // Second click = shot (if aimed at something)
        LivingEntity target = getAimedLivingEntity(client, MAX_RANGED_DISTANCE, MAX_RANGED_ANGLE_DEGREES);
        if (target == null) {
            crossbowPrimed = false;
            return;
        }

        Tier tier = getToolTier(held);
        if (tier != null) {
            SkillKey key = new SkillKey(Skill.RANGED_COMBAT, tier, "crossbow");
            startCooldown(key, held);
        }

        crossbowPrimed = false;
    }

    /* ================== BOW DRAW + RELEASE ================== */

    private static void handleBowUsageTick(MinecraftClient client) {
        if (client.player == null) {
            wasUsingBow = false;
            return;
        }

        ItemStack active = client.player.getActiveItem();
        boolean isUsingBowNow = !active.isEmpty() && active.getItem() instanceof BowItem;

        // Started drawing
        if (!wasUsingBow && isUsingBowNow) {
            bowUseStartTick = tickCounter;
        }

        // Released
        if (wasUsingBow && !isUsingBowNow) {
            long usedTicks = tickCounter - bowUseStartTick;
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
        startCooldown(key, held);
    }

    /* ================== CRAFTING → SMITHING DETECTION ================== */

    /**
     * Detects when the player actually RECEIVES an item (inventory count goes up)
     * while a GUI is open, and if that item is a tool/weapon/armor, starts the appropriate smithing timer.
     */
    private static void handleCraftingGains(MinecraftClient client) {
        if (client.player == null) return;

        var inv = client.player.getInventory();
        int size = inv.size(); // main + armor + offhand

        // Initialize inventory snapshot
        if (lastInventory == null || lastInventory.length != size) {
            lastInventory = new ItemStack[size];
            for (int i = 0; i < size; i++) {
                lastInventory[i] = inv.getStack(i).copy();
            }
            return;
        }

        // Only consider gains while some GUI is open (inventory, crafting table, anvil, smithing table, etc.)
        boolean inGui = client.currentScreen != null;
        if (!inGui) {
            // Just update snapshot and bail
            for (int i = 0; i < size; i++) {
                lastInventory[i] = inv.getStack(i).copy();
            }
            return;
        }

        // Look for any slot where count increased
        for (int i = 0; i < size; i++) {
            ItemStack oldStack = lastInventory[i];
            ItemStack newStack = inv.getStack(i);

            int oldCount = oldStack.isEmpty() ? 0 : oldStack.getCount();
            int newCount = newStack.isEmpty() ? 0 : newStack.getCount();

            if (newCount > oldCount) {
                // Player gained items in this slot (could be craft result)
                ItemStack gained = newStack.copy();

                SkillKey smithKey = detectSmithingSkillFromItem(gained);
                if (smithKey != null) {
                    startCooldown(smithKey, gained);
                }

                // Only handle one gain per tick to avoid spam
                break;
            }
        }

        // Refresh snapshot
        for (int i = 0; i < size; i++) {
            lastInventory[i] = inv.getStack(i).copy();
        }
    }

    /**
     * Classify a crafted item into TOOLSMITHING / WEAPONSMITHING / ARMOURING based on what it is.
     */
    private static SkillKey detectSmithingSkillFromItem(ItemStack stack) {
        if (stack.isEmpty()) return null;

        Tier tier = getToolTier(stack);
        if (tier == null) return null;

        Item item = stack.getItem();

        // Armour (helmet, chestplate, leggings, boots, etc.)
        if (item instanceof ArmorItem) {
            return new SkillKey(Skill.ARMOURING, tier, null);
        }

        // Weapons: swords, bows, crossbows, etc.
        boolean isSwordLike = stack.isIn(ItemTags.SWORDS) || item instanceof SwordItem;
        boolean isBowLike = item instanceof BowItem || item instanceof CrossbowItem;
        if (isSwordLike || isBowLike) {
            return new SkillKey(Skill.WEAPONSMITHING, tier, null);
        }

        // Tools: pickaxe, axe, shovel, hoe (could expand later)
        boolean isTool =
                stack.isIn(ItemTags.PICKAXES) ||
                        stack.isIn(ItemTags.AXES) ||
                        stack.isIn(ItemTags.SHOVELS) ||
                        stack.isIn(ItemTags.HOES);

        if (isTool) {
            return new SkillKey(Skill.TOOLSMITHING, tier, null);
        }

        return null;
    }

    /* ================== COOLDOWNS ================== */

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

    /* ================== BLOCK → SKILL ================== */

    private static SkillKey detectSkillKeyFromBlock(ItemStack held, BlockState state) {
        if (held.isEmpty()) return null;

        Tier tier = getToolTier(held);
        if (tier == null) return null;

        if (held.isIn(ItemTags.AXES) && state.isIn(BlockTags.LOGS)) {
            return new SkillKey(Skill.FORESTRY, tier, null);
        }

        if (held.isIn(ItemTags.PICKAXES) && state.isIn(BlockTags.PICKAXE_MINEABLE)) {
            return new SkillKey(Skill.MINING, tier, null);
        }

        if (held.isIn(ItemTags.SHOVELS) && state.isIn(BlockTags.SHOVEL_MINEABLE)) {
            return new SkillKey(Skill.DIGGING, tier, null);
        }

        if (held.isIn(ItemTags.HOES) && state.getBlock() instanceof CropBlock) {
            return new SkillKey(Skill.FARMING, tier, null);
        }

        return null;
    }

    /* ================== ITEM → TIER ================== */

    private static Tier getToolTier(ItemStack held) {
        String id = held.getItem().toString().toLowerCase();

        if (id.contains("wooden"))    return Tier.WOOD;
        if (id.contains("stone"))     return Tier.STONE;
        if (id.contains("golden") || id.contains("copper")) return Tier.COPPER;
        if (id.contains("iron"))      return Tier.IRON;
        if (id.contains("diamond"))   return Tier.DIAMOND;
        if (id.contains("leather"))   return Tier.LEATHER;
        if (id.contains("chainmail")) return Tier.CHAINMAIL;

        if (id.contains("bow") || id.contains("crossbow"))
            return Tier.WOOD;

        return null;
    }

    /* ================== AIM-CONE ENTITY PICK ================== */

    private static LivingEntity getAimedLivingEntity(MinecraftClient client, double maxDistance, double maxAngleDeg) {
        if (client.player == null || client.world == null) return null;

        Vec3d eyePos = client.player.getCameraPosVec(1.0f);
        Vec3d lookDir = client.player.getRotationVec(1.0f).normalize();

        double maxAngleRad = Math.toRadians(maxAngleDeg);
        double minDot = Math.cos(maxAngleRad);

        Box searchBox = client.player.getBoundingBox().expand(maxDistance);
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;

        for (Entity e : client.world.getOtherEntities(client.player, searchBox,
                entity -> entity instanceof LivingEntity && entity.isAlive())) {

            LivingEntity le = (LivingEntity) e;
            Vec3d toEntity = le.getBoundingBox().getCenter().subtract(eyePos);

            double dist = toEntity.length();
            if (dist < 0.1 || dist > maxDistance) continue;

            Vec3d dirToEntity = toEntity.normalize();
            if (lookDir.dotProduct(dirToEntity) < minDot) continue;

            if (dist < bestDistance) {
                bestDistance = dist;
                best = le;
            }
        }

        return best;
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

                // Tool icon
                context.drawItem(icon, tx + 4, ty + 4);

                String skillName = key.skill().name().replace("_", " ");
                String tierName = key.tier().name().toLowerCase();

                Text title = Text.literal(skillName + " READY");
                Text subtitle = Text.literal("(" + tierName + ")");

                context.drawText(client.textRenderer, title, tx + 24, ty + 5, 0xFFFFFF, false);
                context.drawText(client.textRenderer, subtitle, tx + 24, ty + 14, 0xAAAAAA, false);

                index++;
            }
        });
    }
}
