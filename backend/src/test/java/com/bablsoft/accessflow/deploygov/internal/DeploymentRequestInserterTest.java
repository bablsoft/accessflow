package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DeploymentRequestInserterTest {

    private final DeploymentRequestRepository repository = mock(DeploymentRequestRepository.class);
    private final DeploymentRequestInserter inserter = new DeploymentRequestInserter(repository);

    @Test
    void insertFlushesSoAConstraintViolationSurfacesImmediately() {
        var entity = new DeploymentRequestEntity();
        entity.setId(UUID.randomUUID());

        inserter.insert(entity);

        verify(repository).saveAndFlush(entity);
    }

    @Test
    void theViolationPropagatesRatherThanBeingSwallowed() {
        var entity = new DeploymentRequestEntity();
        doThrow(new DataIntegrityViolationException("uq_deployment_requests_trigger_idem"))
                .when(repository).saveAndFlush(entity);

        assertThatThrownBy(() -> inserter.insert(entity))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * The whole point of this class. Swallowing the exception inside the boundary, or running on the
     * caller's transaction, would leave the submit path unable to re-read the winning row after a
     * trigger-idempotency race.
     */
    @Test
    void insertRunsOnItsOwnTransaction() throws Exception {
        var annotation = DeploymentRequestInserter.class
                .getDeclaredMethod("insert", DeploymentRequestEntity.class)
                .getAnnotation(Transactional.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
