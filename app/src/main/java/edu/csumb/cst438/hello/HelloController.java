package edu.csumb.cst438.hello;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(HelloController.BASE_PATH)
public class HelloController {

    static final String BASE_PATH = "/api/v1";
    static final String HELLO_PATH = "/hello";
    static final String GREETING = "Hello from CST 438";

    private final HostnameResolver hostnameResolver;
    private final String version;

    public HelloController(HostnameResolver hostnameResolver,
                           @Value("${app.version}") String version) {
        this.hostnameResolver = hostnameResolver;
        this.version = version;
    }

    @GetMapping(HELLO_PATH)
    public HelloResponse hello() {
        return new HelloResponse(GREETING, hostnameResolver.resolve(), version);
    }
}

