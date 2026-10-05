package io.github.anyoung1.servercore.auth;

import java.util.UUID;

public final class AuthSession {

    private final UUID playerUuid;
    private final String username;

    private final double lockX;
    private final double lockY;
    private final double lockZ;

    private AuthState state;

    public AuthSession(
            UUID playerUuid,
            String username,
            AuthState state,
            double lockX,
            double lockY,
            double lockZ
    ) {
        this.playerUuid = playerUuid;
        this.username = username;
        this.state = state;

        this.lockX = lockX;
        this.lockY = lockY;
        this.lockZ = lockZ;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getUsername() {
        return username;
    }

    public AuthState getState() {
        return state;
    }

    public boolean isAuthenticated() {
        return state == AuthState.AUTHENTICATED;
    }

    public void authenticate() {
        state = AuthState.AUTHENTICATED;
    }

    public double getLockX() {
        return lockX;
    }

    public double getLockY() {
        return lockY;
    }

    public double getLockZ() {
        return lockZ;
    }
}