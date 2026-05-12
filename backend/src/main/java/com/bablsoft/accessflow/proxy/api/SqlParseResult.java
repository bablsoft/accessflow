package com.bablsoft.accessflow.proxy.api;

import com.bablsoft.accessflow.core.api.QueryType;
import net.sf.jsqlparser.statement.Statement;

public record SqlParseResult(QueryType type, Statement statement) {
}
