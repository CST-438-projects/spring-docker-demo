package edu.csumb.cst438.hello;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

class HelloControllerTest {

    private static final String HELLO_URL = HelloController.BASE_PATH + HelloController.HELLO_PATH;
    private static final String MISSING_URL = HelloController.BASE_PATH + "/nope";
    private static final String DEFAULT_VERSION = "dev";
    private static final String PINNED_VERSION = "1.2.3";

    @Nested
    @WebMvcTest(HelloController.class)
    @Import(HostnameResolver.class)
    class WithDefaultVersion {

        @Autowired
        MockMvc mvc;

        @Test
        void helloReturnsMessageHostnameAndDefaultVersion() throws Exception {
            mvc.perform(get(HELLO_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(HelloController.GREETING))
                .andExpect(jsonPath("$.hostname").value(not(emptyString())))
                .andExpect(jsonPath("$.version").value(DEFAULT_VERSION));
        }

        @Test
        void unknownPathIs404() throws Exception {
            mvc.perform(get(MISSING_URL)).andExpect(status().isNotFound());
        }
    }

    @Nested
    @WebMvcTest(HelloController.class)
    @Import(HostnameResolver.class)
    @TestPropertySource(properties = "APP_VERSION=" + PINNED_VERSION)
    class WithVersionFromEnvironment {

        @Autowired
        MockMvc mvc;

        @Test
        void versionComesFromAppVersionVariable() throws Exception {
            mvc.perform(get(HELLO_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(PINNED_VERSION));
        }
    }
}
