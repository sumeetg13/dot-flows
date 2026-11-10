package io.dotflows.spring.web;

import io.dotflows.FlowException;
import io.dotflows.NotFoundException;
import io.dotflows.ValidationException;
import io.dotflows.model.ValidationError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice(assignableTypes = DotflowsApiController.class)
public class DotflowsApiExceptionHandler {

    public record ApiError(String message, List<ValidationError> errors) {
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(e.getMessage(), List.of()));
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiError> invalid(ValidationException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ApiError("The definition has problems and was not deployed", e.errors()));
    }

    @ExceptionHandler(FlowException.class)
    public ResponseEntity<ApiError> badRequest(FlowException e) {
        return ResponseEntity.badRequest().body(new ApiError(e.getMessage(), List.of()));
    }
}
