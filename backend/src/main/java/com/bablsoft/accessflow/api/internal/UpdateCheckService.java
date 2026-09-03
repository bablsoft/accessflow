package com.bablsoft.accessflow.api.internal;

public interface UpdateCheckService {

    /** Returns the cached update snapshot; never blocks on the network. */
    UpdateStatusView status();
}
