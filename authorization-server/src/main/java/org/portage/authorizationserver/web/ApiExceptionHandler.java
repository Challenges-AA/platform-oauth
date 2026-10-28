package org.portage.authorizationserver.web;

import org.portage.authorizationserver.error.InvalidIdentityConfigurationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Sanitizes typed application errors should they cross the HTTP controller boundary.
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(InvalidIdentityConfigurationException.class)
    ProblemDetail handleInvalidConfiguration(InvalidIdentityConfigurationException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Authorization service configuration is invalid");
    }
}
