package org.portage.authorizationserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "platform")
public record UserProperties(List<User> users) {
    public record User(String username, String password, String subject) {
    }
}
