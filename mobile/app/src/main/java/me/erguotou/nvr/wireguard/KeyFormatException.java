package me.erguotou.nvr.wireguard;

/**
 * An exception thrown when attempting to parse an invalid key.
 * Imported and adapted from WireGuard Android project (Apache-2.0).
 */
public final class KeyFormatException extends Exception {
    private final Key.Format format;
    private final Type type;

    KeyFormatException(final Key.Format format, final Type type) {
        this.format = format;
        this.type = type;
    }

    public Key.Format getFormat() {
        return format;
    }

    public Type getType() {
        return type;
    }

    public enum Type {
        CONTENTS,
        LENGTH
    }
}
