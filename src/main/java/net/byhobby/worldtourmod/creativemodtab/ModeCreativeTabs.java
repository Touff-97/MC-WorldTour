package net.byhobby.worldtourmod.creativemodtab;

import net.byhobby.worldtourmod.WorldTourMod;
import net.byhobby.worldtourmod.item.ModItems;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

public class ModeCreativeTabs {
    public static final CreativeModeTab TOUR_ITEM_TAB = Registry.register(
            BuiltInRegistries.CREATIVE_MODE_TAB,
            Identifier.fromNamespaceAndPath(WorldTourMod.MOD_ID, "tour_items"),
            FabricCreativeModeTab.builder()
                    .icon(() -> new ItemStack(ModItems.TOUR_WAND))
                    .title(Component.translatable("creativemodetab.worldtourmod.tour_items"))
                    .displayItems((parameters, output) -> {
                        output.accept(ModItems.TOUR_WAND);
                    })
                    .build()
        );
    public static final CreativeModeTab TOUR_BLOCK_TAB = Registry.register(
            BuiltInRegistries.CREATIVE_MODE_TAB,
            Identifier.fromNamespaceAndPath(WorldTourMod.MOD_ID, "tour_blocks"),
            FabricCreativeModeTab.builder()
                    .icon(() -> new ItemStack(ModItems.TEST_ITEM))
                    .title(Component.translatable("creativemodetab.worldtourmod.tour_blocks"))
                    .displayItems((parameters, output) -> {
                        output.accept(ModItems.TEST_ITEM);
                    })
                    .build()
    );


    public static void registerModCreativeModeTabs() {
        WorldTourMod.LOGGER.info("Registering Creative Mode Tabs for " + WorldTourMod.MOD_ID);
    }
}
