package com.example.resiliency.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.junit.jupiter.api.Test;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ProductControllerTest {

    @LocalServerPort
    private int port;

    private final TestRestTemplate restTemplate = new TestRestTemplate();

    @Test
    void returnsProductByDefault() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("http://localhost:" + port + "/products/1", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Wireless Mouse");
    }

    @Test
    void returns404ForUnknownProduct() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("http://localhost:" + port + "/products/999", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void perRequestErrorOverrideForcesFailure() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/products/1?mode=ERROR", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void adminEndpointTogglesProcessWideMode() {
        restTemplate.postForEntity(
                "http://localhost:" + port + "/admin/mode",
                java.util.Map.of("mode", "ERROR", "delayMs", 0, "errorRate", 0.0),
                String.class);

        ResponseEntity<String> response =
                restTemplate.getForEntity("http://localhost:" + port + "/products/1", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        // reset for other tests
        restTemplate.postForEntity(
                "http://localhost:" + port + "/admin/mode",
                java.util.Map.of("mode", "OK", "delayMs", 0, "errorRate", 0.0),
                String.class);
    }
}
