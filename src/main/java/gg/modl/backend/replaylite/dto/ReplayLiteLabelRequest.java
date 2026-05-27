package gg.modl.backend.replaylite.dto;

import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import gg.modl.backend.replaylite.data.ReplayLiteLabel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ReplayLiteLabelRequest(
    @NotEmpty
    @Size(max = RequestValidationLimits.REPLAY_LITE_LABELS_MAX_ENTRIES)
    List<@Valid ReplayLiteLabel> labels
) {}
