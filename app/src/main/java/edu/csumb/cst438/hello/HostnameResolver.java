package edu.csumb.cst438.hello;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.stereotype.Component;

/**
 * Finds the name of the machine (or container) this process is running on.
 *
 * Docker sets the HOSTNAME environment variable to the container ID, so we read that
 * first. On a laptop HOSTNAME is usually absent, so we fall back to a DNS lookup.
 */
@Component
public class HostnameResolver {

    static final String HOSTNAME_ENV_VAR = "HOSTNAME";
    static final String UNKNOWN_HOSTNAME = "unknown";

    public String resolve() {
        String fromEnv = System.getenv(HOSTNAME_ENV_VAR);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return UNKNOWN_HOSTNAME;
        }
    }
}
