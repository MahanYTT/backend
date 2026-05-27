package gg.modl.backend.player.dto.request;

import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import jakarta.validation.constraints.Size;
import org.jetbrains.annotations.Nullable;

public record CreateUploadTokenRequest(
    @Nullable @Size(max = RequestValidationLimits.PLAYER_ISSUER_NAME_MAX_LENGTH) String issuerName,
    @Nullable @Size(max = RequestValidationLimits.PLAYER_ISSUER_ID_MAX_LENGTH) String issuerId
) {}
