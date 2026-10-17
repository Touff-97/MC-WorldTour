package net.byhobby.worldtourmod;

import net.byhobby.worldtourmod.creativemodtab.ModeCreativeTabs;
import net.byhobby.worldtourmod.item.ModItems;
import net.byhobby.worldtourmod.item.custom.ComplexTestItem;
import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WorldTourMod implements ModInitializer {
	public static final String MOD_ID = "worldtourmod";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing " + WorldTourMod.MOD_ID + "...");

		ModeCreativeTabs.registerModCreativeModeTabs();
		ModItems.registerModItems();

		AttackBlockCallback.EVENT.register(ComplexTestItem::onAttackBlock);
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
