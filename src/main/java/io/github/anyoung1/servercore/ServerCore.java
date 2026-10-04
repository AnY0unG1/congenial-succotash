package io.github.anyoung1.servercore;

import io.github.anyoung1.servercore.command.HelloCommand;
import net.fabricmc.api.DedicatedServerModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerCore implements DedicatedServerModInitializer {

	public static final String MOD_ID = "server-core";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeServer() {
		LOGGER.info("Server Core is starting!");

		HelloCommand.register();

		LOGGER.info("Server Core started successfully!");
	}
}