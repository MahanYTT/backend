package gg.modl.backend.infrastructure.exception;

import gg.modl.backend.infrastructure.proto.ProtoValidationException;
import gg.modl.backend.infrastructure.proto.ProtobufErrorResponseWriter;
import gg.modl.backend.infrastructure.proto.ProtobufMediaTypes;
import gg.modl.backend.settings.service.SettingsConflictException;
import gg.modl.proto.modl.v1.ApiError;
import gg.modl.proto.modl.v1.FieldViolation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    private static final String INVALID_DATA_MESSAGE = "Invalid data provided.";
    private final ProtobufErrorResponseWriter protobufErrorResponseWriter;

    public GlobalExceptionHandler() {
        this(new ProtobufErrorResponseWriter());
    }

    public GlobalExceptionHandler(ProtobufErrorResponseWriter protobufErrorResponseWriter) {
        this.protobufErrorResponseWriter = protobufErrorResponseWriter;
    }

    @ExceptionHandler(SettingsConflictException.class)
    public ResponseEntity<?> handleSettingsConflict(SettingsConflictException ex, HttpServletRequest request) {
        if (protobufErrorResponseWriter.shouldWriteProtobuf(request)) {
            return protobufError(ex.getStatus(), "CONFLICT", ex.getMessage());
        }
        return ResponseEntity.status(ex.getStatus()).body(Map.of(
            "status", ex.getStatus().value(),
            "error", ex.getMessage(),
            "currentVersion", ex.getCurrentVersion()
        ));
    }

    @ExceptionHandler(BaseApplicationException.class)
    public ResponseEntity<?> handleApplicationException(BaseApplicationException ex, HttpServletRequest request) {
        if (protobufErrorResponseWriter.shouldWriteProtobuf(request)) {
            return protobufError(ex.getStatus(), MachineErrorCodes.forStatus(ex.getStatus()), ex.getMessage());
        }
        return ResponseEntity.status(ex.getStatus())
            .body(new ErrorResponseDTO(ex.getStatus().value(), ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        log.warn("Unhandled IllegalArgumentException — consider replacing with a typed exception at the throw site", ex);
        if (protobufErrorResponseWriter.shouldWriteProtobuf(request)) {
            return protobufError(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "Invalid argument");
        }
        return ResponseEntity.badRequest()
            .body(new ErrorResponseDTO(400, "Invalid argument"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<?> handleMissingParam(
        MissingServletRequestParameterException ex,
        HttpServletRequest request
    ) {
        String message = "Missing required parameter: " + ex.getParameterName();
        if (protobufErrorResponseWriter.shouldWriteProtobuf(request)) {
            return protobufError(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message);
        }
        return ResponseEntity.badRequest()
            .body(new ErrorResponseDTO(400, message));
    }

    @ExceptionHandler(org.springframework.web.bind.MissingRequestHeaderException.class)
    public ResponseEntity<?> handleMissingHeader(
        org.springframework.web.bind.MissingRequestHeaderException ex,
        HttpServletRequest request
    ) {
        String message = "Missing required header: " + ex.getHeaderName();
        if (protobufErrorResponseWriter.shouldWriteProtobuf(request)) {
            return protobufError(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message);
        }
        return ResponseEntity.badRequest()
            .body(new ErrorResponseDTO(400, message));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<?> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String message = "Invalid value for parameter: " + ex.getName();
        if (protobufErrorResponseWriter.shouldWriteProtobuf(request)) {
            return protobufError(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message);
        }
        return ResponseEntity.badRequest()
            .body(new ErrorResponseDTO(400, message));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<?> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        if (protobufErrorResponseWriter.shouldWriteProtobuf(request)) {
            return protobufError(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", INVALID_DATA_MESSAGE);
        }
        return ResponseEntity.badRequest().body(new ErrorResponseDTO(400, INVALID_DATA_MESSAGE));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidationException(MethodArgumentNotValidException ex, HttpServletRequest request) {
        if (protobufErrorResponseWriter.shouldWriteProtobuf(request)) {
            return protobufError(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", INVALID_DATA_MESSAGE);
        }
        return ResponseEntity.badRequest().body(new ErrorResponseDTO(400, INVALID_DATA_MESSAGE));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<?> handleHandlerMethodValidation(
        HandlerMethodValidationException ex,
        HttpServletRequest request
    ) {
        if (protobufErrorResponseWriter.shouldWriteProtobuf(request)) {
            return protobufError(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", INVALID_DATA_MESSAGE);
        }
        return ResponseEntity.badRequest().body(new ErrorResponseDTO(400, INVALID_DATA_MESSAGE));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        if (protobufErrorResponseWriter.shouldWriteProtobuf(request)) {
            return protobufError(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", INVALID_DATA_MESSAGE);
        }
        return ResponseEntity.badRequest().body(new ErrorResponseDTO(400, INVALID_DATA_MESSAGE));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<?> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        if (protobufErrorResponseWriter.shouldWriteProtobuf(request)) {
            return protobufError(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Unsupported media type");
        }
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
            .body(new ErrorResponseDTO(415, "Unsupported media type"));
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<?> handleMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException ex, HttpServletRequest request) {
        if (protobufErrorResponseWriter.shouldWriteProtobuf(request)) {
            return protobufError(HttpStatus.NOT_ACCEPTABLE, "NOT_ACCEPTABLE", "Not acceptable");
        }
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE)
            .body(new ErrorResponseDTO(406, "Not acceptable"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<?> handleMethodNotSupported(
        HttpRequestMethodNotSupportedException ex,
        HttpServletRequest request
    ) {
        String message = "HTTP method not supported: " + ex.getMethod();
        if (protobufErrorResponseWriter.shouldWriteProtobuf(request)) {
            return protobufError(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", message);
        }
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
            .body(new ErrorResponseDTO(405, message));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<?> handleNoHandlerFound(NoHandlerFoundException ex, HttpServletRequest request) {
        String message = "No endpoint found for " + ex.getHttpMethod() + " " + ex.getRequestURL();
        if (protobufErrorResponseWriter.shouldWriteProtobuf(request)) {
            return protobufError(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ErrorResponseDTO(404, message));
    }

    @ExceptionHandler(ProtoValidationException.class)
    public ResponseEntity<?> handleProtoValidation(ProtoValidationException ex, HttpServletRequest request) {
        if (protobufErrorResponseWriter.shouldWriteProtobuf(request)) {
            ApiError.Builder error = ApiError.newBuilder()
                .setStatusCode(400)
                .setCode("INVALID_ARGUMENT")
                .setMessage(ex.getMessage());
            ex.getViolations().stream()
                .map(violation -> FieldViolation.newBuilder()
                    .setField(Objects.toString(violation.getFieldPath(), ""))
                    .setMessage(violation.getMessage())
                    .build())
                .forEach(error::addFieldViolations);
            return protobufError(HttpStatus.BAD_REQUEST, error.build());
        }
        Map<String, Object> body = Map.of(
                "status", 400,
                "error", ex.getMessage()
        );
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGenericException(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception", ex);
        if (protobufErrorResponseWriter.shouldWriteProtobuf(request)) {
            return protobufError(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "An internal error occurred");
        }
        return ResponseEntity.internalServerError()
            .body(new ErrorResponseDTO(500, "An internal error occurred"));
    }

    private ResponseEntity<ApiError> protobufError(HttpStatus status, String code, String message) {
        return protobufError(status, ApiError.newBuilder()
            .setStatusCode(status.value())
            .setCode(code)
            .setMessage(message)
            .build());
    }

    private ResponseEntity<ApiError> protobufError(HttpStatus status, ApiError error) {
        return ResponseEntity.status(status)
            .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
            .body(error);
    }

}
