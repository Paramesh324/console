package com.github.eyefloaters.console.legacy.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.skyscreamer.jsonassert.JSONCompareResult;
import org.skyscreamer.jsonassert.comparator.DefaultComparator;

import jakarta.ws.rs.core.Response.Status;

import java.nio.file.Path;
import java.util.Map;

import static io.restassured.RestAssured.given;

@QuarkusTest
@TestProfile(OASModelFilterTest.Profile.class)
class OASModelFilterTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                  "quarkus.smallrye-jwt.enabled", "false",
                  "kafka.admin.num.partitions.max", "100",
                  "kafka.admin.basic.enabled", "false",
                  "kafka.admin.oauth.enabled", "true",
                  "kafka.admin.oauth.token.endpoint.uri", "/token",
                  "kafka.admin.bootstrap.servers", "localhost:9092");
        }
    }

    @Test
    @Disabled
    /*
     * This test will ensure that the generated API models in /.openapi are always current
     * with the model generated by the application at run time.
     */
    void testOpenApiContractMatchesGenerated() throws Exception {
        ObjectMapper yamlReader = new ObjectMapper(new YAMLFactory());
        Object obj = yamlReader.readValue(Path.of(".openapi", "kafka-admin-rest.yaml").toFile(), Object.class);

        ObjectMapper jsonWriter = new ObjectMapper();
        String expectedJson = jsonWriter.writeValueAsString(obj);

        var response = given()
                .log().ifValidationFails()
            .when()
                .get("/api/v1/openapi?format=JSON")
            .then()
                .log().ifValidationFails()
            .assertThat()
                .statusCode(Status.OK.getStatusCode());

        String actualJson = response.extract().body().asString();

        JSONAssert.assertEquals("Generated OpenAPI does not match runtime value."
                + "Ensure `mvn -Popenapi-generate process-classes` is run before running systemtests\n",
                expectedJson, actualJson, new DynamicPropertyComparator());
    }

    static class DynamicPropertyComparator extends DefaultComparator {
        public DynamicPropertyComparator() {
            super(JSONCompareMode.STRICT);
        }

        @Override
        public void compareValues(String prefix, Object expectedValue, Object actualValue, JSONCompareResult result) {
            switch (prefix) {
                /*
                 * The version and token URL are dynamic, accept differences for this test
                 */
                case "info.version":
                case "components.securitySchemes.Bearer.flows.clientCredentials.tokenUrl":
                    break;
                default:
                    try {
                        super.compareValues(prefix, expectedValue, actualValue, result);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    break;
            }
        }
    }

}
