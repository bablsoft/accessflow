package com.bablsoft.accessflow.deploygov.internal.verification;

import java.util.UUID;

/** Samples one running post-deployment verification (#1025). Module-private. */
public interface DeploymentVerificationService {

    VerificationTickResult verifyOne(UUID deploymentRequestId);
}
