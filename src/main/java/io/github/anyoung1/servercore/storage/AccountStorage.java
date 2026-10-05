package io.github.anyoung1.servercore.storage;

import io.github.anyoung1.servercore.ServerCore;
import io.github.anyoung1.servercore.auth.Account;
import io.github.anyoung1.servercore.auth.PasswordHasher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

public final class AccountStorage {

    private static final Path DIRECTORY =
            Path.of("config", "server-core");

    private static final Path FILE =
            DIRECTORY.resolve("accounts.properties");

    private static final Properties ACCOUNTS =
            new Properties();

    private AccountStorage() {
    }

    public static synchronized void initialize() {
        try {
            Files.createDirectories(DIRECTORY);

            if (!Files.exists(FILE)) {
                ServerCore.LOGGER.info(
                        "No account database found. A new one will be created."
                );
                return;
            }

            try (InputStream input = Files.newInputStream(FILE)) {
                ACCOUNTS.load(input);
            }

            ServerCore.LOGGER.info(
                    "Loaded {} registered accounts.",
                    ACCOUNTS.size()
            );

        } catch (IOException e) {
            throw new IllegalStateException(
                    "Unable to load account database",
                    e
            );
        }
    }

    public static synchronized boolean exists(String username) {
        return ACCOUNTS.containsKey(normalize(username));
    }

    public static synchronized Optional<Account> find(String username) {
        String normalized = normalize(username);
        String value = ACCOUNTS.getProperty(normalized);

        if (value == null) {
            return Optional.empty();
        }

        String[] parts = value.split(":", 4);

        if (parts.length != 4) {
            ServerCore.LOGGER.error(
                    "Invalid account record for {}",
                    normalized
            );

            return Optional.empty();
        }

        try {
            int iterations = Integer.parseInt(parts[0]);
            String salt = parts[1];
            String hash = parts[2];
            long createdAt = Long.parseLong(parts[3]);

            return Optional.of(
                    new Account(
                            normalized,
                            iterations,
                            salt,
                            hash,
                            createdAt
                    )
            );

        } catch (NumberFormatException e) {
            ServerCore.LOGGER.error(
                    "Invalid account record for {}",
                    normalized
            );

            return Optional.empty();
        }
    }

    public static synchronized boolean register(
            String username,
            char[] password
    ) {
        String normalized = normalize(username);

        if (ACCOUNTS.containsKey(normalized)) {
            return false;
        }

        PasswordHasher.HashResult result =
                PasswordHasher.hash(password);

        long createdAt = System.currentTimeMillis();

        String value =
                result.iterations()
                        + ":"
                        + result.salt()
                        + ":"
                        + result.hash()
                        + ":"
                        + createdAt;

        ACCOUNTS.setProperty(normalized, value);

        save();

        ServerCore.LOGGER.info(
                "Registered account for {}",
                normalized
        );

        return true;
    }

    public static synchronized boolean verify(
            String username,
            char[] password
    ) {
        Optional<Account> optional = find(username);

        if (optional.isEmpty()) {
            return false;
        }

        Account account = optional.get();

        return PasswordHasher.verify(
                password,
                account.iterations(),
                account.salt(),
                account.passwordHash()
        );
    }

    private static void save() {
        try {
            Files.createDirectories(DIRECTORY);

            Path temporary =
                    DIRECTORY.resolve("accounts.properties.tmp");

            try (OutputStream output =
                         Files.newOutputStream(temporary)) {

                ACCOUNTS.store(
                        output,
                        "Server Core account database"
                );
            }

            try {
                Files.move(
                        temporary,
                        FILE,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (IOException atomicMoveFailed) {
                Files.move(
                        temporary,
                        FILE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }

        } catch (IOException e) {
            throw new IllegalStateException(
                    "Unable to save account database",
                    e
            );
        }
    }

    private static String normalize(String username) {
        return username.toLowerCase(Locale.ROOT);
    }
}