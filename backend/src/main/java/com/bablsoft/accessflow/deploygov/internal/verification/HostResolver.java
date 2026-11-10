package com.bablsoft.accessflow.deploygov.internal.verification;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Resolves a host name or literal to its addresses; a seam so tests need no network. */
@FunctionalInterface
interface HostResolver {

    InetAddress[] resolve(String host) throws UnknownHostException;
}
