package com.bablsoft.accessflow.core.api;

public interface BootstrapService {

    boolean isSetupRequired();

    SetupResult performSetup(SetupCommand command);
}
