package com.partqam.accessflow.core.api;

public interface BootstrapService {

    boolean isSetupRequired();

    SetupResult performSetup(SetupCommand command);
}
