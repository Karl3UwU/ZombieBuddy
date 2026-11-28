package io.github.karl3uwu.zombud.session;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/**
 * The example files agree with schema/session-v1.schema.json (test-only validator dependency). The Java reader is
 * checked against the same files in SessionFileTest, so schema and reader can't drift apart unnoticed.
 */
class SessionSchemaTest {
    private static final Path SCHEMA = Path.of("..", "schema", "session-v1.schema.json");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonSchema schema() throws IOException {
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(Files.readString(SCHEMA), config);
    }

    static Stream<Path> validExamples() throws IOException {
        return Files.list(SessionFileTest.EXAMPLES).filter(p -> p.toString().endsWith(".json")).sorted();
    }

    static Stream<Path> invalidExamples() throws IOException {
        // not-json.json can't even be parsed, so there is nothing to validate
        return SessionFileTest.invalidExamples().filter(p -> !p.getFileName().toString().equals("not-json.json"));
    }

    @ParameterizedTest
    @MethodSource("validExamples")
    void validExamplesMatchTheSchema(Path file) throws Exception {
        JsonNode node = JSON.readTree(Files.readString(file));
        Set<ValidationMessage> errors = schema().validate(node);
        assertTrue(errors.isEmpty(), file + ": " + errors);
    }

    @ParameterizedTest
    @MethodSource("invalidExamples")
    void invalidExamplesBreakTheSchema(Path file) throws Exception {
        JsonNode node = JSON.readTree(Files.readString(file));
        assertFalse(schema().validate(node).isEmpty(), file + " should not match the schema");
    }
}
