package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.QueryRequestPersistenceService;
import com.bablsoft.accessflow.core.api.SubmitQueryCommand;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultQueryRequestPersistenceService implements QueryRequestPersistenceService {

    private final QueryRequestRepository queryRequestRepository;
    private final DatasourceRepository datasourceRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public UUID submit(SubmitQueryCommand command) {
        var datasource = datasourceRepository.findById(command.datasourceId())
                .orElseThrow(() -> new IllegalStateException(
                        "Datasource not found: " + command.datasourceId()));
        var submitter = userRepository.findById(command.submittedByUserId())
                .orElseThrow(() -> new IllegalStateException(
                        "User not found: " + command.submittedByUserId()));
        var entity = new QueryRequestEntity();
        entity.setId(UUID.randomUUID());
        entity.setDatasource(datasource);
        entity.setSubmittedBy(submitter);
        entity.setSqlText(command.sqlText());
        entity.setQueryType(command.queryType());
        entity.setTransactional(command.transactional());
        entity.setJustification(command.justification());
        var saved = queryRequestRepository.save(entity);
        return saved.getId();
    }
}
