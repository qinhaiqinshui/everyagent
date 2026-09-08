package dev.everyagent.contract.json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * 共享 JSON 工具。Jackson 3:异常均为 unchecked JacksonException。
 */
public final class Json {

    public static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private Json() {
    }

    public static JsonNode parse(String text) {
        return MAPPER.readTree(text);
    }

    public static JsonNode parse(byte[] bytes) {
        return MAPPER.readTree(bytes);
    }

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    public static <T> T convert(JsonNode node, Class<T> type) {
        return MAPPER.convertValue(node, type);
    }

    public static JsonNode toJson(Object value) {
        return MAPPER.valueToTree(value);
    }

    public static ObjectNode obj() {
        return JsonNodeFactory.instance.objectNode();
    }

    public static ArrayNode arr() {
        return JsonNodeFactory.instance.arrayNode();
    }
}
