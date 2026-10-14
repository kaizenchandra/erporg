package com.atlas.configuration;

import com.atlas.modules.corehr.domain.model.ChangeRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrors {
  @ExceptionHandler(ChangeRequest.Validation.class)
  ResponseEntity<?> validation(ChangeRequest.Validation e, HttpServletRequest request) {
    return ResponseEntity.badRequest()
        .body(
            Map.of(
                "status",
                400,
                "detail",
                e.getMessage(),
                "fieldErrors",
                e.fields(),
                "correlationId",
                String.valueOf(request.getAttribute("correlationId"))));
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class
  })
  ResponseEntity<?> invalid(Exception e, HttpServletRequest request) {
    return error(
        request,
        400,
        e instanceof IllegalArgumentException ? e.getMessage() : "Invalid request body.");
  }

  @ExceptionHandler(ChangeRequest.Denied.class)
  ResponseEntity<?> denied(Exception e, HttpServletRequest request) {
    return error(request, 403, e.getMessage());
  }

  @ExceptionHandler({
    ChangeRequest.Conflict.class,
    org.springframework.dao.DataIntegrityViolationException.class,
    org.springframework.dao.CannotAcquireLockException.class
  })
  ResponseEntity<?> conflict(Exception e, HttpServletRequest request) {
    return error(
        request,
        409,
        e instanceof ChangeRequest.Conflict
            ? e.getMessage()
            : "This change conflicts with another operation. Refresh and try again.");
  }

  private ResponseEntity<?> error(HttpServletRequest request, int status, String detail) {
    return ResponseEntity.status(status)
        .body(
            Map.of(
                "status",
                status,
                "detail",
                detail,
                "correlationId",
                String.valueOf(request.getAttribute("correlationId"))));
  }
}
