package gg.modl.backend.infrastructure.exception;

import gg.modl.backend.infrastructure.proto.ProtobufErrorResponseWriter;
import gg.modl.backend.infrastructure.proto.ProtobufMediaTypes;
import gg.modl.proto.modl.v1.ApiError;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class CustomErrorController implements ErrorController {
    private final ProtobufErrorResponseWriter protobufErrorResponseWriter;

    @RequestMapping("/error")
    public ResponseEntity<?> handleError(HttpServletRequest request) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);

        int statusCode = status != null ? Integer.parseInt(status.toString()) : 500;

        String errorMessage;
        String error;

        if (statusCode == HttpStatus.NOT_FOUND.value()) {
            error = "Not Found";
            errorMessage = "The requested resource was not found.";
        } else if (statusCode == HttpStatus.FORBIDDEN.value()) {
            error = "Forbidden";
            errorMessage = "You do not have permission to access this resource.";
        } else if (statusCode == HttpStatus.UNAUTHORIZED.value()) {
            error = "Unauthorized";
            errorMessage = "Authentication is required to access this resource.";
        } else if (statusCode == HttpStatus.INTERNAL_SERVER_ERROR.value()) {
            error = "Internal Server Error";
            errorMessage = "An internal server error occurred.";
        } else {
            error = "Error";
            errorMessage = "Your request could not be processed.";
        }

        HttpStatus httpStatus = HttpStatus.valueOf(statusCode);
        if (protobufErrorResponseWriter.shouldWriteProtobuf(request)) {
            return ResponseEntity.status(httpStatus)
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .body(ApiError.newBuilder()
                    .setStatusCode(statusCode)
                    .setCode(MachineErrorCodes.forStatus(httpStatus))
                    .setMessage(errorMessage)
                    .build());
        }

        ErrorResponse errorResponse = new ErrorResponse(
            statusCode,
            error,
            errorMessage);

        return new ResponseEntity<>(errorResponse, httpStatus);
    }

    @Setter
    @Getter
    @AllArgsConstructor
    public static class ErrorResponse {
        private int status;
        private String error;
        private String message;
    }
}
