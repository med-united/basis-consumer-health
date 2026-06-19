package de.servicehealtherx.connui;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class ConnUiConfigResourceTest {

    @Test
    void returns_configured_default_context() {
        given()
            .when().get("/conn-ui/config.json")
            .then()
                .statusCode(200)
                .contentType("application/json")
                .body("defaultContext.mandantId", equalTo("Mandant1"))
                .body("defaultContext.clientSystemId", equalTo("ClientSystem1"))
                .body("defaultContext.workplaceId", equalTo("Workplace1"));
    }

    @Test
    void payload_contains_no_secret_material() {
        given()
            .when().get("/conn-ui/config.json")
            .then()
                .statusCode(200)
                .body("$", not(hasKey("secret")))
                .body("$", not(hasKey("credentials")))
                .body("$", not(hasKey("keystore")))
                .body("$", not(hasKey("password")));
    }
}
