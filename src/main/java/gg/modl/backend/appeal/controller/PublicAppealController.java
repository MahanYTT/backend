package gg.modl.backend.appeal.controller;

import gg.modl.backend.appeal.dto.request.AddAppealReplyRequest;
import gg.modl.backend.appeal.dto.request.CreateAppealRequest;
import gg.modl.backend.appeal.dto.response.PublicAppealResponse;
import gg.modl.backend.appeal.service.AppealService;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.dto.response.TicketResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PUBLIC_APPEALS)
@RequiredArgsConstructor
public class PublicAppealController {
    private final AppealService appealService;

    @GetMapping("/{id}")
    public ResponseEntity<?> getAppeal(
        @PathVariable String id,
        HttpServletRequest request
    ) {
        final Server server = RequestUtil.getRequestServer(request);

        final TicketResponse appeal = appealService.getAppealById(server, id);
        return ResponseEntity.ok((Object) PublicAppealResponse.fromTicketResponse(appeal));
    }

    @PostMapping
    public ResponseEntity<?> createAppeal(
        @RequestBody @Valid CreateAppealRequest createRequest,
        HttpServletRequest request
    ) {
        final Server server = RequestUtil.getRequestServer(request);

        final TicketResponse appeal = appealService.createAppeal(server, createRequest);
        final String workflowStatus = appeal.appealWorkflowStatus() != null ? appeal.appealWorkflowStatus() : appeal.status();

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
            "success", true,
            "appealId", appeal.id(),
            "message", "Appeal created successfully",
            "appeal", Map.of(
                "id", appeal.id(),
                "_id", appeal.id(),
                "type", appeal.type(),
                "subject", appeal.subject(),
                "status", workflowStatus,
                "appealWorkflowStatus", workflowStatus,
                "created", appeal.date().toInstant().toString()
            )
        ));
    }

    @PostMapping("/{id}/replies")
    public ResponseEntity<?> addReply(
        @PathVariable String id,
        @RequestBody @Valid AddAppealReplyRequest replyRequest,
        HttpServletRequest request
    ) {
        final Server server = RequestUtil.getRequestServer(request);

        if (replyRequest.staff()) {
            throw new ValidationException("Public replies cannot be marked as staff");
        }

        final TicketReply reply = appealService.addReply(server, id, replyRequest);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
            "success", true,
            "message", "Reply added successfully",
            "reply", reply
        ));
    }
}
