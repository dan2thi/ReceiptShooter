package com.dan.receiptshooter.exception;

import com.dan.receiptshooter.dto.ReceiptApi;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(AccountException.class)
    ResponseEntity<ReceiptApi.ErrorResponse> account(AccountException error) {
        return response(error.getStatus(), error.getMessage());
    }

    @ExceptionHandler(InvalidUploadException.class)
    ResponseEntity<ReceiptApi.ErrorResponse> invalid(InvalidUploadException error) {
        return response(HttpStatus.BAD_REQUEST, error.getMessage());
    }

    @ExceptionHandler({UploadTooLargeException.class, MaxUploadSizeExceededException.class})
    ResponseEntity<ReceiptApi.ErrorResponse> tooLarge(Exception error) {
        String message = error instanceof UploadTooLargeException ? error.getMessage() : "The upload exceeds the configured size limit.";
        return response(HttpStatus.PAYLOAD_TOO_LARGE, message);
    }

    @ExceptionHandler({ReceiptConflictException.class})
    ResponseEntity<ReceiptApi.ErrorResponse> conflict() {
        return response(HttpStatus.CONFLICT, "Receipt or photo UUID conflicts with existing data.");
    }

    @ExceptionHandler(ReceiptNotFoundException.class)
    ResponseEntity<ReceiptApi.ErrorResponse> notFound() {
        return response(HttpStatus.NOT_FOUND, "Receipt or photo not found.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ReceiptApi.ErrorResponse> missingResource() {
        return response(HttpStatus.NOT_FOUND, "Resource not found.");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, MissingServletRequestPartException.class,
            HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class, MultipartException.class})
    ResponseEntity<ReceiptApi.ErrorResponse> malformed() {
        return response(HttpStatus.BAD_REQUEST, "The request metadata or multipart body is invalid.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ReceiptApi.ErrorResponse> unsupportedMedia() {
        return response(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Use multipart/form-data with JSON metadata.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ReceiptApi.ErrorResponse> methodNotAllowed() {
        return response(HttpStatus.METHOD_NOT_ALLOWED, "This method is not supported.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ReceiptApi.ErrorResponse> unexpected() {
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "The request could not be completed.");
    }

    private ResponseEntity<ReceiptApi.ErrorResponse> response(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ReceiptApi.ErrorResponse(message));
    }
}
