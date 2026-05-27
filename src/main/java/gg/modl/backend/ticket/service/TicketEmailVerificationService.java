package gg.modl.backend.ticket.service;

import gg.modl.backend.database.mongo.repository.TicketVerificationMongoRepository;
import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.email.EmailHTMLTemplate;
import gg.modl.backend.email.EmailService;
import gg.modl.backend.infrastructure.exception.ExternalServiceException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.ticket.config.TicketEmailVerificationConfiguration;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketVerification;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TicketEmailVerificationService {
    private final TicketVerificationMongoRepository ticketVerificationRepository;
    private final EmailService emailService;
    private final TicketEmailVerificationConfiguration verificationConfig;
    private static final SecureRandom RANDOM = new SecureRandom();

    public String sendVerificationCode(Server server, Ticket ticket) {
        String email = getCreatorEmail(ticket);
        if (email == null || email.isBlank()) {
            throw new ValidationException("No valid email associated with this ticket");
        }

        String code = String.format("%06d", RANDOM.nextInt(1000000));
        String codeHash = hashCode(code);

        TicketVerification verification = TicketVerification.builder()
            .id(UUID.randomUUID().toString())
            .ticketId(ticket.getId())
            .codeHash(codeHash)
            .email(email)
            .expiresAt(new Date(System.currentTimeMillis() + (verificationConfig.getCodeExpirySeconds() * 1000L)))
            .build();
        ticketVerificationRepository.replaceCodeVerification(server, verification);

        try {
            String serverName = server.getServerName() != null ? server.getServerName() : "Server";
            EmailHTMLTemplate.HTMLEmail emailContent = EmailHTMLTemplate.TICKET_VERIFICATION_CODE.build(serverName, code);
            emailService.send(email, emailContent);
        } catch (Exception e) {
            log.error("Failed to send verification code email for ticket {}", ticket.getId(), e);
            throw new ExternalServiceException("Failed to send verification email", e);
        }

        return EmailAddressUtil.mask(email);
    }

    private String getCreatorEmail(Ticket ticket) {
        if (ticket.getData() == null) {
            return null;
        }

        Object email = ticket.getData().get("creatorEmail");
        if (email == null) {
            return null;
        }

        String normalizedEmail = EmailAddressUtil.normalizeIfValid(email.toString());
        if (normalizedEmail == null) {
            log.warn("Skipping ticket verification email for {} due to invalid creator email: {}", ticket.getId(), email);
        }

        return normalizedEmail;
    }

    private String hashCode(String code) {
        try {
            String secret = verificationConfig.getCodeHashSecret();
            if (secret != null && !secret.isBlank()) {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                return HexFormat.of().formatHex(mac.doFinal(code.getBytes(StandardCharsets.UTF_8)));
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(code.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new ExternalServiceException("Failed to hash code", e);
        }
    }

    public String verifyCode(Server server, String ticketId, String code) {
        String codeHash = hashCode(code);
        Date now = new Date();
        TicketVerification verification = ticketVerificationRepository.consumeMatchingCode(server, ticketId, codeHash, now)
            .orElse(null);
        if (verification == null) {
            ticketVerificationRepository.incrementFailedAttempts(server, ticketId, now);
            return null;
        }

        String token = UUID.randomUUID().toString();
        TicketVerification tokenVerification = TicketVerification.builder()
            .id(UUID.randomUUID().toString())
            .ticketId(ticketId)
            .token(token)
            .email(verification.getEmail())
            .expiresAt(new Date(System.currentTimeMillis() + (verificationConfig.getTokenExpirySeconds() * 1000L)))
            .build();
        ticketVerificationRepository.saveEntity(server, tokenVerification);
        return token;
    }

    public boolean validateToken(Server server, String ticketId, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return ticketVerificationRepository.existsActiveToken(server, ticketId, token, new Date());
    }
}
