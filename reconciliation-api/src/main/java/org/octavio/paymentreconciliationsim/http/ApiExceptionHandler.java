package org.octavio.paymentreconciliationsim.http;

import jakarta.servlet.http.HttpServletRequest;
import org.octavio.paymentreconciliationsim.run.RecoveryService;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    public static final String CORRELATION_ID = ApiExceptionHandler.class.getName() + ".correlationId";

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handle(Exception failure, HttpServletRequest request) {
        int status = status(failure);
        ApiError body;
        if (failure instanceof RecoveryService.RecoveryConflict conflict) {
            body = new ApiError(conflict.getBody().getProperties().get("code").toString(),
                    conflict.getReason(), (String) request.getAttribute(CORRELATION_ID));
        } else {
            body = error(status, (String) request.getAttribute(CORRELATION_ID));
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static int status(Exception failure) {
        if (failure instanceof ErrorResponse error) return error.getStatusCode().value();
        if (failure instanceof HttpMessageNotReadableException || failure instanceof TypeMismatchException || failure instanceof IllegalArgumentException) return 400;
        return 500;
    }

    public static ApiError error(int status, String correlationId) {
        return switch (status) {
            case 400 -> new ApiError("INVALID_REQUEST", "The request is invalid", correlationId);
            case 401 -> new ApiError("UNAUTHORIZED", "A valid bearer token is required", correlationId);
            case 403 -> new ApiError("FORBIDDEN", "The bearer token cannot access this route", correlationId);
            case 404 -> new ApiError("NOT_FOUND", "The requested resource was not found", correlationId);
            case 405 -> new ApiError("METHOD_NOT_ALLOWED", "The request method is unsupported", correlationId);
            case 406 -> new ApiError("NOT_ACCEPTABLE", "The requested response format is unsupported", correlationId);
            case 409 -> new ApiError("CONFLICT", "The request conflicts with the current resource state", correlationId);
            case 413 -> new ApiError("PAYLOAD_TOO_LARGE", "The request payload exceeds the application limit", correlationId);
            case 415 -> new ApiError("UNSUPPORTED_MEDIA_TYPE", "The request content type is unsupported", correlationId);
            case 503 -> new ApiError("SERVICE_UNAVAILABLE", "The service is temporarily unavailable", correlationId);
            default -> new ApiError("INTERNAL_ERROR", "The request could not be completed", correlationId);
        };
    }
}
