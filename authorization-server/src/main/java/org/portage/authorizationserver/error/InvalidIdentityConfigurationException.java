package org.portage.authorizationserver.error;

/**
 * Startup configuration violates an identity or credential invariant.
 */
public final class InvalidIdentityConfigurationException extends IllegalArgumentException {
    public InvalidIdentityConfigurationException(String message) {
        super(message);
    }
}
