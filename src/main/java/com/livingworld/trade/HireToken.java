package com.livingworld.trade;

import com.solegendary.reignofnether.faction.Faction;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.List;

/**
 * A hire token — an {@link Items#ECHO_SHARD} with Living World NBT that,
 * when right-clicked, spawns the described unit owned by the player.
 *
 * <p>NBT layout (stored under {@code "lw_hire"} compound):
 * <pre>
 *   lw_hire: {
 *     faction:   "VILLAGERS"     (Faction.name())
 *     unit_type: "villager_unit" (entity registry name, passed to EntityRegistrar.getEntityType)
 *     label:     "Villager Worker" (display suffix)
 *   }
 * </pre>
 */
public final class HireToken {

    private static final String NBT_KEY = "lw_hire";

    private HireToken() {}

    // ------------------------------------------------------------ creation

    /**
     * Build a hire token ItemStack for the given unit.
     *
     * @param faction      owning faction
     * @param unitType     EntityRegistrar key (e.g. {@code "villager_unit"})
     * @param label        human-readable unit name
     */
    public static ItemStack create(Faction faction, String unitType, String label) {
        ItemStack stack = new ItemStack(Items.ECHO_SHARD);

        // NBT payload
        CompoundTag hire = new CompoundTag();
        hire.putString("faction",   faction.name());
        hire.putString("unit_type", unitType);
        hire.putString("label",     label);
        stack.getOrCreateTag().put(NBT_KEY, hire);

        // Display name
        stack.setHoverName(Component.literal("Hire: " + label)
                .withStyle(Style.EMPTY.withColor(0xFFD700).withItalic(false)));

        // Lore tooltip
        stack.getOrCreateTagElement("display").put("Lore",
                new net.minecraft.nbt.ListTag() {{
                    net.minecraft.nbt.StringTag lore1 = net.minecraft.nbt.StringTag.valueOf(
                        Component.Serializer.toJson(
                            Component.literal("Right-click to deploy this unit.")
                                .withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withItalic(true))));
                    net.minecraft.nbt.StringTag lore2 = net.minecraft.nbt.StringTag.valueOf(
                        Component.Serializer.toJson(
                            Component.literal("Faction: " + faction.name().toLowerCase())
                                .withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY).withItalic(true))));
                    add(lore1);
                    add(lore2);
                }});

        return stack;
    }

    // ---------------------------------------------------------- inspection

    public static boolean isHireToken(ItemStack stack) {
        if (stack.isEmpty() || stack.getItem() != Items.ECHO_SHARD) return false;
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains(NBT_KEY);
    }

    @Nullable
    public static String getUnitType(ItemStack stack) {
        CompoundTag hire = getHireTag(stack);
        return hire == null ? null : hire.getString("unit_type");
    }

    @Nullable
    public static Faction getFaction(ItemStack stack) {
        CompoundTag hire = getHireTag(stack);
        if (hire == null) return null;
        try { return Faction.valueOf(hire.getString("faction")); }
        catch (IllegalArgumentException e) { return null; }
    }

    @Nullable
    public static String getLabel(ItemStack stack) {
        CompoundTag hire = getHireTag(stack);
        return hire == null ? null : hire.getString("label");
    }

    @Nullable
    private static CompoundTag getHireTag(ItemStack stack) {
        if (!isHireToken(stack)) return null;
        CompoundTag tag = stack.getTag();
        return tag == null ? null : tag.getCompound(NBT_KEY);
    }
}
