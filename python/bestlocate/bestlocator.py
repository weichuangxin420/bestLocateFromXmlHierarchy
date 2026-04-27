"""
Read an Android hierarchy XML and return the best locator for a given node.

Usage:
    locator = BestLocator.from_file("example.xml")
    result = locator.best_locate("0-0-0-1-0-2")
    # result.type  -> "content_desc" | "resource_id" | "text" | "xpath"
    # result.value -> actual locator value
"""

from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from typing import Dict, List, Optional, Tuple


class LocatorResult:
    """Final locator output (type + value)."""

    def __init__(self, loc_type: str, value: str) -> None:
        self.type = loc_type
        self.value = value

    def __repr__(self) -> str:
        return f"LocatorResult(type='{self.type}', value='{self.value}')"


class _HierarchyNode:
    """Internal parsed hierarchy node."""

    def __init__(self) -> None:
        self.key: str = ""
        self.name: str = ""
        self.properties: Dict[str, str] = {}
        self.bounds: Optional[List[float]] = None
        self.children: List[_HierarchyNode] = []


class _XPathCandidate:
    """Internal xpath candidate."""

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


# ── constants ──

_STRATEGY_CONTENT_DESC = "contentDesc"
_STRATEGY_RESOURCE_ID = "resourceId"
_STRATEGY_TEXT = "text"
_STRATEGY_CLASS_TEXT = "classText"
_STRATEGY_CLASS_CONTENT_DESC = "classContentDesc"
_STRATEGY_CLASS = "class"
_STRATEGY_XPATH = "xpath"

_TYPE_CONTENT_DESC = "content_desc"
_TYPE_RESOURCE_ID = "resource_id"
_TYPE_TEXT = "text"
_TYPE_XPATH = "xpath"

_BY_ID = "id"
_BY_TEXT = "text"
_BY_CONTENT_DESC = "content-desc"
_BY_CLASS = "class"
_BY_CLASS_TEXT = "class_and_text"
_BY_CLASS_CONTENT_DESC = "class_and_content_desc"

_RE_BOUNDS_DIGITS = re.compile(r"\d+")
_RE_XML_BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
_RE_VALID_XML_NAME = re.compile(r"^[A-Za-z_][A-Za-z0-9._-]*$")


class BestLocator:
    """Parse hierarchy XML and return the best locator for any node."""

    def __init__(self, xml_data: str) -> None:
        width, height = _infer_size(xml_data)
        self._root = _parse_hierarchy(xml_data, width, height)
        self._node_map: Dict[str, _HierarchyNode] = {}
        self._all_nodes: List[_HierarchyNode] = []
        self._preferred_xpath: str = ""
        self._build_index()

    @classmethod
    def from_file(cls, path: str) -> "BestLocator":
        with open(path, "r", encoding="utf-8") as f:
            return cls(f.read())

    # ── Public API ──

    def best_locate(self, key: str) -> LocatorResult:
        """Return the best locator (type + value) for the given node key."""
        target = self._node_map.get(key)
        if target is None:
            raise ValueError(f"node key not found: {key}")

        candidates = self._suggest_xpath(target)

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
                    return LocatorResult(_to_locator_type(xc.strategy), xc.property_value)

        if self._preferred_xpath:
            return LocatorResult(_TYPE_XPATH, self._preferred_xpath)
        fallback = candidates[0].xpath if candidates else ""
        return LocatorResult(_TYPE_XPATH, fallback)

    def all_candidates(self, key: str) -> List[_XPathCandidate]:
        """Return all candidates for debugging."""
        target = self._node_map.get(key)
        if target is None:
            raise ValueError(f"node key not found: {key}")
        return self._suggest_xpath(target)

    @property
    def root(self) -> _HierarchyNode:
        return self._root

    @property
    def node_map(self) -> Dict[str, _HierarchyNode]:
        return self._node_map

    @property
    def all_nodes(self) -> List[_HierarchyNode]:
        return self._all_nodes

    # ── Index ──

    def _build_index(self) -> None:
        self._node_map = {}
        self._all_nodes = []

        def walk(node: _HierarchyNode) -> None:
            self._node_map[node.key] = node
            self._all_nodes.append(node)
            for child in node.children:
                walk(child)

        walk(self._root)

    # ── suggest_xpath ──

    def _suggest_xpath(self, selected: _HierarchyNode) -> List[_XPathCandidate]:
        by_candidates = _get_anchor_by_candidates(selected)
        by_candidates.sort(key=lambda by: len(self._matches_by(selected, by)))

        preferred_by = by_candidates[0] if by_candidates else _BY_CLASS
        self._preferred_xpath = _build_xpath_expr(selected, preferred_by)
        pref_matches = self._matches_by(selected, preferred_by)
        pref_idx = _index_of(pref_matches, selected.key)
        if self._preferred_xpath and pref_idx > 0:
            self._preferred_xpath = f"({self._preferred_xpath})[{pref_idx + 1}]"

        seen: set = set()
        candidates: List[_XPathCandidate] = []

        for by in by_candidates:
            matches = self._matches_by(selected, by)
            idx = _index_of(matches, selected.key)
            xpath = _build_xpath_expr(selected, by)
            if xpath and idx > 0:
                xpath = f"({xpath})[{idx + 1}]"
            pv = _extract_property_value(selected, by)
            candidates.append(_XPathCandidate(_by_to_strategy(by), pv, xpath, len(matches), idx))
            if xpath:
                seen.add(xpath)

        candidates.extend(self._build_complex_candidates(selected, seen))
        return candidates

    # ── matches_by ──

    def _matches_by(self, selected: _HierarchyNode, by: str) -> List[_HierarchyNode]:
        sp = selected.properties
        s_name = selected.name
        s_text = sp.get("text")
        s_cd = sp.get("content-desc")
        s_rid = sp.get("resource-id")

        matched: List[_HierarchyNode] = []
        for node in self._all_nodes:
            np = node.properties
            if by == _BY_ID and np.get("resource-id") == s_rid:
                matched.append(node)
            elif by == _BY_TEXT and np.get("text") == s_text and s_text:
                matched.append(node)
            elif by == _BY_CONTENT_DESC and np.get("content-desc") == s_cd:
                matched.append(node)
            elif by == _BY_CLASS and node.name == s_name:
                matched.append(node)
            elif by == _BY_CLASS_TEXT and node.name == s_name and np.get("text") == s_text and s_text:
                matched.append(node)
            elif by == _BY_CLASS_CONTENT_DESC and node.name == s_name and np.get("content-desc") == s_cd:
                matched.append(node)
        return matched

    # ── complex candidates ──

    def _build_complex_candidates(
        self, selected: _HierarchyNode, seen: set
    ) -> List[_XPathCandidate]:
        out: List[_XPathCandidate] = []
        sk = selected.key
        if "-" not in sk:
            return out

        parent_key = sk.rsplit("-", 1)[0]
        parent = self._node_map.get(parent_key)
        if parent is None or not parent.children:
            return out

        sel_idx = _child_index(parent, sk)
        if sel_idx < 0:
            return out

        s_name = selected.name
        same_pos = _same_class_position(parent, sel_idx, s_name)
        parent_anchors = self._unique_anchor_xpaths(parent)

        # Type A: parent-anchored
        for by, px in parent_anchors:
            child_x = f"({px}/*[{sel_idx + 1}])"
            _add_complex(out, sk, child_x, f"parent_{by}_child_index", seen)

            if _is_valid_xml_name(s_name):
                sc_x = f"({px}/{s_name})[{same_pos}]"
            else:
                sc_x = f"({px}/*[@class={_quote_xliteral(s_name)}])[{same_pos}]"
            _add_complex(out, sk, sc_x, f"parent_{by}_same_class_index", seen)

        # Type B: sibling-anchored
        for si, sibling in enumerate(parent.children):
            if si == sel_idx:
                continue
            is_following = si < sel_idx
            direction = "following-sibling" if is_following else "preceding-sibling"
            suffix = "following" if is_following else "preceding"

            b_start = si + 1 if is_following else sel_idx
            b_end = sel_idx if is_following else si
            step = sum(
                1 for i in range(b_start, b_end)
                if parent.children[i].name == s_name
            )
            if step <= 0:
                continue

            axis = _axis_expr(s_name, direction)

            # B1: sibling's own unique anchor
            for sby, sx in self._unique_anchor_xpaths(sibling):
                xx = f"({sx}/{axis})[{step}]"
                _add_complex(out, sk, xx, f"sibling_{sby}_{suffix}", seen)

        return out

    def _unique_anchor_xpaths(self, node: _HierarchyNode) -> List[Tuple[str, str]]:
        result: List[Tuple[str, str]] = []
        seen: set = set()
        for by in _get_anchor_by_candidates(node):
            xpath = _build_xpath_expr(node, by)
            if not xpath or xpath in seen:
                continue
            matches = self._matches_by(node, by)
            if len(matches) != 1:
                continue
            seen.add(xpath)
            result.append((by, xpath))
        return result


# ================================================================
#  XML → tree (stdlib xml.etree.ElementTree)
# ================================================================

def _infer_size(xml: str) -> Tuple[int, int]:
    max_x, max_y = 1, 1
    for m in _RE_XML_BOUNDS.finditer(xml):
        max_x = max(max_x, int(m.group(3)))
        max_y = max(max_y, int(m.group(4)))
    return max_x, max_y


def _parse_hierarchy(xml: str, width: int, height: int) -> _HierarchyNode:
    root = ET.fromstring(xml)
    return _parse_element(root, width, height, [0])


def _parse_element(
    el: ET.Element, width: int, height: int, indexes: List[int]
) -> _HierarchyNode:
    node = _HierarchyNode()
    node.key = _join_indexes(indexes)
    node.name = _resolve_name(el)
    node.properties = dict(el.attrib)

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

    # ElementTree children are sub-elements; we only care about "node" elements
    child_idx = 0
    for child_el in el:
        if child_el.tag != "node":
            continue
        ci = list(indexes) + [child_idx]
        node.children.append(_parse_element(child_el, width, height, ci))
        child_idx += 1

    return node


# ================================================================
#  Strategy helpers
# ================================================================

def _get_anchor_by_candidates(node: _HierarchyNode) -> List[str]:
    p = node.properties
    out: List[str] = []
    out.append(_BY_CLASS)
    if p.get("resource-id"):
        out.append(_BY_ID)
    if p.get("content-desc"):
        out.append(_BY_CONTENT_DESC)
    if p.get("text"):
        out.append(_BY_TEXT)
        out.append(_BY_CLASS_TEXT)
    if p.get("content-desc") and node.name:
        out.append(_BY_CLASS_CONTENT_DESC)
    return _dedupe(out)


def _build_xpath_expr(node: _HierarchyNode, by: str) -> str:
    p = node.properties
    name = node.name

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
        if _is_valid_xml_name(name):
            return f"//{name}"
        return f"//*[@class={_quote_xliteral(name)}]"
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
        return f"//*[@class={_quote_xliteral(name)} and @content-desc={_quote_xliteral(cd)}]"
    return ""


# ================================================================
#  XPath utilities
# ================================================================

def _quote_xliteral(value: str) -> str:
    if '"' not in value:
        return f'"{value}"'
    if "'" not in value:
        return f"'{value}'"
    parts = value.split('"')
    joined = ", '\"', ".join(f'"{p}"' for p in parts)
    return f"concat({joined})"


def _is_valid_xml_name(name: str) -> bool:
    return bool(name and _RE_VALID_XML_NAME.match(name))


def _axis_expr(name: str, axis: str) -> str:
    if _is_valid_xml_name(name):
        return f"{axis}::{name}"
    return f"{axis}::*[@class={_quote_xliteral(name)}]"


# ================================================================
#  General helpers
# ================================================================

def _extract_property_value(node: _HierarchyNode, by: str) -> str:
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
    return {
        _BY_ID: _STRATEGY_RESOURCE_ID,
        _BY_TEXT: _STRATEGY_TEXT,
        _BY_CONTENT_DESC: _STRATEGY_CONTENT_DESC,
        _BY_CLASS: _STRATEGY_CLASS,
        _BY_CLASS_TEXT: _STRATEGY_CLASS_TEXT,
        _BY_CLASS_CONTENT_DESC: _STRATEGY_CLASS_CONTENT_DESC,
    }.get(by, _STRATEGY_XPATH)


def _to_locator_type(strategy: str) -> str:
    if strategy in (_STRATEGY_CONTENT_DESC, _STRATEGY_CLASS_CONTENT_DESC):
        return _TYPE_CONTENT_DESC
    if strategy == _STRATEGY_RESOURCE_ID:
        return _TYPE_RESOURCE_ID
    if strategy in (_STRATEGY_TEXT, _STRATEGY_CLASS_TEXT):
        return _TYPE_TEXT
    return _TYPE_XPATH


def _resolve_name(el: ET.Element) -> str:
    if el.tag == "node":
        cls = el.get("class", "")
        if cls:
            return cls
    return el.tag


def _parse_bounds(v: str) -> List[int]:
    return [int(x) for x in _RE_BOUNDS_DIGITS.findall(v)]


def _round_norm(value: int, size: int) -> float:
    if size <= 0:
        return 0.0
    return round((value * 1.0 / size) * 10000) / 10000


def _join_indexes(indexes: List[int]) -> str:
    return "-".join(str(i) for i in indexes)


def _index_of(nodes: List[_HierarchyNode], key: str) -> int:
    for i, n in enumerate(nodes):
        if n.key == key:
            return i
    return -1


def _child_index(parent: _HierarchyNode, child_key: str) -> int:
    for i, c in enumerate(parent.children):
        if c.key == child_key:
            return i
    return -1


def _same_class_position(parent: _HierarchyNode, sel_idx: int, name: str) -> int:
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
    if not xpath or xpath in seen:
        return
    seen.add(xpath)
    sink.append(_XPathCandidate(_STRATEGY_XPATH, xpath, xpath, 1, 0))


def _dedupe(items: List[str]) -> List[str]:
    out: List[str] = []
    seen: set = set()
    for item in items:
        if item not in seen:
            seen.add(item)
            out.append(item)
    return out
