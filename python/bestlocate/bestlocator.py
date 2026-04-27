"""
读取 Android UIAutomator 层级 XML，对任意节点自动推断最佳定位器。

算法源自 uiautodev 的 suggest_xpath() + resolveLocatorByPriorityFast() 流程。

==== 用法 ====

    locator = BestLocator.from_file("example.xml")
    result = locator.best_locate("0-0-0-1-0-2")
    # result.type  → "content_desc" | "resource_id" | "text" | "xpath"
    # result.value → 实际的定位值

==== 算法概览 ====

阶段1: XML → 层级树（key = DFS 兄弟索引路径）
阶段2: 树 → 扁平索引 + 4 个属性倒排索引（O(1) 匹配查找）
阶段3: suggest_xpath() → 简单 + 复杂 XPath 候选，按唯一性排序
阶段4: resolvePriorityFast() → 优先级链首个命中:
         content-desc > resource-id > text > class-text >
         class-content-desc > class > xpath
"""

from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from collections import defaultdict
from typing import Dict, List, Optional, Tuple


# ================================================================
#  公开模型类
# ================================================================

class LocatorResult:
    """
    best_locate() 返回的最终定位结果。

    type:  "content_desc" | "resource_id" | "text" | "xpath"
    value: 实际的属性值（xpath 类型则为 xpath 表达式）
    """

    def __init__(self, loc_type: str, value: str) -> None:
        self.type = loc_type
        self.value = value

    def __repr__(self) -> str:
        return f"LocatorResult(type='{self.type}', value='{self.value}')"


# ================================================================
#  内部数据结构
# ================================================================

class _HierarchyNode:
    """
    从 UIAutomator XML 解析出的单个节点。

    key        - DFS 兄弟索引路径，如 "0-0-1-2"
                 （根节点为 "0"，根的第3个子节点为 "0-2"，以此类推）
    name       - class 属性值，如 "android.widget.Button"
    properties - 全部 XML 属性，以属性名作为 key
                 （resource-id, text, content-desc, clickable 等）
    bounds     - 归一化坐标 [x1, y1, x2, y2]，0~1 范围（相对屏幕比例）
    children   - 子节点列表（DOM 顺序）
    """

    def __init__(self) -> None:
        self.key: str = ""
        self.name: str = ""
        self.properties: Dict[str, str] = {}
        self.bounds: Optional[List[float]] = None
        self.children: List[_HierarchyNode] = []


class _XPathCandidate:
    """
    suggest_xpath() 生成的一个定位候选。

    strategy       - 内部策略名，如 "contentDesc", "resourceId"
                     （在 best_locate 中映射为输出 type）
    property_value - 该策略提取到的属性值，
                     如 resource-id 候选的 "com.example:id/btn"
    xpath          - 生成的 XPath 表达式
    match_count    - 全树中有多少个节点匹配此策略
                     （1 = 唯一，越大越不精确）
    selected_index - 目标节点在匹配列表中的位置（0-based）
    """

    def __init__(
        self,
        strategy: str,
        property_value: str,
        xpath: str,
        match_count: int,
        selected_index: int,
    ) -> None:
        self.strategy = strategy
        self.property_value = property_value
        self.xpath = xpath
        self.match_count = match_count
        self.selected_index = selected_index

    def __repr__(self) -> str:
        return (
            f"XC(strategy={self.strategy}, pv='{self.property_value}', "
            f"xpath={self.xpath}, matches={self.match_count}, idx={self.selected_index})"
        )


# ================================================================
#  常量定义
# ================================================================

# 策略名（内部使用，用于排序和优先级比较）。
# 这些是 "by" 类型映射到人类可读的策略标签。
_STRATEGY_CONTENT_DESC = "contentDesc"
_STRATEGY_RESOURCE_ID = "resourceId"
_STRATEGY_TEXT = "text"
_STRATEGY_CLASS_TEXT = "classText"
_STRATEGY_CLASS_CONTENT_DESC = "classContentDesc"
_STRATEGY_CLASS = "class"
_STRATEGY_XPATH = "xpath"               # 复杂候选的兜底策略

# 输出定位器类型（返回给调用者，对应 LocatorResult.type）。
# 命名遵循 Android accessibility / Appium 惯例。
_TYPE_CONTENT_DESC = "content_desc"
_TYPE_RESOURCE_ID = "resource_id"
_TYPE_TEXT = "text"
_TYPE_XPATH = "xpath"

# "By" 类型（matches_by / build_xpath_expr 使用的内部 key）。
# 命名与 uiautodev Python 后端保持一致。
_BY_ID = "id"
_BY_TEXT = "text"
_BY_CONTENT_DESC = "content-desc"
_BY_CLASS = "class"
_BY_CLASS_TEXT = "class_and_text"
_BY_CLASS_CONTENT_DESC = "class_and_content_desc"

# 正则表达式，编译一次全局复用，提高性能。
_RE_BOUNDS_DIGITS = re.compile(r"\d+")
_RE_XML_BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
_RE_VALID_XML_NAME = re.compile(r"^[A-Za-z_][A-Za-z0-9._-]*$")


# ================================================================
#  BestLocator — 主类
# ================================================================

class BestLocator:
    """
    解析 Android UIAutomator 层级 XML，对任意给定 key 的节点
    返回最佳定位器。

    构造流程:
      1. 从 XML 中推断屏幕分辨率（扫描最大 bounds 值）
      2. 将 XML DOM 解析为 _HierarchyNode 树
      3. build_index(): 展平树 + 建立 4 个属性倒排索引

    best_locate(key) 流程:
      1. 通过 key 查找目标节点 (O(1))
      2. suggest_xpath() → 生成所有候选（简单 + 复杂）
      3. resolvePriorityFast() → 返回第一个命中的候选
    """

    def __init__(self, xml_data: str) -> None:
        # 阶段1: 将 XML 解析为 _HierarchyNode 树。
        width, height = _infer_size(xml_data)
        self._root = _parse_hierarchy(xml_data, width, height)

        # 阶段2a: 扁平查找结构。
        self._node_map: Dict[str, _HierarchyNode] = {}
        self._all_nodes: List[_HierarchyNode] = []

        # 阶段2b: 属性倒排索引，用于 O(1) 的 matches_by() 查找。
        # 每个索引将属性值映射到拥有该值的节点列表。
        self._by_rid: Dict[str, List[_HierarchyNode]] = defaultdict(list)
        self._by_text: Dict[str, List[_HierarchyNode]] = defaultdict(list)
        self._by_cd: Dict[str, List[_HierarchyNode]] = defaultdict(list)
        self._by_class: Dict[str, List[_HierarchyNode]] = defaultdict(list)

        # 阶段2c: 缓存首选 XPath（用作兜底）。
        self._preferred_xpath: str = ""

        self._build_index()

    @classmethod
    def from_file(cls, path: str) -> "BestLocator":
        """便捷方法：从文件路径读取 XML。"""
        with open(path, "r", encoding="utf-8") as f:
            return cls(f.read())

    # ================================================================
    #  公开 API
    # ================================================================

    def best_locate(self, key: str) -> LocatorResult:
        """
        对给定节点 key 返回最佳定位器 (type + value)。

        阶段3: suggest_xpath() 生成所有候选。
        阶段4: resolvePriorityFast() 按优先级链选取首个命中:

            content-desc > resource-id > text > class-text >
            class-content-desc > class > xpath（兜底）

        优先级顺序遵循行业共识:
        - content-desc / resource-id 最快最稳定（Appium 基准 ~35ms）
        - text 快速但可能随多语言变化
        - XPath 最慢且最脆弱（~120ms），仅作最后手段
        """
        target = self._node_map.get(key)
        if target is None:
            raise ValueError(f"node key not found: {key}")

        candidates = self._suggest_xpath(target)

        # 固定优先级链 —— 首个 property_value 非空的候选即返回。
        # 与 uiautodev 的 resolveLocatorByPriorityFast() 完全一致。
        priority = [
            _STRATEGY_CONTENT_DESC,
            _STRATEGY_RESOURCE_ID,
            _STRATEGY_TEXT,
            _STRATEGY_CLASS_TEXT,
            _STRATEGY_CLASS_CONTENT_DESC,
            _STRATEGY_CLASS,
        ]

        for strat in priority:
            for xc in candidates:
                if xc.strategy == strat and xc.property_value:
                    return LocatorResult(
                        _to_locator_type(xc.strategy), xc.property_value
                    )

        # 如果简单候选都没命中，兜底用首选 XPath。
        if self._preferred_xpath:
            return LocatorResult(_TYPE_XPATH, self._preferred_xpath)

        # 最后的最后。
        fallback = candidates[0].xpath if candidates else ""
        return LocatorResult(_TYPE_XPATH, fallback)

    def all_candidates(self, key: str) -> List[_XPathCandidate]:
        """返回全部候选列表，用于调试 / 检查。"""
        target = self._node_map.get(key)
        if target is None:
            raise ValueError(f"node key not found: {key}")
        return self._suggest_xpath(target)

    # 公开只读访问器，供测试和调试使用。
    @property
    def root(self) -> _HierarchyNode:
        return self._root

    @property
    def node_map(self) -> Dict[str, _HierarchyNode]:
        return self._node_map

    @property
    def all_nodes(self) -> List[_HierarchyNode]:
        return self._all_nodes

    # ================================================================
    #  阶段2: 索引构建
    # ================================================================

    def _build_index(self) -> None:
        """
        前序遍历展平树，同时建立 4 个倒排索引，
        使 matches_by() 实现 O(1) 属性查找。

        建立的索引:
          _by_rid   : resource-id 值 → 匹配节点列表
          _by_text  : text 值       → 匹配节点列表
          _by_cd    : content-desc 值 → 匹配节点列表
          _by_class : class 名称    → 匹配节点列表

        仅索引非空的属性值（空字符串跳过）。
        """
        self._node_map = {}
        self._all_nodes = []
        self._by_rid.clear()
        self._by_text.clear()
        self._by_cd.clear()
        self._by_class.clear()

        def walk(node: _HierarchyNode) -> None:
            # 标准索引: key → node，扁平列表。
            self._node_map[node.key] = node
            self._all_nodes.append(node)

            # 属性倒排索引: 填充全部 4 个映射表。
            p = node.properties
            rid = p.get("resource-id", "")
            text = p.get("text", "")
            cd = p.get("content-desc", "")

            if rid:
                self._by_rid[rid].append(node)
            if text:
                self._by_text[text].append(node)
            if cd:
                self._by_cd[cd].append(node)
            # class 名称始终存在（从 XML tag 或 class 属性派生）。
            self._by_class[node.name].append(node)

            for child in node.children:
                walk(child)

        walk(self._root)

    # ================================================================
    #  阶段3a: suggest_xpath() — 主入口
    # ================================================================

    def _suggest_xpath(self, selected: _HierarchyNode) -> List[_XPathCandidate]:
        """
        为给定节点生成所有定位候选。

        步骤:
          1. getAnchorByCandidates() — 根据目标节点具有哪些属性，
             决定哪些定位策略适用。
          2. 按匹配数升序排列策略 — 匹配越少 = 越唯一。
          3. 对每个策略，构建 XPath 表达式并统计匹配数。
          4. 生成复杂 XPath 候选（父级锚定、同级锚定），
             用于简单 XPath 不够唯一的场景。

        返回 _XPathCandidate 列表，按策略排序。
        """
        # 步骤 1 & 2: 获取候选策略，按匹配数升序排列（越少越好）。
        by_candidates = _get_anchor_by_candidates(selected)
        by_candidates.sort(key=lambda by: len(self._matches_by(selected, by)))

        # 首个（最唯一）策略即为"首选"。
        preferred_by = by_candidates[0] if by_candidates else _BY_CLASS
        self._preferred_xpath = _build_xpath_expr(selected, preferred_by)
        pref_matches = self._matches_by(selected, preferred_by)
        pref_idx = _index_of(pref_matches, selected.key)
        # 如果不是第一个匹配节点，追加位置索引: (//expr)[n]。
        if self._preferred_xpath and pref_idx > 0:
            self._preferred_xpath = f"({self._preferred_xpath})[{pref_idx + 1}]"

        seen: set = set()
        candidates: List[_XPathCandidate] = []

        # 步骤 3: 为每个适用策略构建候选。
        for by in by_candidates:
            matches = self._matches_by(selected, by)
            idx = _index_of(matches, selected.key)
            xpath = _build_xpath_expr(selected, by)
            # 如果不是第一个匹配，追加位置索引 [n]。
            if xpath and idx > 0:
                xpath = f"({xpath})[{idx + 1}]"
            pv = _extract_property_value(selected, by)
            candidates.append(
                _XPathCandidate(_by_to_strategy(by), pv, xpath, len(matches), idx)
            )
            if xpath:
                seen.add(xpath)

        # 步骤 4: 生成复杂 XPath 候选（父级/同级锚定）。
        candidates.extend(self._build_complex_candidates(selected, seen))
        return candidates

    # ================================================================
    #  matches_by — O(1) 属性查找（通过预建索引实现）
    # ================================================================

    def _matches_by(self, selected: _HierarchyNode, by: str) -> List[_HierarchyNode]:
        """
        查找所有与给定策略匹配的节点。

        使用预建的倒排索引（_by_rid, _by_text, _by_cd, _by_class）
        实现 O(1) 查找，而非扫描全部节点。

        对于组合策略（class_and_text, class_and_content_desc），
        计算两个索引的集合交集。
        """
        sp = selected.properties
        s_name = selected.name

        # 单属性策略: 直接 O(1) 字典查找。
        if by == _BY_ID:
            rid = sp.get("resource-id", "")
            return self._by_rid.get(rid, []) if rid else []
        if by == _BY_TEXT:
            text = sp.get("text", "")
            return self._by_text.get(text, []) if text else []
        if by == _BY_CONTENT_DESC:
            cd = sp.get("content-desc", "")
            return self._by_cd.get(cd, []) if cd else []
        if by == _BY_CLASS:
            return self._by_class.get(s_name, [])

        # 组合策略: 计算两个索引的集合交集。
        # 复杂度 O(min(|class匹配|, |另一索引匹配|)) — 仍远快于全量扫描。
        if by == _BY_CLASS_TEXT:
            text = sp.get("text", "")
            if not text:
                return []
            class_nodes = self._by_class.get(s_name, [])
            text_nodes = set(self._by_text.get(text, []))
            return [n for n in class_nodes if n in text_nodes]

        if by == _BY_CLASS_CONTENT_DESC:
            cd = sp.get("content-desc", "")
            if not cd:
                return []
            class_nodes = self._by_class.get(s_name, [])
            cd_nodes = set(self._by_cd.get(cd, []))
            return [n for n in class_nodes if n in cd_nodes]

        return []

    # ================================================================
    #  阶段3d: 复杂 XPath 候选
    # ================================================================

    def _build_complex_candidates(
        self, selected: _HierarchyNode, seen: set
    ) -> List[_XPathCandidate]:
        """
        当简单 XPath 不够唯一时，生成复杂 XPath 候选。

        两种锚定类型:

        类型 A — 父级锚定:
          使用具有唯一属性的父节点作锚点，
          然后通过子节点索引指定目标节点。

          例如: (//*[@resource-id="title_bar"]/*[3])
               (//*[@resource-id="title_bar"]/android.widget.Button)[2]

        类型 B — 同级锚定:
          使用可唯一定位的兄弟节点作参照，
          通过 following-sibling / preceding-sibling 轴定位目标节点。

          例如: (//*[@content-desc="back"]/following-sibling::Button)[1]

        仅在简单策略无法生成唯一定位器时才调用。
        """
        out: List[_XPathCandidate] = []
        sk = selected.key

        # 根节点（key="0"）没有父节点 — 无法生成复杂候选。
        if "-" not in sk:
            return out

        # 通过去掉最后一段索引找到父节点。
        parent_key = sk.rsplit("-", 1)[0]
        parent = self._node_map.get(parent_key)
        if parent is None or not parent.children:
            return out

        sel_idx = _child_index(parent, sk)
        if sel_idx < 0:
            return out

        s_name = selected.name
        # 同级同类节点中的位次（1-based）。
        same_pos = _same_class_position(parent, sel_idx, s_name)
        # 父节点唯一锚定策略（匹配数 == 1）。
        parent_anchors = self._unique_anchor_xpaths(parent)

        # ---- 类型 A: 父级锚定 ----
        for by, px in parent_anchors:
            # A1: 父节点下任意子节点的索引定位。
            child_x = f"({px}/*[{sel_idx + 1}])"
            _add_complex(out, sk, child_x, f"parent_{by}_child_index", seen)

            # A2: 父节点下仅同类子节点的索引定位。
            if _is_valid_xml_name(s_name):
                sc_x = f"({px}/{s_name})[{same_pos}]"
            else:
                sc_x = f"({px}/*[@class={_quote_xliteral(s_name)}])[{same_pos}]"
            _add_complex(out, sk, sc_x, f"parent_{by}_same_class_index", seen)

        # ---- 类型 B: 同级锚定 ----
        for si, sibling in enumerate(parent.children):
            if si == sel_idx:
                continue

            is_following = si < sel_idx          # 兄弟在目标节点之前
            direction = "following-sibling" if is_following else "preceding-sibling"
            suffix = "following" if is_following else "preceding"

            # 统计兄弟与目标之间有多少个同类节点。
            b_start = si + 1 if is_following else sel_idx
            b_end = sel_idx if is_following else si
            step = sum(
                1 for i in range(b_start, b_end)
                if parent.children[i].name == s_name
            )
            if step <= 0:
                continue

            axis = _axis_expr(s_name, direction)

            # B1: 以兄弟节点自身的唯一锚点为基础。
            for sby, sx in self._unique_anchor_xpaths(sibling):
                xx = f"({sx}/{axis})[{step}]"
                _add_complex(out, sk, xx, f"sibling_{sby}_{suffix}", seen)

        return out

    def _unique_anchor_xpaths(self, node: _HierarchyNode) -> List[Tuple[str, str]]:
        """
        查找能够唯一标识给定节点的 XPath 表达式。

        对每个适用策略（getAnchorByCandidates），构建 XPath 并检查
        在整棵树中是否恰好匹配 1 个节点。仅返回唯一的 XPath
        （match_count == 1）。

        返回 (by_type, xpath_expression) 元组列表。
        """
        result: List[Tuple[str, str]] = []
        seen: set = set()
        for by in _get_anchor_by_candidates(node):
            xpath = _build_xpath_expr(node, by)
            if not xpath or xpath in seen:
                continue
            matches = self._matches_by(node, by)
            if len(matches) != 1:       # 必须唯一
                continue
            seen.add(xpath)
            result.append((by, xpath))
        return result


# ================================================================
#  阶段1: XML 解析（纯标准库 xml.etree.ElementTree，零依赖）
# ================================================================

def _infer_size(xml: str) -> Tuple[int, int]:
    """
    从 XML 中推断屏幕分辨率（扫描所有 bounds 取最大值）。
    扫描所有 "[0,0][1080,1920]" 格式的 bounds，返回 (maxX, maxY)。
    """
    max_x, max_y = 1, 1
    for m in _RE_XML_BOUNDS.finditer(xml):
        max_x = max(max_x, int(m.group(3)))
        max_y = max(max_y, int(m.group(4)))
    return max_x, max_y


def _parse_hierarchy(xml: str, width: int, height: int) -> _HierarchyNode:
    """将完整 XML 字符串解析为 _HierarchyNode 树。"""
    root = ET.fromstring(xml)
    return _parse_element(root, width, height, [0])


def _parse_element(
    el: ET.Element, width: int, height: int, indexes: List[int]
) -> _HierarchyNode:
    """
    递归将一个 <node> 元素解析为 _HierarchyNode。

    Key 生成规则: 每个节点的 key 是其 DFS 兄弟索引路径，
    如 root="0", 第一个子节点="0-0", 第二个子节点的第三个="0-1-2"。

    Bounds 归一化: 像素 bounds [x1,y1][x2,y2] 除以屏幕尺寸，
    转为 0~1 范围的值，使其与分辨率无关。
    """
    node = _HierarchyNode()
    node.key = _join_indexes(indexes)
    node.name = _resolve_name(el)
    node.properties = dict(el.attrib)

    # 解析 "[x1,y1][x2,y2]" 格式的 bounds 并做归一化。
    bv = el.get("bounds", "")
    if bv:
        bounds = _parse_bounds(bv)
        if len(bounds) == 4:
            x1, y1, x2, y2 = bounds
            node.bounds = [
                _round_norm(x1, width),
                _round_norm(y1, height),
                _round_norm(x2, width),
                _round_norm(y2, height),
            ]

    # 仅递归解析子 <node> 元素（跳过文本节点等）。
    child_idx = 0
    for child_el in el:
        if child_el.tag != "node":
            continue
        ci = list(indexes) + [child_idx]
        node.children.append(_parse_element(child_el, width, height, ci))
        child_idx += 1

    return node


# ================================================================
#  策略辅助函数 — 选取和构建定位策略的核心逻辑
# ================================================================

def _get_anchor_by_candidates(node: _HierarchyNode) -> List[str]:
    """
    根据目标节点实际拥有的属性，决定哪些定位策略可用。

    class 始终包含（通用兜底）。
    其他策略仅在对应属性值非空时加入。

    顺序: class, id, content-desc, text, class_and_text,
    class_and_content_desc。此顺序在 suggest_xpath() 中
    会按匹配数重新排序。
    """
    p = node.properties
    out: List[str] = []
    out.append(_BY_CLASS)                       # 始终存在（兜底）
    if p.get("resource-id"):
        out.append(_BY_ID)
    if p.get("content-desc"):
        out.append(_BY_CONTENT_DESC)
    if p.get("text"):
        out.append(_BY_TEXT)                    # 纯文本
        out.append(_BY_CLASS_TEXT)              # class + text 组合
    if p.get("content-desc") and node.name:
        out.append(_BY_CLASS_CONTENT_DESC)      # class + content-desc 组合
    return _dedupe(out)


def _build_xpath_expr(node: _HierarchyNode, by: str) -> str:
    """
    为给定节点和定位策略构建 XPath 表达式。

    单属性策略生成简单的属性表达式:

        //*[@resource-id="com.example:id/btn"]
        //*[@content-desc="Submit"]
        //*[@text="OK"]

    基于 class 的策略生成标签或 class 表达式:

        //android.widget.Button                           （合法 XML 名称）
        //*[@class="com.example.CustomView"]              （非法 XML 名称）

    组合策略使用 [谓词] 语法:

        //android.widget.Button[@text="OK"]
        //*[@class="ComposeView" and @text="Submit"]

    若所需属性缺失，返回空字符串。
    """
    p = node.properties
    name = node.name

    # ---- 单属性策略 ----
    if by == _BY_ID:
        rid = p.get("resource-id", "")
        return f'//*[@resource-id={_quote_xliteral(rid)}]' if rid else ""
    if by == _BY_TEXT:
        t = p.get("text", "")
        return f'//*[@text={_quote_xliteral(t)}]' if t else ""
    if by == _BY_CONTENT_DESC:
        cd = p.get("content-desc", "")
        return f'//*[@content-desc={_quote_xliteral(cd)}]' if cd else ""
    if by == _BY_CLASS:
        if not name:
            return ""
        # 如果类名是合法的 XML 元素名（如 "android.widget.Button"），
        # 使用标签选择器。否则退回到 @class 属性匹配。
        if _is_valid_xml_name(name):
            return f"//{name}"
        return f"//*[@class={_quote_xliteral(name)}]"

    # ---- 组合策略（class + 另一属性） ----
    if by == _BY_CLASS_TEXT:
        t = p.get("text", "")
        if not name or not t:
            return ""
        if _is_valid_xml_name(name):
            return f"//{name}[@text={_quote_xliteral(t)}]"
        return f"//*[@class={_quote_xliteral(name)} and @text={_quote_xliteral(t)}]"

    if by == _BY_CLASS_CONTENT_DESC:
        cd = p.get("content-desc", "")
        if not name or not cd:
            return ""
        if _is_valid_xml_name(name):
            return f"//{name}[@content-desc={_quote_xliteral(cd)}]"
        return (
            f"//*[@class={_quote_xliteral(name)}"
            f" and @content-desc={_quote_xliteral(cd)}]"
        )

    return ""


# ================================================================
#  XPath 字面量工具函数
# ================================================================

def _quote_xliteral(value: str) -> str:
    """
    为 XPath 字面量对比中的字符串值加引号。

    处理属性值中同时包含 " 和 ' 的边界情况，
    必要时使用 XPath concat() 函数:

        "hello"     → "hello"         （使用双引号）
        it's        → "it's"          （双引号包裹含单引号的值）
        he said "hi"  → 'he said "hi"'  （单引号包裹含双引号的值）
        a"b'c       → concat("a", '"', "b'c")  （同时含两种引号）
    """
    if '"' not in value:
        return f'"{value}"'
    if "'" not in value:
        return f"'{value}'"
    # 两种引号同时存在 — 使用 concat() 拼接各段。
    parts = value.split('"')
    joined = ", '\"', ".join(f'"{p}"' for p in parts)
    return f"concat({joined})"


def _is_valid_xml_name(name: str) -> bool:
    """
    检查字符串是否为合法的 XML 元素名。
    合法名称: 以字母/下划线开头，后续可为字母/数字/._/-
    用于决定使用标签选择器 (//Button) 还是属性选择器
    (//*[@class="foo.bar.Baz$Inner"])。
    """
    return bool(name and _RE_VALID_XML_NAME.match(name))


def _axis_expr(name: str, axis: str) -> str:
    """
    构建 XPath 轴表达式，如 "following-sibling::Button"
    或 "preceding-sibling::*[@class='CustomView']"。
    """
    if _is_valid_xml_name(name):
        return f"{axis}::{name}"
    return f"{axis}::*[@class={_quote_xliteral(name)}]"


# ================================================================
#  通用辅助函数（策略映射、bounds 解析、树工具）
# ================================================================

def _extract_property_value(node: _HierarchyNode, by: str) -> str:
    """
    提取给定策略对应的实际属性值。
    作为 candidate.property_value — 最终返回给调用者
    的 LocatorResult.value。
    """
    p = node.properties
    return {
        _BY_ID: p.get("resource-id", "") or "",
        _BY_TEXT: p.get("text", "") or "",
        _BY_CONTENT_DESC: p.get("content-desc", "") or "",
        _BY_CLASS: node.name or "",
        _BY_CLASS_TEXT: p.get("text", "") or "",
        _BY_CLASS_CONTENT_DESC: p.get("content-desc", "") or "",
    }.get(by, "")


def _by_to_strategy(by: str) -> str:
    """将内部 "by" 类型映射为输出策略名。"""
    return {
        _BY_ID: _STRATEGY_RESOURCE_ID,
        _BY_TEXT: _STRATEGY_TEXT,
        _BY_CONTENT_DESC: _STRATEGY_CONTENT_DESC,
        _BY_CLASS: _STRATEGY_CLASS,
        _BY_CLASS_TEXT: _STRATEGY_CLASS_TEXT,
        _BY_CLASS_CONTENT_DESC: _STRATEGY_CLASS_CONTENT_DESC,
    }.get(by, _STRATEGY_XPATH)


def _to_locator_type(strategy: str) -> str:
    """将内部策略名映射为输出定位器类型。"""
    if strategy in (_STRATEGY_CONTENT_DESC, _STRATEGY_CLASS_CONTENT_DESC):
        return _TYPE_CONTENT_DESC
    if strategy == _STRATEGY_RESOURCE_ID:
        return _TYPE_RESOURCE_ID
    if strategy in (_STRATEGY_TEXT, _STRATEGY_CLASS_TEXT):
        return _TYPE_TEXT
    return _TYPE_XPATH


def _resolve_name(el: ET.Element) -> str:
    """
    从 XML 元素中提取有效节点名。
    对 <node class="Foo">，名称为 "Foo"。
    对其他元素，名称为标签名本身。
    """
    if el.tag == "node":
        cls = el.get("class", "")
        if cls:
            return cls
    return el.tag


def _parse_bounds(v: str) -> List[int]:
    """将 "[x1,y1][x2,y2]" 解析为 [x1, y1, x2, y2]。"""
    return [int(x) for x in _RE_BOUNDS_DIGITS.findall(v)]


def _round_norm(value: int, size: int) -> float:
    """
    将像素坐标归一化到 0~1 范围，保留 4 位小数。
    这使得 bounds 与分辨率无关 — 同一 UI 在不同屏幕
    尺寸上会产生相同的归一化 bounds。
    """
    if size <= 0:
        return 0.0
    return round((value * 1.0 / size) * 10000) / 10000


def _join_indexes(indexes: List[int]) -> str:
    """将 DFS 路径索引拼接为 key 字符串: [0, 1, 2] → "0-1-2"。"""
    return "-".join(str(i) for i in indexes)


def _index_of(nodes: List[_HierarchyNode], key: str) -> int:
    """在节点列表中按 key 查找位置，未找到返回 -1。"""
    for i, n in enumerate(nodes):
        if n.key == key:
            return i
    return -1


def _child_index(parent: _HierarchyNode, child_key: str) -> int:
    """在父节点的 children 列表中查找子节点索引。"""
    for i, c in enumerate(parent.children):
        if c.key == child_key:
            return i
    return -1


def _same_class_position(
    parent: _HierarchyNode, sel_idx: int, name: str
) -> int:
    """
    统计到 sel_idx 为止有多少个同类兄弟节点。
    返回 1-based 位置。用于构建 "父节点下第n个Button" 表达式。
    """
    pos = 0
    for i in range(sel_idx + 1):
        if parent.children[i].name == name:
            pos += 1
    return max(pos, 1)


def _add_complex(
    sink: List[_XPathCandidate],
    key: str,
    xpath: str,
    name: str,
    seen: set,
) -> None:
    """
    向候选列表中添加一个复杂 XPath 候选（去重）。
    复杂候选统一使用 strategy="xpath"，match_count=1
    （因为是结构锚定的，天然唯一）。
    """
    if not xpath or xpath in seen:
        return
    seen.add(xpath)
    sink.append(_XPathCandidate(_STRATEGY_XPATH, xpath, xpath, 1, 0))


def _dedupe(items: List[str]) -> List[str]:
    """列表去重，保持插入顺序。"""
    out: List[str] = []
    seen: set = set()
    for item in items:
        if item not in seen:
            seen.add(item)
            out.append(item)
    return out
