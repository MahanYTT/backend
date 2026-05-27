package gg.modl.backend.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.AssertionResult;
import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.FinishAssertionOptions;
import com.yubico.webauthn.FinishRegistrationOptions;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.RegistrationResult;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.StartAssertionOptions;
import com.yubico.webauthn.StartRegistrationOptions;
import com.yubico.webauthn.data.AttestationConveyancePreference;
import com.yubico.webauthn.data.AuthenticatorAssertionResponse;
import com.yubico.webauthn.data.AuthenticatorAttestationResponse;
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.ClientAssertionExtensionOutputs;
import com.yubico.webauthn.data.ClientRegistrationExtensionOutputs;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import com.yubico.webauthn.data.PublicKeyCredentialType;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import com.yubico.webauthn.data.ResidentKeyRequirement;
import com.yubico.webauthn.data.UserIdentity;
import com.yubico.webauthn.data.UserVerificationRequirement;
import com.yubico.webauthn.data.exception.Base64UrlException;
import com.yubico.webauthn.exception.AssertionFailedException;
import com.yubico.webauthn.exception.RegistrationFailedException;
import gg.modl.backend.auth.data.WebAuthnChallenge;
import gg.modl.backend.auth.data.WebAuthnCredential;
import gg.modl.backend.database.mongo.repository.WebAuthnChallengeMongoRepository;
import gg.modl.backend.database.mongo.repository.WebAuthnCredentialMongoRepository;
import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.infrastructure.exception.ExternalServiceException;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.exception.UnauthorizedException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.server.data.CustomDomainStatus;
import gg.modl.backend.server.data.Server;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebAuthnService {
    private final WebAuthnChallengeMongoRepository challengeRepository;
    private final WebAuthnCredentialMongoRepository credentialRepository;
    private final AuthConfiguration authConfiguration;

    public StartRegistrationResult startRegistration(Server server, String email) {
        RelyingParty rp = buildRelyingParty(server);

        UserIdentity userIdentity = UserIdentity.builder()
            .name(email)
            .displayName(email)
            .id(userHandle(email))
            .build();

        PublicKeyCredentialCreationOptions options = rp.startRegistration(
            StartRegistrationOptions.builder()
                .user(userIdentity)
                .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                    .residentKey(ResidentKeyRequirement.PREFERRED)
                    .userVerification(UserVerificationRequirement.REQUIRED)
                    .build())
                .build()
        );

        String challengeId = UUID.randomUUID().toString();
        try {
            WebAuthnChallenge challenge = new WebAuthnChallenge();
            challenge.setId(challengeId);
            challenge.setChallengeJson(options.toJson());
            challenge.setEmail(EmailAddressUtil.normalize(email));
            challenge.setExpiresAt(challengeExpiry());
            challengeRepository.saveEntity(server, challenge);
            return new StartRegistrationResult(challengeId, options.toCredentialsCreateJson());
        } catch (JsonProcessingException e) {
            throw new ExternalServiceException("Failed to serialize registration options", e);
        }
    }

    private RelyingParty buildRelyingParty(Server server) {
        String rpId = resolveRpId(server);
        Set<String> origins = resolveOrigins(server, rpId);
        CredentialRepositoryAdapter credRepo = new CredentialRepositoryAdapter(server);

        return RelyingParty.builder()
            .identity(RelyingPartyIdentity.builder()
                .id(rpId)
                .name("modl")
                .build())
            .credentialRepository(credRepo)
            .origins(origins)
            .attestationConveyancePreference(AttestationConveyancePreference.NONE)
            .build();
    }

    private String resolveRpId(Server server) {
        if (server.getCustomDomainOverride() != null && !server.getCustomDomainOverride().isBlank()
            && server.getCustomDomainStatus() == CustomDomainStatus.ACTIVE) {
            return server.getCustomDomainOverride();
        }
        return "modl.gg";
    }

    private Set<String> resolveOrigins(Server server, String rpId) {
        Set<String> origins = new HashSet<>();
        if (authConfiguration.isDevelopmentMode()) {
            origins.add("http://localhost:3000");
            origins.add("http://localhost:5173");
        }
        if (server.getCustomDomainOverride() != null && !server.getCustomDomainOverride().isBlank()
            && server.getCustomDomainStatus() == CustomDomainStatus.ACTIVE) {
            origins.add("https://" + server.getCustomDomainOverride());
        } else {
            origins.add("https://" + server.getCustomDomain() + ".modl.gg");
        }
        origins.add("https://" + rpId);
        return origins;
    }

    private ByteArray userHandle(String email) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(EmailAddressUtil.normalize(email).getBytes(StandardCharsets.UTF_8));
            return new ByteArray(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private Date challengeExpiry() {
        return new Date(System.currentTimeMillis() + 5 * 60 * 1000);
    }

    public void finishRegistration(Server server, String email, String challengeId, String responseJson, String credentialName)
        throws IOException {
        RelyingParty rp = buildRelyingParty(server);
        WebAuthnChallenge challenge = challengeRepository.consumeActiveChallenge(server, challengeId, new Date()).orElse(null);
        if (challenge == null) {
            throw new ResourceNotFoundException("Challenge not found or expired");
        }

        String normalizedEmail = EmailAddressUtil.normalize(email);
        if (!normalizedEmail.equals(challenge.getEmail())) {
            throw new ValidationException("Email mismatch");
        }

        PublicKeyCredentialCreationOptions options = PublicKeyCredentialCreationOptions.fromJson(challenge.getChallengeJson());
        PublicKeyCredential<AuthenticatorAttestationResponse, ClientRegistrationExtensionOutputs> pkc =
            PublicKeyCredential.parseRegistrationResponseJson(responseJson);

        RegistrationResult result;
        try {
            result = rp.finishRegistration(
                FinishRegistrationOptions.builder()
                    .request(options)
                    .response(pkc)
                    .build()
            );
        } catch (RegistrationFailedException e) {
            throw new ValidationException("Registration verification failed: " + e.getMessage(), e);
        }

        WebAuthnCredential cred = new WebAuthnCredential();
        cred.setEmail(normalizedEmail);
        cred.setUserHandle(userHandle(normalizedEmail).getBase64Url());
        cred.setCredentialId(result.getKeyId().getId().getBase64Url());
        cred.setPublicKeyCose(result.getPublicKeyCose().getBytes());
        cred.setSignatureCount(result.getSignatureCount());
        cred.setName(credentialName != null && !credentialName.isBlank() ? credentialName.trim() : "Passkey");
        cred.setCreatedAt(new Date());
        cred.setLastUsedAt(new Date());
        credentialRepository.saveEntity(server, cred);
    }

    public boolean checkHasPasskeys(Server server, String email) {
        return credentialRepository.existsByEmail(server, email);
    }

    public StartAuthenticationResult startDiscoverableAuthentication(Server server) {
        RelyingParty rp = buildRelyingParty(server);
        AssertionRequest assertionRequest = rp.startAssertion(
            StartAssertionOptions.builder()
                .userVerification(UserVerificationRequirement.REQUIRED)
                .build()
        );

        String challengeId = UUID.randomUUID().toString();
        try {
            WebAuthnChallenge challenge = new WebAuthnChallenge();
            challenge.setId(challengeId);
            challenge.setChallengeJson(assertionRequest.toJson());
            challenge.setEmail(null);
            challenge.setExpiresAt(challengeExpiry());
            challengeRepository.saveEntity(server, challenge);
            return new StartAuthenticationResult(challengeId, assertionRequest.toCredentialsGetJson(), true);
        } catch (JsonProcessingException e) {
            throw new ExternalServiceException("Failed to serialize assertion request", e);
        }
    }

    public StartAuthenticationResult startAuthentication(Server server, String email) {
        RelyingParty rp = buildRelyingParty(server);
        AssertionRequest assertionRequest = rp.startAssertion(
            StartAssertionOptions.builder()
                .username(EmailAddressUtil.normalize(email))
                .userVerification(UserVerificationRequirement.REQUIRED)
                .build()
        );

        String challengeId = UUID.randomUUID().toString();
        try {
            WebAuthnChallenge challenge = new WebAuthnChallenge();
            challenge.setId(challengeId);
            challenge.setChallengeJson(assertionRequest.toJson());
            challenge.setEmail(EmailAddressUtil.normalize(email));
            challenge.setExpiresAt(challengeExpiry());
            challengeRepository.saveEntity(server, challenge);
            return new StartAuthenticationResult(challengeId, assertionRequest.toCredentialsGetJson(), true);
        } catch (JsonProcessingException e) {
            throw new ExternalServiceException("Failed to serialize assertion request", e);
        }
    }

    public String finishAuthentication(Server server, String challengeId, String responseJson) throws IOException {
        RelyingParty rp = buildRelyingParty(server);
        WebAuthnChallenge challenge = challengeRepository.consumeActiveChallenge(server, challengeId, new Date()).orElse(null);
        if (challenge == null) {
            throw new ResourceNotFoundException("Challenge not found or expired");
        }

        AssertionRequest assertionRequest = AssertionRequest.fromJson(challenge.getChallengeJson());
        PublicKeyCredential<AuthenticatorAssertionResponse, ClientAssertionExtensionOutputs> pkc =
            PublicKeyCredential.parseAssertionResponseJson(responseJson);

        AssertionResult result;
        try {
            result = rp.finishAssertion(
                FinishAssertionOptions.builder()
                    .request(assertionRequest)
                    .response(pkc)
                    .build()
            );
        } catch (AssertionFailedException e) {
            throw new UnauthorizedException("Authentication verification failed: " + e.getMessage(), e);
        }

        if (!result.isSuccess()) {
            throw new UnauthorizedException("Authentication failed");
        }

        if (!result.isSignatureCounterValid()) {
            log.warn("WebAuthn signature counter invalid for credential {} — possible cloned authenticator",
                result.getCredential().getCredentialId().getBase64Url());
            throw new UnauthorizedException("Authentication failed: possible cloned authenticator");
        }

        String credentialId = result.getCredential().getCredentialId().getBase64Url();
        boolean updated = credentialRepository.updateUsage(server, credentialId, result.getSignatureCount(), new Date());
        if (!updated) {
            throw new UnauthorizedException("Authentication failed: credential not found");
        }

        String email = challenge.getEmail();
        if (email == null || email.isBlank()) {
            ByteArray userHandle = result.getCredential().getUserHandle();
            if (userHandle == null) {
                throw new ResourceNotFoundException("Could not determine user identity");
            }
            WebAuthnCredential cred = credentialRepository.findByUserHandle(server, userHandle.getBase64Url()).orElse(null);
            if (cred == null) {
                throw new ResourceNotFoundException("Could not determine user identity");
            }
            email = cred.getEmail();
        }
        return email;
    }

    public List<CredentialInfo> listCredentials(Server server, String email) {
        return credentialRepository.findByEmail(server, email)
            .stream()
            .map(c -> new CredentialInfo(c.getId(), c.getName(), c.getCreatedAt(), c.getLastUsedAt()))
            .collect(Collectors.toList());
    }

    public boolean renameCredential(Server server, String email, String credentialMongoId, String newName) {
        return credentialRepository.renameByIdAndEmail(server, credentialMongoId, email, newName);
    }

    public boolean deleteCredential(Server server, String email, String credentialMongoId) {
        return credentialRepository.deleteByIdAndEmail(server, credentialMongoId, email);
    }

    public record StartRegistrationResult(String challengeId, String optionsJson) {
    }

    public record StartAuthenticationResult(String challengeId, String optionsJson, boolean hasPasskeys) {
    }

    public record CredentialInfo(String id, String name, Date createdAt, Date lastUsedAt) {
    }

    private class CredentialRepositoryAdapter implements CredentialRepository {
        private final Server server;

        CredentialRepositoryAdapter(Server server) {
            this.server = server;
        }

        @Override
        public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
            return credentialRepository.findByEmail(server, username)
                .stream()
                .map(c -> {
                    try {
                        return PublicKeyCredentialDescriptor.builder()
                            .id(ByteArray.fromBase64Url(c.getCredentialId()))
                            .type(PublicKeyCredentialType.PUBLIC_KEY)
                            .build();
                    } catch (Base64UrlException e) {
                        log.error("Invalid base64url credential ID for {}", c.getId(), e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        }

        @Override
        public Optional<ByteArray> getUserHandleForUsername(String username) {
            return Optional.of(userHandle(username));
        }

        @Override
        public Optional<String> getUsernameForUserHandle(ByteArray userHandle) {
            return credentialRepository.findByUserHandle(server, userHandle.getBase64Url()).map(WebAuthnCredential::getEmail);
        }

        @Override
        public Optional<RegisteredCredential> lookup(ByteArray credentialId, ByteArray userHandle) {
            return credentialRepository.findByCredentialId(server, credentialId.getBase64Url())
                .filter(cred -> {
                    try {
                        ByteArray storedHandle = ByteArray.fromBase64Url(cred.getUserHandle());
                        return storedHandle.equals(userHandle);
                    } catch (Base64UrlException e) {
                        log.error("Invalid base64url user handle for credential {}", cred.getId(), e);
                        return false;
                    }
                })
                .map(cred -> RegisteredCredential.builder()
                    .credentialId(credentialId)
                    .userHandle(userHandle)
                    .publicKeyCose(new ByteArray(cred.getPublicKeyCose()))
                    .signatureCount(cred.getSignatureCount())
                    .build());
        }

        @Override
        public Set<RegisteredCredential> lookupAll(ByteArray credentialId) {
            return credentialRepository.findAllByCredentialId(server, credentialId.getBase64Url())
                .stream()
                .map(c -> RegisteredCredential.builder()
                    .credentialId(credentialId)
                    .userHandle(userHandle(c.getEmail()))
                    .publicKeyCose(new ByteArray(c.getPublicKeyCose()))
                    .signatureCount(c.getSignatureCount())
                    .build())
                .collect(Collectors.toSet());
        }
    }
}
