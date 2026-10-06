package edu.csumb.cst438.hello;

/**
 * Body of GET /api/v1/hello.
 *
 * @param message  a fixed greeting
 * @param hostname where the process is running; inside Docker this is the container ID
 * @param version  the APP_VERSION environment variable, or "dev" when it is not set
 */
public record HelloResponse(String message, String hostname, String version) {
}
