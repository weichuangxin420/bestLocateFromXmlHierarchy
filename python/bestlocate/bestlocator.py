"""
Read an Android UIAutomator hierarchy XML and return the best locator
for any given node.

The algorithm is a port of the suggest_xpath() +
resolveLocatorByPriorityFast() flow from uiautodev.

==== Usage ====

    locator = BestLocator.from_file("example.xml")
    result = locator.best_locate("0-0-0-1-0-2")
    # result.type  -> "content_desc" | "resource_id" | "text" | "xpath"
    # result.value -> actual locator value

==== Algorithm overview ====

Phase 1: XML -> hierarchy tree (key = DFS sibling-index path)
Phase 2: Tree -> flat index + 4 property inverted indexes (O(1) match lookup)
Phase 3: suggest_xpath() -> simple + complex XPath candidates, sorted by uniqueness
Phase 4: resolvePriorityFast() -> first match wins:
         content-desc > resource-id > text > class-text >
         class-content-desc > class > xpath
"""

from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from collections import defaultdict
from typing import Dict, List, Optional, Tuple


# ================================================================
#  Public model classes
# ================================================================

class LocatorResult:
    """
    Final locator output returned by best_locate().

    type:  one of "content_desc", "resource_id", "text", "xpath"
    value: the actual property value (or xpath expression for "xpath")
    """

    def __init__(self, loc_type: str, value: str) -> None:
        self.type = loc_type
        self.value = value

    def __repr__(self) -> str:
        return f"LocatorResult(type='{self.type}', value='{self.value}')"


# ================================================================
#  Internal data structures
# ================================================================

class _HierarchyNode:
    """
    A single node parsed from the UIAutomator XML.

    key        - DFS sibling-index path, e.g. "0-0-1-2"
                 (root is "0", its 3rd child is "0-2", etc.)
    name       - the "class" attribute value, e.g. "android.widget.Button"
    properties - all XML attributes keyed by name
                 ("resource-id", "text", "content-desc", "clickable", ...)
    bounds     - normalized [x1, y1, x2, y2] in 0~1 range (screen-relative)
    children   - child nodes in DOM order
    """

    def __init__(self) -> None:
        self.key: str = ""
        self.name: str = ""
        self.properties: Dict[str, str] = {}
        self.bounds: Optional[List[float]] = None
        self.children: List[_HierarchyNode] = []


class _XPathCandidate:
    """
    One locator candidate produced by suggest_xpath().

    strategy       - internal name, e.g. "contentDesc", "resourceId"
                     (mapped to output type in best_locate)
    property_value - the extracted property value for this strategy,
                     e.g. "com.example:id/btn" for resource-id
    xpath          - the generated XPath expression
    match_count    - how many nodes in the whole tree match this strategy
                     (1 = unique, higher = less specific)
    selected_index - position of the target node in the matched list (0-based)
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
#  Constants
# ================================================================

# Strategy names (internal, used for sorting and priority comparison).
# These are the "by" types mapped to human-readable strategy labels.
_STRATEGY_CONTENT_DESC = "contentDesc"
_STRATEGY_RESOURCE_ID = "resourceId"
_STRATEGY_TEXT = "text"
_STRATEGY_CLASS_TEXT = "classText"
_STRATEGY_CLASS_CONTENT_DESC = "classContentDesc"
_STRATEGY_CLASS = "class"
_STRATEGY_XPATH = "xpath"               # fallback for complex candidates

# Output locator types (returned to caller in LocatorResult.type).
# These follow the Android accessibility / Appium convention.
_TYPE_CONTENT_DESC = "content_desc"
_TYPE_RESOURCE_ID = "resource_id"
_TYPE_TEXT = "text"
_TYPE_XPATH = "xpath"

# "By" types (internal keys used by matches_by / build_xpath_expr).
# These mirror the uiautodev Python backend naming.
_BY_ID = "id"
_BY_TEXT = "text"
_BY_CONTENT_DESC = "content-desc"
_BY_CLASS = "class"
_BY_CLASS_TEXT = "class_and_text"
_BY_CLASS_CONTENT_DESC = "class_and_content_desc"

# Regex patterns compiled once for performance.
_RE_BOUNDS_DIGITS = re.compile(r"\d+")
_RE_XML_BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
_RE_VALID_XML_NAME = re.compile(r"^[A-Za-z_][A-Za-z0-9._-]*$")


# ================================================================
#  BestLocator — main class
# ================================================================

class BestLocator:
    """
    Parse an Android UIAutomator hierarchy XML and return the best
    locator for any node identified by its key.

    Construction flow:
      1. infer screen size from max bounds values in the XML
      2. parse XML DOM into a _HierarchyNode tree
      3. build_index(): flatten tree + create 4 property inverted indexes

    best_locate(key) flow:
      1. look up the target node by key (O(1))
      2. suggest_xpath() -> all candidates (simple + complex)
      3. resolvePriorityFast() -> return first matching candidate
    """

    def __init__(self, xml_data: str) -> None:
        # Phase 1: parse XML into a tree of _HierarchyNode.
        width, height = _infer_size(xml_data)
        self._root = _parse_hierarchy(xml_data, width, height)

        # Phase 2a: flat lookup structures.
        self._node_map: Dict[str, _HierarchyNode] = {}
        self._all_nodes: List[_HierarchyNode] = []

        # Phase 2b: property inverted indexes for O(1) matches_by().
        # Each maps a property value to the list of nodes having that value.
        self._by_rid: Dict[str, List[_HierarchyNode]] = defaultdict(list)
        self._by_text: Dict[str, List[_HierarchyNode]] = defaultdict(list)
        self._by_cd: Dict[str, List[_HierarchyNode]] = defaultdict(list)
        self._by_class: Dict[str, List[_HierarchyNode]] = defaultdict(list)

        # Phase 2c: cached preferred XPath (used as fallback).
        self._preferred_xpath: str = ""

        self._build_index()

    @classmethod
    def from_file(cls, path: str) -> "BestLocator":
        """Convenience: read XML from a file path."""
        with open(path, "r", encoding="utf-8") as f:
            return cls(f.read())

    # ================================================================
    #  Public API
    # ================================================================

    def best_locate(self, key: str) -> LocatorResult:
        """
        Return the best locator (type + value) for the given node key.

        Phase 3: suggest_xpath() generates all candidates.
        Phase 4: resolvePriorityFast() picks the first match by priority:

            content-desc > resource-id > text > class-text >
            class-content-desc > class > xpath (fallback)

        The priority order is fixed and matches the industry consensus:
        - content-desc / resource-id are fastest and most stable (Appium ~35ms)
        - text is fast but may change with i18n
        - XPath is the slowest and most brittle (~120ms), used only as fallback
        """
        target = self._node_map.get(key)
        if target is None:
            raise ValueError(f"node key not found: {key}")

        candidates = self._suggest_xpath(target)

        # Fixed priority chain — first candidate with a non-empty
        # property_value wins. This matches uiautodev's
        # resolveLocatorByPriorityFast() exactly.
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

        # If no simple candidate matched, fall back to preferred XPath.
        if self._preferred_xpath:
            return LocatorResult(_TYPE_XPATH, self._preferred_xpath)

        # Absolute last resort.
        fallback = candidates[0].xpath if candidates else ""
        return LocatorResult(_TYPE_XPATH, fallback)

    def all_candidates(self, key: str) -> List[_XPathCandidate]:
        """Return all candidates for debugging / inspection."""
        target = self._node_map.get(key)
        if target is None:
            raise ValueError(f"node key not found: {key}")
        return self._suggest_xpath(target)

    # Public read-only accessors for tests and debugging.
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
    #  Phase 2: Index building
    # ================================================================

    def _build_index(self) -> None:
        """
        Flatten the tree via pre-order traversal and build 4 inverted
        indexes for O(1) property lookup in matches_by().

        Indexes built:
          _by_rid   : resource-id value -> list of matching nodes
          _by_text  : text value         -> list of matching nodes
          _by_cd    : content-desc value -> list of matching nodes
          _by_class : class name         -> list of matching nodes

        Only non-empty property values are indexed (empty string = skip).
        """
        self._node_map = {}
        self._all_nodes = []
        self._by_rid.clear()
        self._by_text.clear()
        self._by_cd.clear()
        self._by_class.clear()

        def walk(node: _HierarchyNode) -> None:
            # Standard index: key -> node, flat list.
            self._node_map[node.key] = node
            self._all_nodes.append(node)

            # Property inverted indexes: populate all 4 maps.
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
            # class name always exists (derived from XML tag/class attr).
            self._by_class[node.name].append(node)

            for child in node.children:
                walk(child)

        walk(self._root)

    # ================================================================
    #  Phase 3a: suggest_xpath() — main entry
    # ================================================================

    def _suggest_xpath(self, selected: _HierarchyNode) -> List[_XPathCandidate]:
        """
        Generate all locator candidates for the given node.

        Steps:
          1. getAnchorByCandidates() — determine which strategies are applicable
             based on which properties the target node has.
          2. Sort strategies by match count ascending — fewer matches = more unique.
          3. For each strategy, build the XPath expression and count matches.
          4. Build complex XPath candidates (parent-anchored, sibling-anchored)
             for cases where simple XPaths are not unique.

        Returns a list of _XPathCandidate, ordered by strategy then by
        preference within each strategy.
        """
        # Step 1 & 2: get candidates, sort by match count (fewer = better).
        by_candidates = _get_anchor_by_candidates(selected)
        by_candidates.sort(key=lambda by: len(self._matches_by(selected, by)))

        # The first (most unique) strategy is the "preferred" one.
        preferred_by = by_candidates[0] if by_candidates else _BY_CLASS
        self._preferred_xpath = _build_xpath_expr(selected, preferred_by)
        pref_matches = self._matches_by(selected, preferred_by)
        pref_idx = _index_of(pref_matches, selected.key)
        # If this is not the first match, append a position index: (//expr)[n].
        if self._preferred_xpath and pref_idx > 0:
            self._preferred_xpath = f"({self._preferred_xpath})[{pref_idx + 1}]"

        seen: set = set()
        candidates: List[_XPathCandidate] = []

        # Step 3: build a candidate for each applicable strategy.
        for by in by_candidates:
            matches = self._matches_by(selected, by)
            idx = _index_of(matches, selected.key)
            xpath = _build_xpath_expr(selected, by)
            # Append [n] if the node is not the first match.
            if xpath and idx > 0:
                xpath = f"({xpath})[{idx + 1}]"
            pv = _extract_property_value(selected, by)
            candidates.append(
                _XPathCandidate(_by_to_strategy(by), pv, xpath, len(matches), idx)
            )
            if xpath:
                seen.add(xpath)

        # Step 4: generate complex XPath candidates (parent/sibling anchored).
        candidates.extend(self._build_complex_candidates(selected, seen))
        return candidates

    # ================================================================
    #  matches_by — O(1) property lookup via pre-built indexes
    # ================================================================

    def _matches_by(self, selected: _HierarchyNode, by: str) -> List[_HierarchyNode]:
        """
        Find all nodes matching the given strategy for the selected node.

        Uses the pre-built inverted indexes (_by_rid, _by_text, _by_cd,
        _by_class) for O(1) lookup instead of scanning all nodes.

        For combined strategies (class_and_text, class_and_content_desc),
        computes the set intersection of two indexes.
        """
        sp = selected.properties
        s_name = selected.name

        # Single-property strategies: direct O(1) dict lookup.
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

        # Combined strategies: compute set intersection of two indexes.
        # This is O(min(|class_match|, |other_match|)) — still much faster
        # than scanning all nodes.
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
    #  Phase 3d: Complex XPath candidates
    # ================================================================

    def _build_complex_candidates(
        self, selected: _HierarchyNode, seen: set
    ) -> List[_XPathCandidate]:
        """
        Generate complex XPath candidates when simple ones are not unique.

        Two types of anchoring:

        Type A — Parent-anchored:
          Use a parent node that DOES have a unique attribute as anchor,
          then specify the selected node as the n-th child.

          Example: (//*[@resource-id="title_bar"]/*[3])
                   (//*[@resource-id="title_bar"]/android.widget.Button)[2]

        Type B — Sibling-anchored:
          Use a sibling node that CAN be uniquely located as a reference,
          then use following-sibling / preceding-sibling axis to reach
          the selected node.

          Example: (//*[@content-desc="back"]/following-sibling::Button)[1]

        Only called when simple strategies don't produce unique locators.
        """
        out: List[_XPathCandidate] = []
        sk = selected.key

        # Root node (key="0") has no parent — can't build complex candidates.
        if "-" not in sk:
            return out

        # Find the parent by stripping the last index segment.
        parent_key = sk.rsplit("-", 1)[0]
        parent = self._node_map.get(parent_key)
        if parent is None or not parent.children:
            return out

        sel_idx = _child_index(parent, sk)
        if sel_idx < 0:
            return out

        s_name = selected.name
        # Position among siblings of the same class (1-based).
        same_pos = _same_class_position(parent, sel_idx, s_name)
        # Parent strategies that match exactly 1 node (unique anchors).
        parent_anchors = self._unique_anchor_xpaths(parent)

        # ---- Type A: Parent-anchored ----
        for by, px in parent_anchors:
            # A1: any child at index (sel_idx + 1)
            child_x = f"({px}/*[{sel_idx + 1}])"
            _add_complex(out, sk, child_x, f"parent_{by}_child_index", seen)

            # A2: only same-class siblings at their own index
            if _is_valid_xml_name(s_name):
                sc_x = f"({px}/{s_name})[{same_pos}]"
            else:
                sc_x = f"({px}/*[@class={_quote_xliteral(s_name)}])[{same_pos}]"
            _add_complex(out, sk, sc_x, f"parent_{by}_same_class_index", seen)

        # ---- Type B: Sibling-anchored ----
        for si, sibling in enumerate(parent.children):
            if si == sel_idx:
                continue

            is_following = si < sel_idx          # sibling before selected
            direction = "following-sibling" if is_following else "preceding-sibling"
            suffix = "following" if is_following else "preceding"

            # Count how many same-class nodes exist between sibling and selected.
            b_start = si + 1 if is_following else sel_idx
            b_end = sel_idx if is_following else si
            step = sum(
                1 for i in range(b_start, b_end)
                if parent.children[i].name == s_name
            )
            if step <= 0:
                continue

            axis = _axis_expr(s_name, direction)

            # B1: use the sibling's own unique anchor as the base.
            for sby, sx in self._unique_anchor_xpaths(sibling):
                xx = f"({sx}/{axis})[{step}]"
                _add_complex(out, sk, xx, f"sibling_{sby}_{suffix}", seen)

        return out

    def _unique_anchor_xpaths(self, node: _HierarchyNode) -> List[Tuple[str, str]]:
        """
        Find XPath expressions that uniquely identify the given node.

        For each applicable strategy (getAnchorByCandidates), build the
        XPath and check if it matches EXACTLY one node in the tree.
        Only unique (match_count == 1) XPaths are returned.

        Returns a list of (by_type, xpath_expression) tuples.
        """
        result: List[Tuple[str, str]] = []
        seen: set = set()
        for by in _get_anchor_by_candidates(node):
            xpath = _build_xpath_expr(node, by)
            if not xpath or xpath in seen:
                continue
            matches = self._matches_by(node, by)
            if len(matches) != 1:       # must be unique
                continue
            seen.add(xpath)
            result.append((by, xpath))
        return result


# ================================================================
#  Phase 1: XML parsing (stdlib xml.etree.ElementTree, zero deps)
# ================================================================

def _infer_size(xml: str) -> Tuple[int, int]:
    """
    Infer the screen resolution from the maximum bounds values in the XML.
    Scans all bounds patterns like "[0,0][1080,1920]" and returns (maxX, maxY).
    """
    max_x, max_y = 1, 1
    for m in _RE_XML_BOUNDS.finditer(xml):
        max_x = max(max_x, int(m.group(3)))
        max_y = max(max_y, int(m.group(4)))
    return max_x, max_y


def _parse_hierarchy(xml: str, width: int, height: int) -> _HierarchyNode:
    """Parse the complete XML string into a _HierarchyNode tree."""
    root = ET.fromstring(xml)
    return _parse_element(root, width, height, [0])


def _parse_element(
    el: ET.Element, width: int, height: int, indexes: List[int]
) -> _HierarchyNode:
    """
    Recursively parse one <node> element into a _HierarchyNode.

    Key generation: each node's key is its DFS path of sibling indices,
    e.g. root="0", first child="0-0", second child's third="0-1-2".

    Bounds normalization: pixel bounds [x1,y1][x2,y2] are divided by
    screen dimensions to produce 0~1 range values, making them
    resolution-independent.
    """
    node = _HierarchyNode()
    node.key = _join_indexes(indexes)
    node.name = _resolve_name(el)
    node.properties = dict(el.attrib)

    # Parse and normalize bounds from "[x1,y1][x2,y2]" format.
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

    # Recursively parse child <node> elements only (skip text nodes, etc.).
    child_idx = 0
    for child_el in el:
        if child_el.tag != "node":
            continue
        ci = list(indexes) + [child_idx]
        node.children.append(_parse_element(child_el, width, height, ci))
        child_idx += 1

    return node


# ================================================================
#  Strategy helpers — the core logic for picking and building
# ================================================================

def _get_anchor_by_candidates(node: _HierarchyNode) -> List[str]:
    """
    Determine which locator strategies are applicable based on which
    properties the target node actually has.

    class is ALWAYS included as the universal fallback.
    Other strategies are only included if the corresponding property
    has a non-empty value.

    The order is: class, id, content-desc, text, class_and_text,
    class_and_content_desc.  This order is then re-sorted by match
    count in suggest_xpath().
    """
    p = node.properties
    out: List[str] = []
    out.append(_BY_CLASS)                       # always present (fallback)
    if p.get("resource-id"):
        out.append(_BY_ID)
    if p.get("content-desc"):
        out.append(_BY_CONTENT_DESC)
    if p.get("text"):
        out.append(_BY_TEXT)                    # text alone
        out.append(_BY_CLASS_TEXT)              # class + text combination
    if p.get("content-desc") and node.name:
        out.append(_BY_CLASS_CONTENT_DESC)      # class + content-desc combination
    return _dedupe(out)


def _build_xpath_expr(node: _HierarchyNode, by: str) -> str:
    """
    Build an XPath expression for the given node and locator strategy.

    Single-property strategies produce simple attribute expressions:

        //*[@resource-id="com.example:id/btn"]
        //*[@content-desc="Submit"]
        //*[@text="OK"]

    Class-based strategies produce tag or class expressions:

        //android.widget.Button                              (valid XML name)
        //*[@class="com.example.CustomView"]                 (invalid XML name)

    Combined strategies use [predicate] syntax:

        //android.widget.Button[@text="OK"]
        //*[@class="ComposeView" and @text="Submit"]

    Returns empty string if the required property is missing.
    """
    p = node.properties
    name = node.name

    # ---- Single-property strategies ----
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
        # If the class name is a valid XML element name (e.g.
        # "android.widget.Button"), use a tag selector. Otherwise
        # fall back to @class attribute matching.
        if _is_valid_xml_name(name):
            return f"//{name}"
        return f"//*[@class={_quote_xliteral(name)}]"

    # ---- Combined strategies (class + another property) ----
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
#  XPath literal utilities
# ================================================================

def _quote_xliteral(value: str) -> str:
    """
    Quote a string for use in XPath literal comparisons.

    Handles the edge case where a value contains BOTH double and single
    quotes by using XPath concat():

        "hello"         -> "hello"         (use double quotes)
        it's            -> "it's"          (double quotes, contains single)
        he said "hi"    -> 'he said "hi"'  (single quotes, contains double)
        a"b'c           -> concat("a", '"', "b'c")  (both quote types)
    """
    if '"' not in value:
        return f'"{value}"'
    if "'" not in value:
        return f"'{value}'"
    # Both quote types present — use concat() to join segments.
    parts = value.split('"')
    joined = ", '\"', ".join(f'"{p}"' for p in parts)
    return f"concat({joined})"


def _is_valid_xml_name(name: str) -> bool:
    """
    Check if a string is a valid XML element name.
    Valid names: start with letter/underscore, then letters/digits/._/-
    Used to decide between tag selectors (//Button) and attribute
    selectors (//*[@class="foo.bar.Baz$Inner"]).
    """
    return bool(name and _RE_VALID_XML_NAME.match(name))


def _axis_expr(name: str, axis: str) -> str:
    """
    Build an XPath axis expression like "following-sibling::Button"
    or "preceding-sibling::*[@class='CustomView']".
    """
    if _is_valid_xml_name(name):
        return f"{axis}::{name}"
    return f"{axis}::*[@class={_quote_xliteral(name)}]"


# ================================================================
#  General helpers (strategy mapping, bounds parsing, tree utils)
# ================================================================

def _extract_property_value(node: _HierarchyNode, by: str) -> str:
    """
    Extract the actual property value for a given strategy.
    Used as candidate.property_value — the value returned to the caller
    as LocatorResult.value.
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
    """Map internal "by" type to output strategy name."""
    return {
        _BY_ID: _STRATEGY_RESOURCE_ID,
        _BY_TEXT: _STRATEGY_TEXT,
        _BY_CONTENT_DESC: _STRATEGY_CONTENT_DESC,
        _BY_CLASS: _STRATEGY_CLASS,
        _BY_CLASS_TEXT: _STRATEGY_CLASS_TEXT,
        _BY_CLASS_CONTENT_DESC: _STRATEGY_CLASS_CONTENT_DESC,
    }.get(by, _STRATEGY_XPATH)


def _to_locator_type(strategy: str) -> str:
    """Map internal strategy name to output locator type."""
    if strategy in (_STRATEGY_CONTENT_DESC, _STRATEGY_CLASS_CONTENT_DESC):
        return _TYPE_CONTENT_DESC
    if strategy == _STRATEGY_RESOURCE_ID:
        return _TYPE_RESOURCE_ID
    if strategy in (_STRATEGY_TEXT, _STRATEGY_CLASS_TEXT):
        return _TYPE_TEXT
    return _TYPE_XPATH


def _resolve_name(el: ET.Element) -> str:
    """
    Extract the effective node name from an XML element.
    For <node class="Foo">, the name is "Foo".
    For any other element, the name is the tag name itself.
    """
    if el.tag == "node":
        cls = el.get("class", "")
        if cls:
            return cls
    return el.tag


def _parse_bounds(v: str) -> List[int]:
    """Parse "[x1,y1][x2,y2]" into [x1, y1, x2, y2]."""
    return [int(x) for x in _RE_BOUNDS_DIGITS.findall(v)]


def _round_norm(value: int, size: int) -> float:
    """
    Normalize a pixel coordinate to 0~1 range, rounded to 4 decimal places.
    This makes bounds resolution-independent — the same UI on a different
    screen size will produce the same normalized bounds.
    """
    if size <= 0:
        return 0.0
    return round((value * 1.0 / size) * 10000) / 10000


def _join_indexes(indexes: List[int]) -> str:
    """Join DFS path indices into a key string: [0, 1, 2] -> "0-1-2"."""
    return "-".join(str(i) for i in indexes)


def _index_of(nodes: List[_HierarchyNode], key: str) -> int:
    """Find the position of a node by key within a list. Returns -1 if not found."""
    for i, n in enumerate(nodes):
        if n.key == key:
            return i
    return -1


def _child_index(parent: _HierarchyNode, child_key: str) -> int:
    """Find the index of a child node within its parent's children list."""
    for i, c in enumerate(parent.children):
        if c.key == child_key:
            return i
    return -1


def _same_class_position(
    parent: _HierarchyNode, sel_idx: int, name: str
) -> int:
    """
    Count how many siblings up to sel_idx have the same class name.
    Returns 1-based position. Used for "nth Button in parent" expressions.
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
    Add a complex XPath candidate to the sink list, avoiding duplicates.
    Complex candidates always use strategy="xpath" with match_count=1
    (since they are structurally anchored).
    """
    if not xpath or xpath in seen:
        return
    seen.add(xpath)
    sink.append(_XPathCandidate(_STRATEGY_XPATH, xpath, xpath, 1, 0))


def _dedupe(items: List[str]) -> List[str]:
    """Remove duplicates from a list while preserving insertion order."""
    out: List[str] = []
    seen: set = set()
    for item in items:
        if item not in seen:
            seen.add(item)
            out.append(item)
    return out
