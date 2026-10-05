package io.github.anyoung1.servercore;

import io.github.anyoung1.servercore.auth.AuthEvents;
import io.github.anyoung1.servercore.command.HelloCommand;
import io.github.anyoung1.servercore.command.LoginCommand;
import io.github.anyoung1.servercore.command.RegisterCommand;
import io.github.anyoung1.servercore.protection.BlockProtection;
import io.github.anyoung1.servercore.protection.CommunicationProtection;
import io.github.anyoung1.servercore.protection.EntityProtection;
import io.github.anyoung1.servercore.storage.AccountStorage;
import net.fabricmc.api.DedicatedServerModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerCore implements DedicatedServerModInitializer {

	public static final String MOD_ID = "server-core";

	public static final Logger LOGGER =
			LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeServer() {

		LOGGER.info("Server Core is starting!");

		AccountStorage.initialize();

		AuthEvents.register();

		HelloCommand.register();
		RegisterCommand.register();
		LoginCommand.register();

		BlockProtection.register();
		EntityProtection.register();
		CommunicationProtection.register();

		LOGGER.info("Server Core started successfully!");
	}
}

