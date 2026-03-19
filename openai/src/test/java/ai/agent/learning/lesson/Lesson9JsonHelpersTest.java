package ai.agent.learning.lesson;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lesson9RunSimple - JSON辅助方法单元测试
 */
class Lesson9JsonHelpersTest {

    @Test
    @DisplayName("测试解析简单JSON对象")
    void testParseSimpleJson() {
        String json = "{\"name\":\"test\",\"value\":123}";
        Map<String, Object> result = Lesson9RunSimple.parseJsonToMap(json);

        assertEquals("test", result.get("name"));
        assertEquals(123, result.get("value"));
    }

    @Test
    @DisplayName("测试解析包含特殊字符的JSON")
    void testParseJsonWithSpecialChars() {
        String json = "{\"text\":\"hello\\\"world\"}";
        Map<String, Object> result = Lesson9RunSimple.parseJsonToMap(json);

        assertEquals("hello\"world", result.get("text"));
    }

    @Test
    @DisplayName("测试解析空JSON对象")
    void testParseEmptyJson() {
        String json = "{}";
        Map<String, Object> result = Lesson9RunSimple.parseJsonToMap(json);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("测试Map转JSON")
    void testMapToJson() {
        Map<String, Object> map = Map.of(
                "name", "test",
                "count", 42
        );

        String json = Lesson9RunSimple.mapToJson(map);

        assertTrue(json.contains("\"name\":\"test\""));
        assertTrue(json.contains("\"count\":42"));
    }

    @Test
    @DisplayName("测试List转JSON")
    void testListToJson() {
        List<Map<String, Object>> list = List.of(
                Map.of("id", "1", "status", "pending"),
                Map.of("id", "2", "status", "completed")
        );

        String json = Lesson9RunSimple.listToJson(list);

        assertTrue(json.startsWith("["));
        assertTrue(json.endsWith("]"));
        assertTrue(json.contains("\"id\":\"1\""));
        assertTrue(json.contains("\"id\":\"2\""));
    }

    @Test
    @DisplayName("测试解析嵌套JSON")
    void testParseNestedJson() {
        String json = "{\"outer\":{\"inner\":\"value\"}}";
        Map<String, Object> result = Lesson9RunSimple.parseJsonToMap(json);

        @SuppressWarnings("unchecked")
        Map<String, Object> outer = (Map<String, Object>) result.get("outer");
        assertNotNull(outer);
    }

    @Test
    @DisplayName("测试解析数组值")
    void testParseArrayValue() {
        String json = "{\"items\":[\"a\",\"b\",\"c\"]}";
        Map<String, Object> result = Lesson9RunSimple.parseJsonToMap(json);

        @SuppressWarnings("unchecked")
        List<Object> items = (List<Object>) result.get("items");
        assertNotNull(items);
        assertEquals(3, items.size());
    }

    @Test
    @DisplayName("测试valueToJson处理null")
    void testValueToJsonNull() {
        String result = Lesson9RunSimple.valueToJson(null);
        assertEquals("null", result);
    }

    @Test
    @DisplayName("测试valueToJson处理字符串")
    void testValueToJsonString() {
        String result = Lesson9RunSimple.valueToJson("hello\"world");
        assertEquals("\"hello\\\"world\"", result);
    }

    @Test
    @DisplayName("测试valueToJson处理数字")
    void testValueToJsonNumber() {
        String result = Lesson9RunSimple.valueToJson(42.5);
        assertEquals("42.5", result);
    }
}
