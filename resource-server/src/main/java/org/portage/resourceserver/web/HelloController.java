package org.portage.resourceserver.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {
    @GetMapping("/hello")
    public HelloResponse hello(@AuthenticationPrincipal Jwt jwt) {
        return new HelloResponse("HelloWorld", jwt.getSubject());
    }
}
