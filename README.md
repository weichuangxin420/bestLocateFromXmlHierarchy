# bestLocateFromXmlHierarchy

对于自动化测试，获取元素的最佳定位永远是最基础的，由此针对安卓提出以下简单算法：
输入 Android UIAutomator 层级 XML，对任意节点**自动推断最佳定位器**。
补充：获取层级可用：adb shell uiautomator dump /data/local/tmp/uidump.xml


[English version](README_EN.md)

## 快速开始

**Java**

```java
BestLocator locator = BestLocator.fromFile("example.xml");
LocatorResult result = locator.bestLocate("0-0-0-1-0-2");
// result.getType()  → "content_desc" | "resource_id" | "text" | "xpath"
// result.getValue() → 实际的定位值
```

**Python**

```python
from bestlocate import BestLocator

locator = BestLocator.from_file("example.xml")
result = locator.best_locate("0-0-0-1-0-2")
# result.type  → "content_desc" | "resource_id" | "text" | "xpath"
# result.value → 实际的定位值
```

## 算法流程

给定层级 XML 和目标节点 key，分 4 个阶段：

### Phase 1: XML → 层级树

原始 UIAutomator XML 解析为 `HierarchyNode` 树，每个节点包含：
- `key` — DFS 兄弟索引路径（如 `"0-0-1-2"`）
- `name` — `class` 属性值（如 `"android.widget.Button"`）
- `properties` — 全部 XML 属性（`resource-id`、`text`、`content-desc`、`clickable` 等）
- `bounds` — 归一化坐标 `[x1, y1, x2, y2]`（0~1 范围）

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
key                            name                      归一化 bounds
──                            ────                      ───────────────
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

### Phase 2: 树 → 扁平索引

前序遍历，建立 3 个查找结构 + 4 个属性倒排索引：

```
nodeMap:   key → node           (O(1) 按键查找)
allNodes:  [node, node, ...]    (扁平列表)
byRid:     resource-id → nodes  (O(1) 按属性查找)
byText:    text → nodes
byCd:      content-desc → nodes
byClass:   class → nodes
```

### Phase 3: `suggest_xpath()`

#### 3a — 确定候选策略

根据目标节点具有哪些属性决定可用策略：

| 具有的属性 | 策略 |
|---|---|
| _始终_ | `class` |
| resource-id | `id` |
| content-desc | `content-desc` |
| text | `text`、`class_and_text` |
| content-desc + class | `class_and_content_desc` |

#### 3b — 按匹配数升序排序

每个策略在全量节点中统计命中了多少。排序后匹配数最少的排最前（越唯一越优）：

```
选中 = "立即购买" Button (key=0-0-1-0-2)

策略                  匹配数
────────              ──────
id          → 1       ← 唯一
content-desc → 1      ← 唯一
text        → 1       ← 唯一
class       → 1       ← 唯一
```

preferredBy = `id`（排序后第一个）。

#### 3c — 构建 XPath 表达式

```
id             → //*[@resource-id="com.example:id/item_btn"]
content-desc   → //*[@content-desc="购买按钮"]
text           → //*[@text="立即购买"]
class_and_text → //android.widget.Button[@text="立即购买"]
class          → //android.widget.Button
```

若非第一个匹配节点，追加索引：`(//android.widget.Button)[3]`。

#### 3d — 复杂 XPath 候选

当简单 XPath 不够唯一时，生成锚定表达式：

**父级锚定** — 用唯一可定位的父节点作参照：

```xpath
(//*[@resource-id="android:id/content"]/*[3])
(//*[@resource-id="android:id/content"]/android.widget.Button)[2]
```

**兄弟锚定** — 用唯一定位的兄弟节点作参照：

```xpath
(//*[@content-desc="商品图片"]/following-sibling::android.widget.Button)[2]
(//*[@text="精品咖啡"]/following-sibling::android.widget.Button)[1]
```

### Phase 4: `resolvePriorityFast()`

按固定优先级链选取。**第一个策略匹配且属性值非空**即返回：

```
content-desc  →  resource-id  →  text  →  class-text
→  class-content-desc  →  class  →  xpath
```

对"立即购买" Button，先检查 `content-desc`——"购买按钮"非空——**直接命中**：

```
LocatorResult(type="content_desc", value="购买按钮")
```

对应的测试代码：

```python
locator.click_element_by_content_desc("购买按钮")
```

## XPath 字面量引号处理

处理属性值中同时包含 `"` 和 `'` 的边界情况：

| 原始值 | XPath 字面量 |
|---|---|
| `hello` | `"hello"` |
| `it's` | `"it's"` |
| `he said "hi"` | `'he said "hi"'` |
| `a"b'c` | `concat("a", '"', "b'c")` |

## 复杂度

- 解析标注全部节点：O(n)
- `matchesBy` 查找：O(1)，通过预建的 4 个倒排索引
- `best_locate` 热路径：简单候选命中即返回，跳过复杂 XPath 生成

Java、Python 两版均**零外部依赖**。

## 项目结构

```
bestLocateFromXmlHierarchy/
├── pom.xml                           ← Maven 工程（Java）
├── example.xml                        ← QQ 聊天界面层级样本（33KB, 90 节点）
├── README.md                          ← 当前文件（中文）
├── README_EN.md                       ← English version
│
├── java/src/
│   ├── main/java/com/pdd/bestlocate/
│   │   ├── BestLocator.java           ← 核心算法
│   │   ├── HierarchyNode.java         ← 节点模型
│   │   ├── HierarchyRect.java         ← 矩形模型
│   │   └── LocatorResult.java         ← 输出 (type + value)
│   └── test/java/com/pdd/bestlocate/
│       └── BestLocatorTest.java       ← 18 个测试
│
└── python/
    ├── bestlocate/
    │   ├── __init__.py
    │   └── bestlocator.py             ← 核心算法（纯标准库）
    └── tests/
        └── test_bestlocator.py        ← 18 个测试
```

## 测试

Java 和 Python 均包含 18 个测试用例：

| 测试 | 预期 type |
|---|---|
| 返回按钮 `content-desc="返回消息未读4"` | `content_desc` |
| 标题 `text="多多传输"` + resource-id | `resource_id` |
| 设置 `content-desc="聊天设置"` | `content_desc` |
| 语音 `content-desc="语音"` | `content_desc` |
| 表情 `content-desc="表情"` | `content_desc` |
| 更多 `content-desc="更多功能"` | `content_desc` |
| 输入框 `resource-id="...input"` | `resource_id` |
| 听筒 `content-desc="听筒模式"` | `content_desc` |
| 图片 `content-desc="图片"`（多个） | 任意有效 |
| 昵称 `text="玱枝"` + resource-id | 任意有效 |
| 群主标签 `text="群主"`（多个） | text 或 xpath |
| 候选列表生成 | 至少 1 个 |
| 根节点兜底 | 任意有效 |
| 不存在的 key 抛异常 | — |
| 每个节点都有 name | — |
| 每个节点都有 key | — |
| bounds 存在 | — |
| 全部 90 节点均有结果 | — |

运行：

```bash
# Java
cd bestLocateFromXmlHierarchy
mvn compile test-compile
java -cp "target/classes:target/test-classes:..." org.junit.runner.JUnitCore \
     com.wcx.bestlocate.BestLocatorTest

# Python
python -m pytest python/tests/test_bestlocator.py -v
```
