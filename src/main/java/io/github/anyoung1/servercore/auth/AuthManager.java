package io.github.anyoung1.servercore.auth;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AuthManager {

    private static final Map<UUID, AuthSession> SESSIONS =
            new ConcurrentHashMap<>();

    private AuthManager() {
    }

    public static AuthSession createSession(
            UUID playerUuid,
            String username,
            boolean registered,
            double x,
            double y,
            double z
    ) {
        AuthState initialState = registered
                ? AuthState.NEEDS_LOGIN
                : AuthState.NEEDS_REGISTER;

        AuthSession session = new AuthSession(
                playerUuid,
                username,
                initialState,
                x,
                y,
                z
        );

        SESSIONS.put(playerUuid, session);

        return session;
    }

    public static AuthSession getSession(UUID playerUuid) {
        return SESSIONS.get(playerUuid);
    }

    public static boolean isAuthenticated(UUID playerUuid) {
        AuthSession session = SESSIONS.get(playerUuid);

        return session != null && session.isAuthenticated();
    }

    public static void authenticate(UUID playerUuid) {
        AuthSession session = SESSIONS.get(playerUuid);

        if (session != null) {
            session.authenticate();
        }
    }

    public static void removeSession(UUID playerUuid) {
        SESSIONS.remove(playerUuid);
    }
}