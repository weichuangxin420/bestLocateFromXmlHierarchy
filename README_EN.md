# bestLocateFromXmlHierarchy

Read an Android UIAutomator hierarchy XML and return the **best locator** for any given node.

The algorithm is a port of the `suggest_xpath()` + `resolveLocatorByPriorityFast()` flow from [uiautodev](https://github.com/nicepkg/uiautodev).

## Quick start

**Java**

```java
BestLocator locator = BestLocator.fromFile("example.xml");
LocatorResult result = locator.bestLocate("0-0-0-1-0-2");
// result.getType()  → "content_desc" | "resource_id" | "text" | "xpath"
// result.getValue() → actual locator value
```

**Python**

```python
from bestlocate import BestLocator

locator = BestLocator.from_file("example.xml")
result = locator.best_locate("0-0-0-1-0-2")
# result.type  → "content_desc" | "resource_id" | "text" | "xpath"
# result.value → actual locator value
```

## Algorithm

Given an Android hierarchy XML and a node key, the algorithm works in 4 phases:

### Phase 1: XML → Hierarchy Tree

The raw UIAutomator XML is parsed into a tree of `HierarchyNode`. Each node has:
- `key` — DFS sibling-index path (e.g. `"0-0-1-2"`)
- `name` — the `class` attribute (e.g. `"android.widget.Button"`)
- `properties` — all XML attributes (`resource-id`, `text`, `content-desc`, `clickable`, etc.)
- `bounds` — normalized coordinates `[x1, y1, x2, y2]` in 0–1 range

```
<?xml version='1.0' encoding='UTF-8'?>
<hierarchy rotation="0">
  <node index="0" class="android.widget.FrameLayout" bounds="[0,0][1200,2670]">
    <node index="0" class="android.widget.LinearLayout" bounds="[0,0][1200,2670]">
      <node index="0" class="android.widget.TextView"
            text="首页" resource-id="com.example:id/title"
            content-desc="页面标题" bounds="[20,80][400,130]" />
      ...
```

```
key                            name                      bounds (norm)
──                            ────                      ─────────────
0                             FrameLayout              [0, 0, 1.0, 1.0]
└── 0-0                       LinearLayout             [0, 0, 1.0, 1.0]
    ├── 0-0-0                 TextView                 [0.018, 0.041, 0.370, 0.067]
    │       text="首页", resource-id="com.example:id/title", content-desc="页面标题"
    └── 0-0-1                 RecyclerView             [0, 0.067, 1.0, 1.0]
        └── 0-0-1-0           LinearLayout             [0, 0.067, 1.0, 0.156]
            ├── 0-0-1-0-0     ImageView                [0.018, 0.072, 0.259, 0.151]
            │       content-desc="商品图片"
            ├── 0-0-1-0-1     TextView                 [0.278, 0.072, 0.833, 0.104]
            │       text="精品咖啡", resource-id="com.example:id/item_title"
            └── 0-0-1-0-2     Button                   [0.278, 0.109, 0.556, 0.146]
                    text="立即购买", resource-id="com.example:id/item_btn",
                    content-desc="购买按钮", clickable=true
```

### Phase 2: Tree → Flat Index

The tree is flattened via pre-order traversal into three lookup structures:

```
nodeMap:   key → node        (O(1) lookup by key)
allNodes:  [node, node, ...] (flat list for matching)
```

### Phase 3: `suggest_xpath()`

#### 3a — Determine candidate strategies

Based on which attributes the target node has:

| Attribute present | Strategy |
|---|---|
| _always_ | `class` |
| resource-id | `id` |
| content-desc | `content-desc` |
| text | `text`, `class_and_text` |
| content-desc + class | `class_and_content_desc` |

#### 3b — Sort by match count

For each strategy, count how many nodes in `allNodes` have the same value. Sort asc — fewer matches means more specific:

```
selected = "立即购买" Button (key=0-0-1-0-2)

strategy                match count
────────                ───────────
id          → "item_btn"    → 1   ← unique
content-desc → "购买按钮"   → 1   ← unique
text        → "立即购买"    → 1   ← unique
class       → Button        → 1   ← unique
```

Preferred by = `id` (first in sorted order).

#### 3c — Build XPath expressions

```
id             → //*[@resource-id="com.example:id/item_btn"]
content-desc   → //*[@content-desc="购买按钮"]
text           → //*[@text="立即购买"]
class_and_text → //android.widget.Button[@text="立即购买"]
class          → //android.widget.Button
```

If a node is not the first match, an index is appended: `(//android.widget.Button)[3]`.

#### 3d — Complex XPath candidates

When simple XPaths are not unique, the algorithm builds anchored expressions:

**Parent-anchored** — using a parent node that _does_ have a unique attribute:

```xpath
(//*[@resource-id="android:id/content"]/*[3])
(//*[@resource-id="android:id/content"]/android.widget.Button)[2]
```

**Sibling-anchored** — using a unique sibling as a reference point:

```xpath
(//*[@content-desc="商品图片"]/following-sibling::android.widget.Button)[2]
(//*[@text="精品咖啡"]/following-sibling::android.widget.Button)[1]
```

### Phase 4: `resolvePriorityFast()`

Candidates are tested in a fixed priority order. The **first candidate whose strategy matches AND whose property value is non-empty** wins:

```
content-desc  →  resource-id  →  text  →  class-text
→  class-content-desc  →  class  →  xpath
```

For the "立即购买" Button, `content-desc` is checked first — "购买按钮" is non-empty — **done**.

```
LocatorResult(type="content_desc", value="购买按钮")
```

The corresponding test code:

```python
locator.click_element_by_content_desc("购买按钮")
```

## XPath literal quoting

Handles the edge case where a value contains both `"` and `'`:

| Value | XPath literal |
|---|---|
| `hello` | `"hello"` |
| `it's` | `"it's"` |
| `he said "hi"` | `'he said "hi"'` |
| `a"b'c` | `concat("a", '"', "b'c")` |

## Runtime

- Parse & annotate all nodes: O(n)
- `matchesBy` lookups: O(1) via pre-built inverted indexes (resource-id, text, content-desc, class)
- `best_locate` hot path: returns on first simple candidate hit, skipping complex xpath generation

Zero external dependencies in both Java and Python versions.

## Project structure

```
bestLocateFromXmlHierarchy/
├── pom.xml                           ← Maven project (Java)
├── example.xml                        ← sample QQ chat hierarchy (33 KB, 90 nodes)
├── README.md
│
├── java/src/
│   ├── main/java/com/pdd/bestlocate/
│   │   ├── BestLocator.java           ← core algorithm
│   │   ├── HierarchyNode.java         ← tree node model
│   │   ├── HierarchyRect.java         ← rectangle model
│   │   └── LocatorResult.java         ← output (type + value)
│   └── test/java/com/pdd/bestlocate/
│       └── BestLocatorTest.java       ← 18 tests
│
└── python/
    ├── bestlocate/
    │   ├── __init__.py
    │   └── bestlocator.py             ← core algorithm (pure stdlib)
    └── tests/
        └── test_bestlocator.py        ← 18 tests
```

## Tests

Both Java and Python have the same 18 test cases:

| Test | Expected type |
|---|---|
| Back button `content-desc="返回消息未读4"` | `content_desc` |
| Title `text="多多传输"` + resource-id | `resource_id` |
| Settings `content-desc="聊天设置"` | `content_desc` |
| Voice `content-desc="语音"` | `content_desc` |
| Emoji `content-desc="表情"` | `content_desc` |
| More `content-desc="更多功能"` | `content_desc` |
| EditText `resource-id="...input"` | `resource_id` |
| Headset `content-desc="听筒模式"` | `content_desc` |
| Image `content-desc="图片"` (multiple) | any valid |
| Name label `text="玱枝"` + resource-id | any valid |
| Owner label `text="群主"` (multiple) | text or xpath |
| All candidates generated | at least 1 |
| Root node fallback | any valid |
| Non-existent key throws | — |
| Every node has name | — |
| Every node has key | — |
| Bounds present | — |
| All 90 nodes produce a result | — |

Run tests:

```bash
# Java
cd bestLocateFromXmlHierarchy
mvn compile test-compile
java -cp "target/classes:target/test-classes:..." org.junit.runner.JUnitCore \
     com.wcx.bestlocate.BestLocatorTest

# Python
python -m pytest python/tests/test_bestlocator.py -v
```
