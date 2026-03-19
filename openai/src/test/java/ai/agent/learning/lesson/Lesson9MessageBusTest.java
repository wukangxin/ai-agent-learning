package ai.agent.learning.lesson;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lesson9RunSimple - MessageBus 单元测试
 */
class Lesson9MessageBusTest {

    @TempDir
    Path tempDir;

    private Lesson9RunSimple.MessageBus messageBus;

    @BeforeEach
    void setUp() {
        messageBus = new Lesson9RunSimple.MessageBus(tempDir);
    }

    @Test
    @DisplayName("测试发送简单消息")
    void testSendMessage() {
        String result = messageBus.send("alice", "bob", "Hello Bob", "message", null);

        assertTrue(result.contains("Sent message to bob"));

        List<Map<String, Object>> inbox = messageBus.readInbox("bob");
        assertEquals(1, inbox.size());
        assertEquals("alice", inbox.get(0).get("from"));
        assertEquals("Hello Bob", inbox.get(0).get("content"));
        assertEquals("message", inbox.get(0).get("type"));
    }

    @Test
    @DisplayName("测试读取空收件箱")
    void testReadEmptyInbox() {
        List<Map<String, Object>> inbox = messageBus.readInbox("nonexistent");
        assertTrue(inbox.isEmpty());
    }

    @Test
    @DisplayName("测试收件箱读取后清空")
    void testInboxClearedAfterRead() {
        messageBus.send("alice", "bob", "Message 1", "message", null);
        messageBus.send("alice", "bob", "Message 2", "message", null);

        List<Map<String, Object>> firstRead = messageBus.readInbox("bob");
        assertEquals(2, firstRead.size());

        List<Map<String, Object>> secondRead = messageBus.readInbox("bob");
        assertTrue(secondRead.isEmpty());
    }

    @Test
    @DisplayName("测试广播消息")
    void testBroadcast() {
        List<String> teammates = List.of("bob", "charlie", "alice");

        String result = messageBus.broadcast("alice", "Team meeting!", teammates);

        assertTrue(result.contains("Broadcast to 2 teammates"));

        // bob should receive
        List<Map<String, Object>> bobInbox = messageBus.readInbox("bob");
        assertEquals(1, bobInbox.size());
        assertEquals("broadcast", bobInbox.get(0).get("type"));
        assertEquals("Team meeting!", bobInbox.get(0).get("content"));

        // charlie should receive
        List<Map<String, Object>> charlieInbox = messageBus.readInbox("charlie");
        assertEquals(1, charlieInbox.size());
        assertEquals("broadcast", charlieInbox.get(0).get("type"));
    }

    @Test
    @DisplayName("测试发送带额外字段的消息")
    void testSendMessageWithExtra() {
        Map<String, Object> extra = Map.of("request_id", "req-123", "approve", true);

        messageBus.send("alice", "bob", "Approval", "plan_approval_response", extra);

        List<Map<String, Object>> inbox = messageBus.readInbox("bob");
        assertEquals(1, inbox.size());
        assertEquals("req-123", inbox.get(0).get("request_id"));
        assertEquals(true, Boolean.valueOf(inbox.get(0).get("approve").toString()));
    }

    @Test
    @DisplayName("测试无效消息类型")
    void testInvalidMessageType() {
        String result = messageBus.send("alice", "bob", "test", "invalid_type", null);

        assertTrue(result.contains("Error: Invalid type"));
    }

    @Test
    @DisplayName("测试消息时间戳")
    void testMessageTimestamp() {
        long beforeTime = System.currentTimeMillis() / 1000;
        messageBus.send("alice", "bob", "test", "message", null);
        long afterTime = System.currentTimeMillis() / 1000;

        List<Map<String, Object>> inbox = messageBus.readInbox("bob");
        double timestamp = ((Number) inbox.get(0).get("timestamp")).doubleValue();

        assertTrue(timestamp >= beforeTime);
        assertTrue(timestamp <= afterTime + 1);
    }

    @Test
    @DisplayName("测试多用户独立收件箱")
    void testMultipleInboxes() {
        messageBus.send("alice", "bob", "Hi Bob", "message", null);
        messageBus.send("alice", "charlie", "Hi Charlie", "message", null);
        messageBus.send("bob", "alice", "Hi Alice", "message", null);

        List<Map<String, Object>> bobInbox = messageBus.readInbox("bob");
        assertEquals(1, bobInbox.size());
        assertEquals("Hi Bob", bobInbox.get(0).get("content"));

        List<Map<String, Object>> charlieInbox = messageBus.readInbox("charlie");
        assertEquals(1, charlieInbox.size());
        assertEquals("Hi Charlie", charlieInbox.get(0).get("content"));

        List<Map<String, Object>> aliceInbox = messageBus.readInbox("alice");
        assertEquals(1, aliceInbox.size());
        assertEquals("Hi Alice", aliceInbox.get(0).get("content"));
    }

    @Test
    @DisplayName("测试所有有效消息类型")
    void testAllValidMessageTypes() {
        String[] validTypes = {"message", "broadcast", "shutdown_request", "shutdown_response", "plan_approval_response"};

        for (String type : validTypes) {
            String result = messageBus.send("sender", "receiver", "test", type, null);
            assertTrue(result.contains("Sent " + type), "Type " + type + " should be valid");
        }
    }
}
