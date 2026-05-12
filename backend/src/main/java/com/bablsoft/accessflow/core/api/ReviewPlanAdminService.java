package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.UUID;

public interface ReviewPlanAdminService {

    List<ReviewPlanView> list(UUID organizationId);

    ReviewPlanView get(UUID id, UUID organizationId);

    ReviewPlanView create(CreateReviewPlanCommand command);

    ReviewPlanView update(UUID id, UUID organizationId, UpdateReviewPlanCommand command);

    void delete(UUID id, UUID organizationId);
}
