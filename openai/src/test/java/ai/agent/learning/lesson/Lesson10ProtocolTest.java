package ai.agent.learning.lesson;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lesson10RunSimple - Team Protocols (MessageBus + TeammateManager) 单元测试
 */
class Lesson10ProtocolTest {

    @TempDir
    Path tempDir;

    // =========================================================================
    // MessageBus 测试
    // =========================================================================
    @Nested
    @DisplayName("MessageBus 测试")
    class MessageBusTests {

        private Lesson10RunSimple.MessageBus bus;

        @BeforeEach
        void setUp() {
            bus = new Lesson10RunSimple.MessageBus(tempDir);
        }

        @Test
        @DisplayName("测试发送普通消息并读取收件箱")
        void testSendAndReadInbox() {
            String result = bus.send("alice", "bob", "Hello Bob", "message", null);

            assertTrue(result.contains("Sent message to bob"));

            List<Map<String, Object>> inbox = bus.readInbox("bob");
            assertEquals(1, inbox.size());
            assertEquals("alice", inbox.get(0).get("from"));
            assertEquals("Hello Bob", inbox.get(0).get("content"));
            assertEquals("message", inbox.get(0).get("type"));
        }

        @Test
        @DisplayName("测试读取空收件箱返回空列表")
        void testReadEmptyInbox() {
            List<Map<String, Object>> inbox = bus.readInbox("nonexistent");
            assertTrue(inbox.isEmpty());
        }

        @Test
        @DisplayName("测试收件箱读取后被清空")
        void testInboxDrainedAfterRead() {
            bus.send("alice", "bob", "Message 1", "message", null);
            bus.send("alice", "bob", "Message 2", "message", null);

            List<Map<String, Object>> firstRead = bus.readInbox("bob");
            assertEquals(2, firstRead.size());

            List<Map<String, Object>> secondRead = bus.readInbox("bob");
            assertTrue(secondRead.isEmpty());
        }

        @Test
        @DisplayName("测试广播消息跳过发送者本身")
        void testBroadcastSkipsSender() {
            List<String> teammates = List.of("alice", "bob", "charlie");

            String result = bus.broadcast("alice", "Team update!", teammates);
            assertTrue(result.contains("Broadcast to 2 teammates"));

            // alice (sender) should NOT receive
            List<Map<String, Object>> aliceInbox = bus.readInbox("alice");
            assertTrue(aliceInbox.isEmpty());

            // bob and charlie should receive
            List<Map<String, Object>> bobInbox = bus.readInbox("bob");
            assertEquals(1, bobInbox.size());
            assertEquals("broadcast", bobInbox.get(0).get("type"));
            assertEquals("Team update!", bobInbox.get(0).get("content"));

            List<Map<String, Object>> charlieInbox = bus.readInbox("charlie");
            assertEquals(1, charlieInbox.size());
        }

        @Test
        @DisplayName("测试广播到空列表")
        void testBroadcastToEmptyList() {
            String result = bus.broadcast("alice", "Hello?", List.of());
            assertTrue(result.contains("Broadcast to 0 teammates"));
        }

        @Test
        @DisplayName("测试发送带额外字段的消息（如 request_id）")
        void testSendWithExtraFields() {
            Map<String, Object> extra = Map.of("request_id", "req-001");
            bus.send("lead", "worker", "Please shut down.", "shutdown_request", extra);

            List<Map<String, Object>> inbox = bus.readInbox("worker");
            assertEquals(1, inbox.size());
            assertEquals("shutdown_request", inbox.get(0).get("type"));
            assertEquals("req-001", inbox.get(0).get("request_id"));
            assertEquals("Please shut down.", inbox.get(0).get("content"));
        }

        @Test
        @DisplayName("测试无效消息类型被拒绝")
        void testInvalidMessageType() {
            String result = bus.send("alice", "bob", "test", "invalid_type", null);
            assertTrue(result.contains("Error: Invalid type"));
        }

        @Test
        @DisplayName("测试所有有效消息类型")
        void testAllValidMessageTypes() {
            String[] validTypes = {"message", "broadcast", "shutdown_request", "shutdown_response", "plan_approval_response"};

            for (String type : validTypes) {
                String result = bus.send("sender", "receiver", "test", type, null);
                assertTrue(result.contains("Sent " + type), "Type '" + type + "' should be valid");
            }
        }

        @Test
        @DisplayName("测试消息包含时间戳")
        void testMessageContainsTimestamp() {
            long before = System.currentTimeMillis() / 1000;
            bus.send("alice", "bob", "ping", "message", null);
            long after = System.currentTimeMillis() / 1000;

            List<Map<String, Object>> inbox = bus.readInbox("bob");
            double ts = ((Number) inbox.get(0).get("timestamp")).doubleValue();
            assertTrue(ts >= before);
            assertTrue(ts <= after + 1);
        }

        @Test
        @DisplayName("测试多用户独立收件箱")
        void testIndependentInboxes() {
            bus.send("alice", "bob", "Hi Bob", "message", null);
            bus.send("bob", "charlie", "Hi Charlie", "message", null);

            List<Map<String, Object>> bobInbox = bus.readInbox("bob");
            assertEquals(1, bobInbox.size());
            assertEquals("Hi Bob", bobInbox.get(0).get("content"));

            List<Map<String, Object>> charlieInbox = bus.readInbox("charlie");
            assertEquals(1, charlieInbox.size());
            assertEquals("Hi Charlie", charlieInbox.get(0).get("content"));
        }
    }

    // =========================================================================
    // TeammateManager 测试 (不涉及 OpenAI 客户端调用)
    // =========================================================================
    @Nested
    @DisplayName("TeammateManager 测试")
    class TeammateManagerTests {

        private Lesson10RunSimple.MessageBus bus;
        private Lesson10RunSimple.TeammateManager manager;
        private Path teamDir;

        @BeforeEach
        void setUp() {
            teamDir = tempDir.resolve("team");
            Path inboxDir = tempDir.resolve("inbox");
            bus = new Lesson10RunSimple.MessageBus(inboxDir);
            manager = new Lesson10RunSimple.TeammateManager(
                    teamDir, bus, null, "test-model", tempDir,
                    new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ReentrantLock()
            );
        }

        @Test
        @DisplayName("测试默认配置初始化")
        void testDefaultConfig() {
            assertNotNull(manager.config);
            assertEquals("default", manager.config.get("team_name"));
            assertNotNull(manager.config.get("members"));
            assertTrue(((List<?>) manager.config.get("members")).isEmpty());
        }

        @Test
        @DisplayName("测试空团队列表输出")
        void testListAllEmpty() {
            String result = manager.listAll();
            assertEquals("No teammates.", result);
        }

        @Test
        @DisplayName("测试空团队成员名称列表")
        void testMemberNamesEmpty() {
            List<String> names = manager.memberNames();
            assertTrue(names.isEmpty());
        }

        @Test
        @DisplayName("测试查找不存在的成员返回 null")
        void testFindMemberNotFound() {
            assertNull(manager.findMember("ghost"));
        }

        @Test
        @DisplayName("测试手动添加成员后查找")
        @SuppressWarnings("unchecked")
        void testAddAndFindMember() {
            Map<String, Object> member = new HashMap<>();
            member.put("name", "bob");
            member.put("role", "coder");
            member.put("status", "working");
            ((List<Map<String, Object>>) manager.config.get("members")).add(member);

            Map<String, Object> found = manager.findMember("bob");
            assertNotNull(found);
            assertEquals("bob", found.get("name"));
            assertEquals("coder", found.get("role"));
            assertEquals("working", found.get("status"));
        }

        @Test
        @DisplayName("测试添加多个成员后 listAll 输出")
        @SuppressWarnings("unchecked")
        void testListAllWithMembers() {
            List<Map<String, Object>> members = (List<Map<String, Object>>) manager.config.get("members");

            Map<String, Object> bob = new HashMap<>();
            bob.put("name", "bob");
            bob.put("role", "coder");
            bob.put("status", "working");
            members.add(bob);

            Map<String, Object> charlie = new HashMap<>();
            charlie.put("name", "charlie");
            charlie.put("role", "reviewer");
            charlie.put("status", "idle");
            members.add(charlie);

            String result = manager.listAll();
            assertTrue(result.contains("bob"));
            assertTrue(result.contains("coder"));
            assertTrue(result.contains("working"));
            assertTrue(result.contains("charlie"));
            assertTrue(result.contains("reviewer"));
            assertTrue(result.contains("idle"));
            assertTrue(result.startsWith("Team: default"));
        }

        @Test
        @DisplayName("测试 memberNames 返回所有成员名称")
        @SuppressWarnings("unchecked")
        void testMemberNames() {
            List<Map<String, Object>> members = (List<Map<String, Object>>) manager.config.get("members");

            Map<String, Object> alice = new HashMap<>();
            alice.put("name", "alice");
            alice.put("role", "lead");
            alice.put("status", "working");
            members.add(alice);

            Map<String, Object> bob = new HashMap<>();
            bob.put("name", "bob");
            bob.put("role", "coder");
            bob.put("status", "idle");
            members.add(bob);

            List<String> names = manager.memberNames();
            assertEquals(2, names.size());
            assertTrue(names.contains("alice"));
            assertTrue(names.contains("bob"));
        }

        @Test
        @DisplayName("测试 setStatus 更新成员状态并持久化")
        @SuppressWarnings("unchecked")
        void testSetStatusAndPersist() {
            Map<String, Object> member = new HashMap<>();
            member.put("name", "bob");
            member.put("role", "coder");
            member.put("status", "working");
            ((List<Map<String, Object>>) manager.config.get("members")).add(member);

            manager.setStatus("bob", "shutdown");

            assertEquals("shutdown", manager.findMember("bob").get("status"));
            assertTrue(Files.exists(manager.configPath), "Config file should be persisted");
        }

        @Test
        @DisplayName("测试 setStatus 对不存在的成员无操作")
        void testSetStatusUnknownMember() {
            // Should not throw
            manager.setStatus("ghost", "idle");
            assertNull(manager.findMember("ghost"));
        }

        @Test
        @DisplayName("测试 saveConfig 和 loadConfig 往返")
        @SuppressWarnings("unchecked")
        void testSaveAndLoadConfigRoundTrip() {
            Map<String, Object> member = new HashMap<>();
            member.put("name", "dana");
            member.put("role", "tester");
            member.put("status", "idle");
            ((List<Map<String, Object>>) manager.config.get("members")).add(member);
            manager.saveConfig();

            assertTrue(Files.exists(manager.configPath));

            // Create a new manager reading the same config directory
            Lesson10RunSimple.TeammateManager reloaded = new Lesson10RunSimple.TeammateManager(
                    teamDir, bus, null, "test-model", tempDir,
                    new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ReentrantLock()
            );

            Map<String, Object> found = reloaded.findMember("dana");
            assertNotNull(found);
            assertEquals("dana", found.get("name"));
            assertEquals("tester", found.get("role"));
            assertEquals("idle", found.get("status"));
        }

        @Test
        @DisplayName("测试从已有配置文件加载")
        void testLoadExistingConfig() throws Exception {
            String json = "{\"team_name\":\"alpha\",\"members\":[{\"name\":\"eve\",\"role\":\"ops\",\"status\":\"working\"}]}";
            Files.createDirectories(teamDir);
            Files.write(teamDir.resolve("config.json"), json.getBytes(StandardCharsets.UTF_8));

            Lesson10RunSimple.TeammateManager loaded = new Lesson10RunSimple.TeammateManager(
                    teamDir, bus, null, "test-model", tempDir,
                    new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ReentrantLock()
            );

            assertEquals("alpha", loaded.config.get("team_name"));
            assertNotNull(loaded.findMember("eve"));
            assertEquals("ops", loaded.findMember("eve").get("role"));
        }
    }
}
