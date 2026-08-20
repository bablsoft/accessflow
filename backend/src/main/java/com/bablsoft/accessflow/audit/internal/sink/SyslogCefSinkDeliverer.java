package com.bablsoft.accessflow.audit.internal.sink;

import com.bablsoft.accessflow.audit.api.AuditSinkType;
import com.bablsoft.accessflow.audit.internal.codec.AuditSinkConfigCodec;
import com.bablsoft.accessflow.audit.internal.persistence.entity.AuditSinkEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/** Syslog/CEF sink: one framed CEF message per event, one connection per batch. */
@Component
@RequiredArgsConstructor
@Slf4j
class SyslogCefSinkDeliverer implements AuditSinkDeliverer {

    private final AuditSinkConfigCodec codec;
    private final CefFormatter cefFormatter;
    private final SyslogTcpClient syslogTcpClient;

    @Override
    public AuditSinkType type() {
        return AuditSinkType.SYSLOG_CEF;
    }

    @Override
    public void deliver(AuditSinkEntity sink, List<AuditExportEvent> batch) {
        var config = codec.decodeSyslogCef(sink.getConfigJson());
        var messages = batch.stream().map(cefFormatter::format).toList();
        syslogTcpClient.send(config, messages);
    }
}
