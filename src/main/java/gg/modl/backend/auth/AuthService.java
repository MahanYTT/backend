package gg.modl.backend.auth;

import gg.modl.backend.auth.data.AuthCode;
import gg.modl.backend.database.mongo.repository.AuthCodeMongoRepository;
import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.email.EmailHTMLTemplate;
import gg.modl.backend.email.EmailService;
import gg.modl.backend.server.data.Server;
import jakarta.mail.MessagingException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {
    private final EmailService emailService;
    private final AuthCodeMongoRepository authCodeRepository;
    private final AuthConfiguration authConfiguration;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public void sendUserLoginCode(Server server, String email) throws MessagingException, UnsupportedEncodingException {
        String code = prepareAndStoreCode(email, (normalizedEmail, codeHash, expiresAt) ->
            authCodeRepository.replaceForServer(server, normalizedEmail, codeHash, expiresAt));

        if (code == null) {
            return;
        }

        emailService.send(email, EmailHTMLTemplate.USER_CODE.build(server.getServerName(), code));
    }

    private String prepareAndStoreCode(String email, CodeStorageAction storageAction) {
        String code = generateNumericCode(authConfiguration.getEmailCodeLength());
        String codeHash = hashCode(code);
        String normalizedEmail = EmailAddressUtil.normalize(email);
        Date expiresAt = new Date(System.currentTimeMillis() + (authConfiguration.getEmailCodeExpiry() * 1000L));

        storageAction.store(normalizedEmail, codeHash, expiresAt);

        return code;
    }

    private String generateNumericCode(int length) {
        StringBuilder code = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            code.append(SECURE_RANDOM.nextInt(10));
        }
        return code.toString();
    }

    private String hashCode(String code) {
        try {
            String secret = authConfiguration.getCodeHashSecret();
            if (secret != null && !secret.isBlank()) {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                return Base64.getEncoder().encodeToString(mac.doFinal(code.getBytes(StandardCharsets.UTF_8)));
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(code.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            log.error("Required hashing algorithm unavailable for auth code hashing");
            throw new IllegalStateException("Failed to hash auth code: algorithm unavailable", e);
        } catch (InvalidKeyException e) {
            log.error("HMAC secret rejected by Mac.init; verify code-hash secret configuration");
            throw new IllegalStateException("Failed to hash auth code: invalid hash key", e);
        }
    }

    public void sendAdminLoginCode(String email) throws MessagingException, UnsupportedEncodingException {
        String code = prepareAndStoreCode(email, (normalizedEmail, codeHash, expiresAt) ->
            authCodeRepository.replaceForGlobal(normalizedEmail, codeHash, expiresAt));

        if (code == null) {
            return;
        }

        emailService.send(email, EmailHTMLTemplate.ADMIN_CODE.build(code, null));
    }

    public boolean verifyCode(Server server, String email, String code) {
        String normalizedEmail = EmailAddressUtil.normalize(email);
        String codeHash = hashCode(code);
        Date now = new Date();

        Optional<AuthCode> consumed = authCodeRepository.consumeIfHashMatchesForServer(server, normalizedEmail, codeHash, now);
        if (consumed.isPresent()) {
            return true;
        }

        authCodeRepository.incrementFailedAttemptsForServer(server, normalizedEmail, now);
        return false;
    }

    public boolean verifyAdminCode(String email, String code) {
        String normalizedEmail = EmailAddressUtil.normalize(email);
        String codeHash = hashCode(code);
        Date now = new Date();

        Optional<AuthCode> consumed = authCodeRepository.consumeIfHashMatchesForGlobal(normalizedEmail, codeHash, now);
        if (consumed.isPresent()) {
            return true;
        }

        authCodeRepository.incrementFailedAttemptsForGlobal(normalizedEmail, now);
        return false;
    }

    @FunctionalInterface
    private interface CodeStorageAction {
        void store(String normalizedEmail, String codeHash, Date expiresAt);
    }
}
