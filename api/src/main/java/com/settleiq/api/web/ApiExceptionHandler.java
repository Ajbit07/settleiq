package com.settleiq.api.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * RFC 7807 problem responses.
 *
 * Client mistakes get a specific 4xx and a usable message. Anything unexpected
 * gets a 500 with a correlation id and NO internal detail: a stack trace on a
 * finance API is an information leak, and the operator can find the trace by id.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        pd.setTitle("Invalid request");
        pd.setType(URI.create("https://settleiq/errors/invalid-request"));
        return pd;
    }

    @ExceptionHandler(com.settleiq.api.config.TenantGuard.Forbidden.class)
    public ProblemDetail forbidden(com.settleiq.api.config.TenantGuard.Forbidden e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
        pd.setTitle("Forbidden");
        pd.setType(URI.create("https://settleiq/errors/forbidden"));
        return pd;
    }

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail notFound(NotFoundException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        pd.setTitle("Not found");
        pd.setType(URI.create("https://settleiq/errors/not-found"));
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail internal(Exception e) {
        String correlationId = java.util.UUID.randomUUID().toString();
        log.error("unhandled error correlation_id={}", correlationId, e);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Unexpected error. Quote correlation id " + correlationId + " to support.");
        pd.setTitle("Internal error");
        pd.setProperty("correlation_id", correlationId);
        return pd;
    }
}
