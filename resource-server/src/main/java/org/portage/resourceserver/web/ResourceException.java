package org.portage.resourceserver.web;

public final class ResourceException extends RuntimeException {
    private final String code;

    public ResourceException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
