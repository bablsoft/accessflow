package com.bablsoft.accessflow.deploygov.internal.verification;

import java.net.URI;

/** Fetches a verification target through {@link VerificationTargetPolicy}; never throws. */
interface VerificationHttpFetcher {

    FetchResult fetch(String checkName, URI uri);
}
