package com.pdd.bestlocate;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

/**
 * Test cases against example.xml (QQ chat screen, 1200x2670).
 */
public class BestLocatorTest {

    private static BestLocator locator;
    private static Map<String, HierarchyNode> nodeMap;

    @BeforeClass
    public static void setUp() throws Exception {
        String path = System.getProperty("example.xml", "example.xml");
        locator = BestLocator.fromFile(path);
        nodeMap = locator.getNodeMap();
        assertNotNull(locator.getRoot());
        assertTrue("node map should not be empty", nodeMap.size() > 10);
        System.out.println("Loaded " + nodeMap.size() + " nodes");
    }

    // ── helper ──

    /** Find the first node whose property equals value. */
    private String findKey(String propName, String propValue) {
        for (HierarchyNode n : nodeMap.values()) {
            String v = n.getProperties().get(propName);
            if (propValue.equals(v)) return n.getKey();
        }
        return null;
    }

    private void assertBest(String key, String expectedType) {
        LocatorResult r = locator.bestLocate(key);
        assertNotNull("result should not be null for key=" + key, r);
        assertEquals("type mismatch for key=" + key, expectedType, r.getType());
        assertNotNull("value should not be null", r.getValue());
        assertFalse("value should not be empty", r.getValue().isEmpty());
        System.out.println("  [" + key + "] → " + r);
    }

    // ================================================================
    //  Test cases
    // ================================================================

    @Test
    public void testBackButton_contentDesc() {
        // <node content-desc="返回消息未读4" class="android.widget.TextView" ... />
        String key = findKey("content-desc", "返回消息未读4");
        assertNotNull("back button should exist", key);
        System.out.println("Back button key=" + key);
        assertBest(key, "content_desc");
    }

    @Test
    public void testTitle_resourceId() {
        // <node text="多多传输" resource-id="com.tencent.mobileqq:id/20r" ... />
        // resource-id takes priority over text in resolveLocatorByPriorityFast
        String key = findKey("text", "多多传输");
        assertNotNull("title should exist", key);
        System.out.println("Title key=" + key);
        assertBest(key, "resource_id");
    }

    @Test
    public void testSettingsButton_contentDesc() {
        // <node content-desc="聊天设置" class="android.widget.ImageView" ... />
        String key = findKey("content-desc", "聊天设置");
        assertNotNull("settings button should exist", key);
        System.out.println("Settings key=" + key);
        assertBest(key, "content_desc");
    }

    @Test
    public void testVoiceButton_contentDesc() {
        // <node content-desc="语音" class="android.widget.ImageButton" ... />
        String key = findKey("content-desc", "语音");
        assertNotNull("voice button should exist", key);
        System.out.println("Voice btn key=" + key);
        assertBest(key, "content_desc");
    }

    @Test
    public void testEmojiButton_contentDesc() {
        // <node content-desc="表情" class="android.widget.ImageButton" ... />
        String key = findKey("content-desc", "表情");
        assertNotNull("emoji button should exist", key);
        System.out.println("Emoji btn key=" + key);
        assertBest(key, "content_desc");
    }

    @Test
    public void testMoreButton_contentDesc() {
        // <node content-desc="更多功能" class="android.widget.ImageButton" ... />
        String key = findKey("content-desc", "更多功能");
        assertNotNull("more button should exist", key);
        System.out.println("More btn key=" + key);
        assertBest(key, "content_desc");
    }

    @Test
    public void testEditText_resourceId() {
        // <node resource-id="com.tencent.mobileqq:id/input" class="android.widget.EditText" ... />
        String key = findKey("resource-id", "com.tencent.mobileqq:id/input");
        assertNotNull("input edittext should exist", key);
        System.out.println("EditText key=" + key);
        assertBest(key, "resource_id");
    }

    @Test
    public void testHeadsetModeIcon_contentDesc() {
        // <node content-desc="听筒模式" ... />
        String key = findKey("content-desc", "听筒模式");
        assertNotNull("headset icon should exist", key);
        System.out.println("Headset icon key=" + key);
        assertBest(key, "content_desc");
    }

    @Test
    public void testImage_contentDesc_multipleMatches() {
        // There are multiple "图片" content-desc nodes (image bubbles)
        // The first match might not be unique → check that a result is still produced
        String key = findKey("content-desc", "图片");
        assertNotNull("image bubble should exist", key);
        System.out.println("Image key=" + key);
        // With multiple matches, should fall back to resource-id or xpath (not empty)
        LocatorResult r = locator.bestLocate(key);
        assertNotNull(r);
        assertFalse(r.getValue().isEmpty());
        System.out.println("  Image result: " + r);
    }

    @Test
    public void testNameLabel_text() {
        // <node text="玱枝" resource-id="com.tencent.mobileqq:id/mj3" ... />
        String key = findKey("text", "玱枝");
        assertNotNull("name label should exist", key);
        System.out.println("Name label key=" + key);
        // "玱枝" appears multiple times, but has resource-id too → should get resource_id or text
        LocatorResult r = locator.bestLocate(key);
        assertNotNull(r);
        assertFalse(r.getValue().isEmpty());
        System.out.println("  Name label result: " + r);
    }

    @Test
    public void testOwnerLabel_text() {
        // <node text="群主" ... /> (appears at least twice)
        // No resource-id, text="群主"
        String key = findKey("text", "群主");
        assertNotNull("群主 label should exist", key);
        System.out.println("Owner label key=" + key);
        LocatorResult r = locator.bestLocate(key);
        assertNotNull(r);
        assertFalse(r.getValue().isEmpty());
        System.out.println("  Owner label result: " + r);
    }

    @Test
    public void testGetAllCandidates() {
        // Pick a node with rich properties and verify candidates are generated
        String key = findKey("content-desc", "聊天设置");
        assertNotNull(key);

        List<BestLocator.XPathCandidate> cands = locator.getAllCandidates(key);
        assertNotNull(cands);
        assertFalse("should have at least one candidate", cands.isEmpty());

        System.out.println("Candidates for settings button (" + key + "):");
        for (BestLocator.XPathCandidate xc : cands) {
            System.out.println("  " + xc);
        }

        // Check there's a content-desc candidate
        boolean hasCD = false;
        for (BestLocator.XPathCandidate xc : cands) {
            if (xc.locateType == LocateType.CONTENT_DESC) { hasCD = true; break; }
        }
        assertTrue("should have content-desc candidate", hasCD);
    }

    @Test
    public void testRootNodeHasFallbackXpath() {
        // Root node has no useful properties → should fall back to class or xpath
        String rootKey = locator.getRoot().getKey();
        LocatorResult r = locator.bestLocate(rootKey);
        assertNotNull(r);
        assertFalse(r.getValue().isEmpty());
        System.out.println("Root node result: " + r);
    }

    @Test
    public void testNonExistentKeyThrows() {
        try {
            locator.bestLocate("999-999-999");
            fail("should throw for non-existent key");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    // ================================================================
    //  Structural tests
    // ================================================================

    @Test
    public void testEveryNodeHasName() {
        for (HierarchyNode n : nodeMap.values()) {
            assertNotNull("node should have a name", n.getName());
        }
    }

    @Test
    public void testEveryNodeHasKey() {
        for (HierarchyNode n : nodeMap.values()) {
            assertNotNull("node should have a key", n.getKey());
        }
    }

    @Test
    public void testBoundsOrRectPresent() {
        int withBounds = 0, withRect = 0;
        for (HierarchyNode n : nodeMap.values()) {
            if (n.getBounds() != null && n.getBounds().size() == 4) withBounds++;
            if (n.getRect() != null) withRect++;
        }
        assertTrue("at least some nodes should have bounds", withBounds > 10);
        assertTrue("at least some nodes should have rect", withRect > 10);
        System.out.println("Nodes with bounds: " + withBounds + ", with rect: " + withRect);
    }

    @Test
    public void testAllNodesHaveBestLocator() {
        int succeeded = 0;
        int failed = 0;
        for (String key : nodeMap.keySet()) {
            try {
                LocatorResult r = locator.bestLocate(key);
                if (r != null && !r.getValue().isEmpty()) {
                    succeeded++;
                } else {
                    failed++;
                    System.err.println("  empty result for key=" + key);
                }
            } catch (Exception e) {
                failed++;
                System.err.println("  error for key=" + key + ": " + e.getMessage());
            }
        }
        System.out.println("bestLocate succeeded: " + succeeded + " / " + nodeMap.size()
                + " (failed: " + failed + ")");
        assertEquals("all nodes should produce a result", 0, failed);
    }
}
