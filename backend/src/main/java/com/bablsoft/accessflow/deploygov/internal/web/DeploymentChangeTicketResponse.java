package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.DeploymentTicketView;

/** The change-ticket summary on the gate (#993) and on deployment requests (#994). */
record DeploymentChangeTicketResponse(String system, String externalKey, String url, String status,
                                      String resolution, String decision) {

    static DeploymentChangeTicketResponse from(DeploymentTicketView view) {
        return view == null ? null : new DeploymentChangeTicketResponse(view.ticketSystem(),
                view.externalKey(), view.url(), view.status(), view.resolution(), view.decision());
    }
}
