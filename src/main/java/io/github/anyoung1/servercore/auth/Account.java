package io.github.anyoung1.servercore.auth;

public record Account(
        String username,
        int iterations,
        String salt,
        String passwordHash,
        long createdAt
) {
}