"""Test cases against example.xml (QQ chat screen, 1200x2670)."""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from bestlocate import BestLocator, LocateType, LocatorResult

EXAMPLE_XML = os.path.join(
    os.path.dirname(__file__), "..", "..", "example.xml"
)


class TestBestLocator(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.locator = BestLocator.from_file(EXAMPLE_XML)
        cls.node_map = cls.locator.node_map
        assert len(cls.node_map) > 10, "node map should not be empty"
        print(f"\nLoaded {len(cls.node_map)} nodes")

    # ── helpers ──

    def _find_key(self, prop_name: str, prop_value: str) -> str:
        for node in self.node_map.values():
            if node.properties.get(prop_name) == prop_value:
                return node.key
        return ""

    def _assert_best(self, key: str, expected_type: str) -> None:
        result = self.locator.best_locate(key)
        self.assertIsNotNone(result, f"result should not be None for key={key}")
        self.assertEqual(
            expected_type, result.type,
            f"type mismatch for key={key}: expected {expected_type}, got {result.type}"
        )
        self.assertTrue(result.value, f"value should not be empty for key={key}")
        print(f"  [{key}] -> {result}")

    # ================================================================
    #  Test cases
    # ================================================================

    def test_back_button_content_desc(self) -> None:
        key = self._find_key("content-desc", "返回消息未读4")
        self.assertTrue(key, "back button should exist")
        print(f"Back button key={key}")
        self._assert_best(key, "content_desc")

    def test_title_resource_id(self) -> None:
        key = self._find_key("text", "多多传输")
        self.assertTrue(key, "title should exist")
        print(f"Title key={key}")
        self._assert_best(key, "resource_id")

    def test_settings_button_content_desc(self) -> None:
        key = self._find_key("content-desc", "聊天设置")
        self.assertTrue(key, "settings button should exist")
        print(f"Settings key={key}")
        self._assert_best(key, "content_desc")

    def test_voice_button_content_desc(self) -> None:
        key = self._find_key("content-desc", "语音")
        self.assertTrue(key, "voice button should exist")
        print(f"Voice btn key={key}")
        self._assert_best(key, "content_desc")

    def test_emoji_button_content_desc(self) -> None:
        key = self._find_key("content-desc", "表情")
        self.assertTrue(key, "emoji button should exist")
        print(f"Emoji btn key={key}")
        self._assert_best(key, "content_desc")

    def test_more_button_content_desc(self) -> None:
        key = self._find_key("content-desc", "更多功能")
        self.assertTrue(key, "more button should exist")
        print(f"More btn key={key}")
        self._assert_best(key, "content_desc")

    def test_edittext_resource_id(self) -> None:
        key = self._find_key("resource-id", "com.tencent.mobileqq:id/input")
        self.assertTrue(key, "input edittext should exist")
        print(f"EditText key={key}")
        self._assert_best(key, "resource_id")

    def test_headset_icon_content_desc(self) -> None:
        key = self._find_key("content-desc", "听筒模式")
        self.assertTrue(key, "headset icon should exist")
        print(f"Headset icon key={key}")
        self._assert_best(key, "content_desc")

    def test_image_content_desc_multiple(self) -> None:
        key = self._find_key("content-desc", "图片")
        self.assertTrue(key, "image bubble should exist")
        print(f"Image key={key}")
        result = self.locator.best_locate(key)
        self.assertIsNotNone(result)
        self.assertTrue(result.value)
        print(f"  Image result: {result}")

    def test_name_label(self) -> None:
        key = self._find_key("text", "玱枝")
        self.assertTrue(key, "name label should exist")
        print(f"Name label key={key}")
        result = self.locator.best_locate(key)
        self.assertIsNotNone(result)
        self.assertTrue(result.value)
        print(f"  Name label result: {result}")

    def test_owner_label_text(self) -> None:
        key = self._find_key("text", "群主")
        self.assertTrue(key, "owner label should exist")
        print(f"Owner label key={key}")
        result = self.locator.best_locate(key)
        self.assertIsNotNone(result)
        self.assertTrue(result.value)
        self.assertIn(result.type, ("text", "xpath"))
        print(f"  Owner label result: {result}")

    def test_all_candidates(self) -> None:
        key = self._find_key("content-desc", "聊天设置")
        self.assertTrue(key)
        cands = self.locator.all_candidates(key)
        self.assertTrue(cands, "should have at least one candidate")

        print(f"Candidates for settings button ({key}):")
        for xc in cands:
            print(f"  {xc}")

        has_cd = any(xc.locate_type == LocateType.CONTENT_DESC for xc in cands)
        self.assertTrue(has_cd, "should have content-desc candidate")

    def test_root_node_fallback(self) -> None:
        root_key = self.locator.root.key
        result = self.locator.best_locate(root_key)
        self.assertIsNotNone(result)
        self.assertTrue(result.value)
        print(f"Root node result: {result}")

    def test_nonexistent_key(self) -> None:
        with self.assertRaises(ValueError):
            self.locator.best_locate("999-999-999")

    # ================================================================
    #  Structural tests
    # ================================================================

    def test_every_node_has_name(self) -> None:
        for node in self.node_map.values():
            self.assertIsNotNone(node.name, f"node {node.key} should have a name")

    def test_every_node_has_key(self) -> None:
        for node in self.node_map.values():
            self.assertTrue(node.key, f"node should have a key")

    def test_bounds_present(self) -> None:
        cnt = sum(1 for n in self.node_map.values()
                  if n.bounds and len(n.bounds) == 4)
        self.assertGreater(cnt, 10, "at least some nodes should have bounds")
        print(f"Nodes with bounds: {cnt}")

    def test_all_nodes_have_best_locate(self) -> None:
        succeeded = 0
        failed = 0
        for key, node in self.node_map.items():
            try:
                result = self.locator.best_locate(key)
                if result and result.value:
                    succeeded += 1
                else:
                    failed += 1
                    print(f"  empty result for key={key}")
            except Exception as e:
                failed += 1
                print(f"  error for key={key}: {e}")
        print(f"best_locate succeeded: {succeeded} / {len(self.node_map)} "
              f"(failed: {failed})")
        self.assertEqual(0, failed, "all nodes should produce a result")


if __name__ == "__main__":
    unittest.main(verbosity=2)
