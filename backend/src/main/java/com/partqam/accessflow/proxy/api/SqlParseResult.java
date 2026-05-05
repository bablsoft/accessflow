package com.partqam.accessflow.proxy.api;

import com.partqam.accessflow.core.api.QueryType;
import net.sf.jsqlparser.statement.Statement;

public record SqlParseResult(QueryType type, Statement statement) {
}
