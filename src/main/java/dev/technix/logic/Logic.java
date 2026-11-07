package dev.technix.logic;

import dev.technix.logic.event.EventBus;
import net.fabricmc.api.ModInitializer;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class Logic implements ModInitializer {
	public static final String NAME = "logic";
	public static final String VERSION = version();
	private static String version() {
		try (var input = Logic.class.getResourceAsStream("/assets/logic/version.txt")) {
			return input == null ? "unknown" : new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
		} catch (java.io.IOException ignored) { return "unknown"; }
	}

	public static final Logger LOGGER = LoggerFactory.getLogger(NAME);

	private static final EventBus  EVENTS = new EventBus();
	public static EventBus events() { return EVENTS;}
	@Override
	public void onInitialize() {

		LOGGER.info("{} initialising", NAME);
	}
}


