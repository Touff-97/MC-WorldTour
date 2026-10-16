package net.byhobby.worldtourmod.item;

import net.byhobby.worldtourmod.WorldTourMod;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import java.util.function.Function;

public class ModItems {
    // test item
    public static final Item TEST_ITEM = registerItem(
            "test_item",
            Item::new
    );

    private static Item registerItem(String name, Function<Item.Properties, Item> function) {
        return Registry.register(
            BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath(WorldTourMod.MOD_ID, name),
            function.apply(new Item.Properties().setId(ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(WorldTourMod.MOD_ID, name))))
        );
    }

    public static void registerModItems() {
        WorldTourMod.LOGGER.info("Registering Mod Items for " + WorldTourMod.MOD_ID);

        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.INGREDIENTS).register(output ->
            output.accept(TEST_ITEM)
        );
    }
}
