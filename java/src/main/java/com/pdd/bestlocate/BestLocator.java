package com.pdd.bestlocate;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read an Android UIAutomator hierarchy XML and return the best locator
 * for any given node.
 *
 * <p>The algorithm is a port of the {@code suggest_xpath()} +
 * {@code resolveLocatorByPriorityFast()} flow from uiautodev.</p>
 *
 * <h3>Algorithm overview</h3>
 * <ol>
 *   <li><b>XML → hierarchy tree</b> — parse DOM, assign DFS sibling-index keys</li>
 *   <li><b>Tree → flat index</b> — pre-order traversal + 4 property inverted indexes</li>
 *   <li><b>suggest_xpath()</b> — generate simple + complex XPath candidates,
 *       sorted by uniqueness (match count ascending)</li>
 *   <li><b>resolvePriorityFast()</b> — first match wins:
 *       content-desc → resource-id → text → class-text →
 *       class-content-desc → class → xpath</li>
 * </ol>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   BestLocator bl = new BestLocator(xmlString);
 *   LocatorResult result = bl.bestLocate("0-0-0-1-0-2");
 *   // result.getType()  → "content_desc" | "resource_id" | "text" | "xpath"
 *   // result.getValue() → actual locator value
 * }</pre>
 */
public class BestLocator {

    // Regex patterns compiled once for performance.
    private static final Pattern BOUNDS_PATTERN = Pattern.compile("\\d+");
    private static final Pattern XML_BOUNDS_PATTERN =
            Pattern.compile("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]");

    // ---- strategy names (internal, used for sorting and priority comparison) ----
    private static final String STRATEGY_CONTENT_DESC = "contentDesc";
    private static final String STRATEGY_RESOURCE_ID = "resourceId";
    private static final String STRATEGY_TEXT = "text";
    private static final String STRATEGY_CLASS_TEXT = "classText";
    private static final String STRATEGY_CLASS_CONTENT_DESC = "classContentDesc";
    private static final String STRATEGY_CLASS = "class";
    /** Fallback for complex candidates. */
    private static final String STRATEGY_XPATH = "xpath";

    // ---- output locator types (returned in LocatorResult.type) ----
    /** Accessibility ID / content-desc — fastest and most stable. */
    private static final String TYPE_CONTENT_DESC = "content_desc";
    /** Resource ID — unique and fast (~35ms in Appium). */
    private static final String TYPE_RESOURCE_ID = "resource_id";
    /** Text content — may change with i18n. */
    private static final String TYPE_TEXT = "text";
    /** XPath — slow (~120ms) and brittle, used as last resort. */
    private static final String TYPE_XPATH = "xpath";

    // ---- internal "by" types (keys used by matchesBy / buildXpathExpr) ----
    private static final String BY_ID = "id";
    private static final String BY_TEXT = "text";
    private static final String BY_CONTENT_DESC = "content-desc";
    private static final String BY_CLASS = "class";
    private static final String BY_CLASS_TEXT = "class_and_text";
    private static final String BY_CLASS_CONTENT_DESC = "class_and_content_desc";

    // ================================================================
    //  Instance state — built once during construction
    // ================================================================

    /** Root of the parsed hierarchy tree. */
    private HierarchyNode root;
    /** Key → node map for O(1) node lookup. */
    private Map<String, HierarchyNode> nodeMap;
    /** All nodes in pre-order (flat list for iteration). */
    private List<HierarchyNode> allNodes;
    /** Cached preferred XPath, used as fallback in bestLocate(). */
    private String preferredXpath;

    /**
     * Property inverted indexes — built during buildIndex() for O(1)
     * property lookup in matchesBy(). Each maps a property value to
     * the list of nodes having that value.
     */
    private Map<String, List<HierarchyNode>> byRid = new HashMap<>();
    private Map<String, List<HierarchyNode>> byText = new HashMap<>();
    private Map<String, List<HierarchyNode>> byCd = new HashMap<>();
    private Map<String, List<HierarchyNode>> byClass = new HashMap<>();

    // ================================================================
    //  Construction
    // ================================================================

    /**
     * Parse an XML string into the hierarchy, then build all indexes.
     *
     * @param xmlData raw UIAutomator dump XML string
     */
    public BestLocator(String xmlData) {
        int[] size = inferSize(xmlData);
        this.root = parseHierarchy(xmlData, size[0], size[1]);
        buildIndex();
    }

    /**
     * Convenience: load XML from a file path.
     *
     * @param path filesystem path to the hierarchy XML file
     * @return a fully initialized BestLocator instance
     * @throws Exception if the file cannot be read
     */
    public static BestLocator fromFile(String path) throws Exception {
        return new BestLocator(new String(Files.readAllBytes(Paths.get(path)), "UTF-8"));
    }

    // ================================================================
    //  Public API
    // ================================================================

    /**
     * Return the best locator (type + value) for the given node key.
     *
     * <p>This is the main entry point. It runs the full algorithm:</p>
     * <ol>
     *   <li>Look up the target node by key (O(1))</li>
     *   <li>suggestXpath() → generate all candidates</li>
     *   <li>resolvePriorityFast() → pick the first matching candidate
     *       by fixed priority: content-desc → resource-id → text →
     *       class-text → class-content-desc → class → xpath</li>
     * </ol>
     *
     * <p>The priority order matches the industry consensus:
     * content-desc and resource-id are fastest and most stable (~35ms),
     * while XPath is slow (~120ms) and brittle — used only as fallback.</p>
     *
     * @param key the node key (e.g. "0-0-1-2")
     * @return the best locator, never null
     * @throws IllegalArgumentException if the key is not found
     */
    public LocatorResult bestLocate(String key) {
        HierarchyNode target = nodeMap.get(key);
        if (target == null) {
            throw new IllegalArgumentException("node key not found: " + key);
        }

        // Phase 3: generate all candidates.
        List<XPathCandidate> candidates = suggestXpath(target);

        // Phase 4: resolveLocatorByPriorityFast().
        // Fixed priority chain — first candidate with a non-empty
        // propertyValue wins.
        String[] priorityOrder = {
                STRATEGY_CONTENT_DESC, STRATEGY_RESOURCE_ID, STRATEGY_TEXT,
                STRATEGY_CLASS_TEXT, STRATEGY_CLASS_CONTENT_DESC, STRATEGY_CLASS
        };

        for (String strat : priorityOrder) {
            for (XPathCandidate xc : candidates) {
                if (strat.equals(xc.strategy) && !xc.propertyValue.isEmpty()) {
                    return new LocatorResult(toLocatorType(xc.strategy), xc.propertyValue);
                }
            }
        }

        // Fallback 1: preferred XPath (the most unique simple strategy).
        if (!preferredXpath.isEmpty()) {
            return new LocatorResult(TYPE_XPATH, preferredXpath);
        }

        // Fallback 2: absolute last resort.
        String xpath = candidates.isEmpty() ? "" : candidates.get(0).xpath;
        return new LocatorResult(TYPE_XPATH, xpath);
    }

    /**
     * Return all candidates for debugging / inspection.
     *
     * @param key the node key
     * @return full list of XPath candidates including complex ones
     */
    public List<XPathCandidate> getAllCandidates(String key) {
        HierarchyNode target = nodeMap.get(key);
        if (target == null) {
            throw new IllegalArgumentException("node key not found: " + key);
        }
        return suggestXpath(target);
    }

    /** @return root of the parsed hierarchy tree */
    public HierarchyNode getRoot() { return root; }

    /** @return key → node lookup map */
    public Map<String, HierarchyNode> getNodeMap() { return nodeMap; }

    /** @return all nodes in pre-order */
    public List<HierarchyNode> getAllNodes() { return allNodes; }

    // ================================================================
    //  Phase 1: XML → HierarchyNode tree
    // ================================================================

    /**
     * Infer screen resolution from the maximum bounds values in the XML.
     * Scans all {@code [x1,y1][x2,y2]} patterns and returns (maxX, maxY).
     */
    private static int[] inferSize(String xml) {
        int maxX = 1, maxY = 1;
        Matcher m = XML_BOUNDS_PATTERN.matcher(xml);
        while (m.find()) {
            maxX = Math.max(maxX, Integer.parseInt(m.group(3)));
            maxY = Math.max(maxY, Integer.parseInt(m.group(4)));
        }
        return new int[]{maxX, maxY};
    }

    /**
     * Parse the complete XML string into a HierarchyNode tree.
     * Uses DOM parser with secure feature flags (no external entities).
     */
    private static HierarchyNode parseHierarchy(String xml, int width, int height) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setExpandEntityReferences(false);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            Document doc = f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            // Root is the <hierarchy> element; first child is the first <node>.
            return parseElement(doc.getDocumentElement(), width, height,
                    new ArrayList<>(Collections.singletonList(0)));
        } catch (Exception e) {
            throw new RuntimeException("failed to parse hierarchy xml", e);
        }
    }

    /**
     * Recursively parse one DOM element into a HierarchyNode.
     *
     * <p><b>Key generation:</b> each node's key is its DFS path of sibling
     * indices, e.g. root="0", first child="0-0", second child's third="0-1-2".</p>
     *
     * <p><b>Bounds normalization:</b> pixel bounds [x1,y1][x2,y2] are divided
     * by screen dimensions to produce 0~1 range values, making them
     * resolution-independent.</p>
     */
    private static HierarchyNode parseElement(Element el, int width, int height,
                                               List<Integer> indexes) {
        HierarchyNode node = new HierarchyNode();
        node.setKey(joinIndexes(indexes));
        node.setName(resolveNodeName(el));
        node.setProperties(readProperties(el));

        // Parse and normalize bounds from "[x1,y1][x2,y2]" format.
        String bv = el.getAttribute("bounds");
        if (bv != null && !bv.isEmpty()) {
            List<Integer> bounds = parseBounds(bv);
            if (bounds.size() == 4) {
                int x1 = bounds.get(0), y1 = bounds.get(1),
                    x2 = bounds.get(2), y2 = bounds.get(3);
                // rect = pixel coordinates (for frontend overlay drawing).
                node.setRect(new HierarchyRect(x1, y1, x2 - x1, y2 - y1));
                // bounds = normalized 0~1 range (for coordinate matching).
                node.setBounds(Arrays.asList(
                        roundNorm(x1, width), roundNorm(y1, height),
                        roundNorm(x2, width), roundNorm(y2, height)));
            }
        }

        // Recursively parse child <node> elements only (skip text/#text nodes).
        NodeList children = el.getChildNodes();
        int childIdx = 0;
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element)) continue;
            List<Integer> ci = new ArrayList<>(indexes);
            ci.add(childIdx);
            node.getChildren().add(parseElement(
                    (Element) children.item(i), width, height, ci));
            childIdx++;
        }
        return node;
    }

    // ================================================================
    //  Phase 2: Index building
    // ================================================================

    /**
     * Flatten the tree via pre-order traversal and build 4 inverted
     * indexes for O(1) property lookup in matchesBy().
     *
     * <p>Indexes built:</p>
     * <ul>
     *   <li>{@code byRid}   — resource-id value → matching nodes</li>
     *   <li>{@code byText}  — text value → matching nodes</li>
     *   <li>{@code byCd}    — content-desc value → matching nodes</li>
     *   <li>{@code byClass} — class name → matching nodes</li>
     * </ul>
     *
     * <p>Only non-empty property values are indexed.</p>
     */
    private void buildIndex() {
        nodeMap = new LinkedHashMap<>();
        allNodes = new ArrayList<>();
        byRid.clear();
        byText.clear();
        byCd.clear();
        byClass.clear();
        indexRecursive(root);
    }

    /**
     * Recursive helper for buildIndex() — populates nodeMap, allNodes,
     * and all 4 property inverted indexes.
     */
    private void indexRecursive(HierarchyNode node) {
        if (node == null) return;

        // Standard index: key → node, flat list.
        nodeMap.put(node.getKey(), node);
        allNodes.add(node);

        // Property inverted indexes: populate all 4 maps.
        Map<String, String> p = node.getProperties();
        String rid = p.get("resource-id");
        String text = p.get("text");
        String cd = p.get("content-desc");

        if (hasText(rid)) {
            byRid.computeIfAbsent(rid, k -> new ArrayList<>()).add(node);
        }
        if (hasText(text)) {
            byText.computeIfAbsent(text, k -> new ArrayList<>()).add(node);
        }
        if (hasText(cd)) {
            byCd.computeIfAbsent(cd, k -> new ArrayList<>()).add(node);
        }
        // class name is always present (derived from XML tag or class attr).
        byClass.computeIfAbsent(node.getName(), k -> new ArrayList<>()).add(node);

        if (node.getChildren() != null) {
            for (HierarchyNode child : node.getChildren()) {
                indexRecursive(child);
            }
        }
    }

    // ================================================================
    //  Phase 3: suggestXpath() — main entry
    // ================================================================

    /**
     * Generate all locator candidates for the given node.
     *
     * <p>Steps:</p>
     * <ol>
     *   <li>getAnchorByCandidates() — determine applicable strategies based
     *       on which properties the target node has.</li>
     *   <li>Sort strategies by match count ascending — fewer matches
     *       = more unique.</li>
     *   <li>For each strategy, build the XPath expression, count matches,
     *       and create a candidate.</li>
     *   <li>buildComplexCandidates() — generate parent-anchored and
     *       sibling-anchored XPath candidates for cases where simple
     *       strategies are not unique.</li>
     * </ol>
     *
     * @param selected the target node
     * @return all candidates, sorted by strategy preference
     */
    private List<XPathCandidate> suggestXpath(HierarchyNode selected) {
        // Step 1 & 2: get candidates, sort by match count (fewer = better).
        List<String> byCandidates = getAnchorByCandidates(selected);
        byCandidates.sort(Comparator.comparingInt(
                by -> matchesBy(selected, by).size()));

        // The first (most unique) strategy is the "preferred" one.
        String preferredBy = byCandidates.isEmpty() ? BY_CLASS : byCandidates.get(0);
        this.preferredXpath = buildXpathExpr(selected, preferredBy);
        List<HierarchyNode> prefMatches = matchesBy(selected, preferredBy);
        int prefIdx = indexOfNode(prefMatches, selected.getKey());
        // If not the first match, append position index: (//expr)[n].
        if (!preferredXpath.isEmpty() && prefIdx > 0) {
            preferredXpath = "(" + preferredXpath + ")[" + (prefIdx + 1) + "]";
        }

        // Step 3: build a candidate for each applicable strategy.
        List<XPathCandidate> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (String by : byCandidates) {
            List<HierarchyNode> matches = matchesBy(selected, by);
            int idx = indexOfNode(matches, selected.getKey());
            String xpath = buildXpathExpr(selected, by);
            // Append [n] if the node is not the first match.
            if (!xpath.isEmpty() && idx > 0) {
                xpath = "(" + xpath + ")[" + (idx + 1) + "]";
            }
            String pv = extractPropertyValue(selected, by);
            candidates.add(new XPathCandidate(
                    byToStrategy(by), pv, xpath, matches.size(), idx));
            if (!xpath.isEmpty()) seen.add(xpath);
        }

        // Step 4: generate complex candidates (parent/sibling anchored).
        candidates.addAll(buildComplexCandidates(selected, seen));
        return candidates;
    }

    // ================================================================
    //  getAnchorByCandidates — determine applicable strategies
    // ================================================================

    /**
     * Determine which locator strategies are applicable based on which
     * properties the target node actually has.
     *
     * <p>class is ALWAYS included as the universal fallback. Other
     * strategies are only included if the corresponding property
     * has a non-empty value.</p>
     *
     * <p>The order is: class, id, content-desc, text, class_and_text,
     * class_and_content_desc — later re-sorted by match count in
     * suggestXpath().</p>
     */
    private List<String> getAnchorByCandidates(HierarchyNode node) {
        List<String> out = new ArrayList<>();
        Map<String, String> p = node.getProperties();

        out.add(BY_CLASS);                              // always present (fallback)
        if (hasText(p.get("resource-id"))) out.add(BY_ID);
        if (hasText(p.get("content-desc"))) out.add(BY_CONTENT_DESC);
        if (hasText(p.get("text"))) {
            out.add(BY_TEXT);                           // text alone
            out.add(BY_CLASS_TEXT);                     // class + text combination
        }
        if (hasText(p.get("content-desc")) && hasText(node.getName())) {
            out.add(BY_CLASS_CONTENT_DESC);             // class + content-desc combo
        }
        return dedupe(out);
    }

    // ================================================================
    //  matchesBy — O(1) property lookup via pre-built indexes
    // ================================================================

    /**
     * Find all nodes matching the given strategy for the selected node.
     *
     * <p>Uses the pre-built inverted indexes (byRid, byText, byCd, byClass)
     * for O(1) lookup instead of scanning all nodes. For combined
     * strategies (class_and_text, class_and_content_desc), computes
     * the set intersection of two indexes.</p>
     *
     * <p>This was the main performance hotspot before optimization —
     * each call used to scan O(n) nodes. Now reduced to O(1).</p>
     */
    private List<HierarchyNode> matchesBy(HierarchyNode selected, String by) {
        Map<String, String> sp = selected.getProperties();
        String sName = selected.getName();

        switch (by) {
            // Single-property strategies: direct O(1) map lookup.
            case BY_ID: {
                String rid = sp.get("resource-id");
                if (!hasText(rid)) return Collections.emptyList();
                List<HierarchyNode> v = byRid.get(rid);
                return v != null ? v : Collections.emptyList();
            }
            case BY_TEXT: {
                String text = sp.get("text");
                if (!hasText(text)) return Collections.emptyList();
                List<HierarchyNode> v = byText.get(text);
                return v != null ? v : Collections.emptyList();
            }
            case BY_CONTENT_DESC: {
                String cd = sp.get("content-desc");
                if (!hasText(cd)) return Collections.emptyList();
                List<HierarchyNode> v = byCd.get(cd);
                return v != null ? v : Collections.emptyList();
            }
            case BY_CLASS: {
                List<HierarchyNode> v = byClass.get(sName);
                return v != null ? v : Collections.emptyList();
            }
            // Combined strategies: set intersection of two indexes.
            // O(min(|A|, |B|)) — still much faster than scanning all nodes.
            case BY_CLASS_TEXT: {
                String text = sp.get("text");
                if (!hasText(text)) return Collections.emptyList();
                List<HierarchyNode> classNodes = byClass.get(sName);
                if (classNodes == null) return Collections.emptyList();
                List<HierarchyNode> textNodes = byText.get(text);
                if (textNodes == null) return Collections.emptyList();

                Set<HierarchyNode> textSet = new HashSet<>(textNodes);
                List<HierarchyNode> result = new ArrayList<>();
                for (HierarchyNode n : classNodes) {
                    if (textSet.contains(n)) result.add(n);
                }
                return result;
            }
            case BY_CLASS_CONTENT_DESC: {
                String cd = sp.get("content-desc");
                if (!hasText(cd)) return Collections.emptyList();
                List<HierarchyNode> classNodes = byClass.get(sName);
                if (classNodes == null) return Collections.emptyList();
                List<HierarchyNode> cdNodes = byCd.get(cd);
                if (cdNodes == null) return Collections.emptyList();

                Set<HierarchyNode> cdSet = new HashSet<>(cdNodes);
                List<HierarchyNode> result = new ArrayList<>();
                for (HierarchyNode n : classNodes) {
                    if (cdSet.contains(n)) result.add(n);
                }
                return result;
            }
            default:
                return Collections.emptyList();
        }
    }

    // ================================================================
    //  buildXpathExpr — construct XPath for a strategy
    // ================================================================

    /**
     * Build an XPath expression for the given node and locator strategy.
     *
     * <p>Single-property strategies produce simple attribute expressions:</p>
     * <pre>
     *   id         → //*[@resource-id="com.example:id/btn"]
     *   text       → //*[@text="OK"]
     *   content-desc → //*[@content-desc="Submit"]</pre>
     *
     * <p>Class-based strategies produce tag or class expressions:</p>
     * <pre>
     *   valid XML name  → //android.widget.Button
     *   invalid XML name → //*[@class="com.example.CustomView"]</pre>
     *
     * <p>Combined strategies use predicate syntax:</p>
     * <pre>
     *   class+text → //android.widget.Button[@text="OK"]</pre>
     *
     * @return the XPath string, or "" if the required property is missing
     */
    private String buildXpathExpr(HierarchyNode node, String by) {
        Map<String, String> p = node.getProperties();
        String name = node.getName();

        switch (by) {
            // ---- Single-property strategies ----
            case BY_ID: {
                String rid = p.get("resource-id");
                return hasText(rid)
                        ? "//*[@resource-id=" + quoteXLiteral(rid) + "]" : "";
            }
            case BY_TEXT: {
                String t = p.get("text");
                return hasText(t) ? "//*[@text=" + quoteXLiteral(t) + "]" : "";
            }
            case BY_CONTENT_DESC: {
                String cd = p.get("content-desc");
                return hasText(cd)
                        ? "//*[@content-desc=" + quoteXLiteral(cd) + "]" : "";
            }
            case BY_CLASS: {
                if (!hasText(name)) return "";
                // If the class name is a valid XML element name, use a tag
                // selector (//Button). Otherwise use @class attribute.
                return isValidXmlName(name)
                        ? "//" + name
                        : "//*[@class=" + quoteXLiteral(name) + "]";
            }
            // ---- Combined strategies ----
            case BY_CLASS_TEXT: {
                String t = p.get("text");
                if (!hasText(name) || !hasText(t)) return "";
                if (isValidXmlName(name))
                    return "//" + name + "[@text=" + quoteXLiteral(t) + "]";
                return "//*[@class=" + quoteXLiteral(name)
                        + " and @text=" + quoteXLiteral(t) + "]";
            }
            case BY_CLASS_CONTENT_DESC: {
                String cd = p.get("content-desc");
                if (!hasText(name) || !hasText(cd)) return "";
                if (isValidXmlName(name))
                    return "//" + name + "[@content-desc=" + quoteXLiteral(cd) + "]";
                return "//*[@class=" + quoteXLiteral(name)
                        + " and @content-desc=" + quoteXLiteral(cd) + "]";
            }
            default:
                return "";
        }
    }

    // ================================================================
    //  Phase 3d: Complex XPath candidates
    // ================================================================

    /**
     * Generate complex XPath candidates when simple ones are not unique.
     *
     * <h4>Type A — Parent-anchored</h4>
     * <p>Use a parent node that DOES have a unique attribute as anchor,
     * then specify the selected node as the n-th child.</p>
     * <pre>
     *   (//*[@resource-id="title_bar"]/*[3])
     *   (//*[@resource-id="title_bar"]/android.widget.Button)[2]</pre>
     *
     * <h4>Type B — Sibling-anchored</h4>
     * <p>Use a sibling node that CAN be uniquely located as a reference,
     * then use XPath axis to reach the selected node.</p>
     * <pre>
     *   (//*[@content-desc="back"]/following-sibling::Button)[1]</pre>
     *
     * <p>Only called when simple strategies don't yield unique locators.</p>
     */
    private List<XPathCandidate> buildComplexCandidates(HierarchyNode selected,
                                                         Set<String> seen) {
        List<XPathCandidate> out = new ArrayList<>();
        String selectedKey = selected.getKey();

        // Root node (key="0") has no parent — can't build complex candidates.
        if (!selectedKey.contains("-")) return out;

        // Find the parent by stripping the last index segment.
        String parentKey = selectedKey.substring(0, selectedKey.lastIndexOf('-'));
        HierarchyNode parent = nodeMap.get(parentKey);
        if (parent == null || parent.getChildren().isEmpty()) return out;

        int selectedIdx = childIndex(parent, selectedKey);
        if (selectedIdx < 0) return out;

        String sName = selected.getName();
        // 1-based position among siblings of the same class.
        int sameClassPos = sameClassPosition(parent, selectedIdx, sName);
        // Parent strategies that match exactly 1 node (unique anchors).
        List<String[]> parentAnchors = uniqueAnchorXpaths(parent);

        // ---- Type A: Parent-anchored ----
        for (String[] anchor : parentAnchors) {
            String px = anchor[1];   // parent xpath
            String by = anchor[0];  // parent by-type

            // A1: any child at the specific index.
            String childX = "(" + px + "/*[" + (selectedIdx + 1) + "])";
            addComplex(out, selectedKey, childX,
                    "parent_" + by + "_child_index", seen);

            // A2: only same-class siblings at their own index.
            String scX;
            if (isValidXmlName(sName)) {
                scX = "(" + px + "/" + sName + ")[" + sameClassPos + "]";
            } else {
                scX = "(" + px + "/*[@class=" + quoteXLiteral(sName)
                        + "])[" + sameClassPos + "]";
            }
            addComplex(out, selectedKey, scX,
                    "parent_" + by + "_same_class_index", seen);
        }

        // ---- Type B: Sibling-anchored ----
        for (int si = 0; si < parent.getChildren().size(); si++) {
            if (si == selectedIdx) continue;

            HierarchyNode sibling = parent.getChildren().get(si);
            boolean isFollowing = si < selectedIdx;           // sibling before selected
            String direction = isFollowing
                    ? "following-sibling" : "preceding-sibling";
            String suffix = isFollowing ? "following" : "preceding";

            // Count same-class nodes between sibling and selected.
            int betweenStart = isFollowing ? si + 1 : selectedIdx;
            int betweenEnd = isFollowing ? selectedIdx : si;
            int step = 0;
            for (int i = betweenStart; i < betweenEnd; i++) {
                if (Objects.equals(parent.getChildren().get(i).getName(), sName))
                    step++;
            }
            if (step <= 0) continue;

            String axis = axisExpr(sName, direction);

            // B1: use the sibling's own unique anchor as the base.
            for (String[] anchor : uniqueAnchorXpaths(sibling)) {
                String xx = "(" + anchor[1] + "/" + axis + ")[" + step + "]";
                addComplex(out, selectedKey, xx,
                        "sibling_" + anchor[0] + "_" + suffix, seen);
            }
        }
        return out;
    }

    /**
     * Find XPath expressions that uniquely identify the given node.
     *
     * <p>For each applicable strategy, build the XPath and check if it
     * matches EXACTLY one node in the tree. Only unique XPaths are
     * returned.</p>
     *
     * @return list of (byType, xpathExpression) pairs
     */
    private List<String[]> uniqueAnchorXpaths(HierarchyNode node) {
        List<String[]> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String by : getAnchorByCandidates(node)) {
            String xpath = buildXpathExpr(node, by);
            if (xpath.isEmpty() || seen.contains(xpath)) continue;
            List<HierarchyNode> matches = matchesBy(node, by);
            if (matches.size() != 1) continue;    // must be unique
            seen.add(xpath);
            result.add(new String[]{by, xpath});
        }
        return result;
    }

    /**
     * Add a complex XPath candidate, avoiding duplicates.
     * Complex candidates always use strategy="xpath" with matchCount=1
     * (since they are structurally anchored).
     */
    private void addComplex(List<XPathCandidate> sink, String key,
                            String xpath, String name, Set<String> seen) {
        if (xpath.isEmpty() || seen.contains(xpath)) return;
        seen.add(xpath);
        sink.add(new XPathCandidate(STRATEGY_XPATH, xpath, xpath, 1, 0));
    }

    // ================================================================
    //  Mapping helpers (internal → output)
    // ================================================================

    /** Map internal strategy name to output locator type. */
    private static String toLocatorType(String strategy) {
        switch (strategy) {
            case STRATEGY_CONTENT_DESC:
            case STRATEGY_CLASS_CONTENT_DESC: return TYPE_CONTENT_DESC;
            case STRATEGY_RESOURCE_ID: return TYPE_RESOURCE_ID;
            case STRATEGY_TEXT:
            case STRATEGY_CLASS_TEXT: return TYPE_TEXT;
            default: return TYPE_XPATH;
        }
    }

    /**
     * Extract the actual property value for a given "by" type.
     * This becomes candidate.propertyValue — the value returned to the
     * caller as LocatorResult.value.
     */
    private static String extractPropertyValue(HierarchyNode node, String by) {
        Map<String, String> p = node.getProperties();
        switch (by) {
            case BY_ID: return nullToEmpty(p.get("resource-id"));
            case BY_TEXT: return nullToEmpty(p.get("text"));
            case BY_CONTENT_DESC: return nullToEmpty(p.get("content-desc"));
            case BY_CLASS: return nullToEmpty(node.getName());
            case BY_CLASS_TEXT: return nullToEmpty(p.get("text"));
            case BY_CLASS_CONTENT_DESC: return nullToEmpty(p.get("content-desc"));
            default: return "";
        }
    }

    /** Map internal "by" type to output strategy name. */
    private static String byToStrategy(String by) {
        switch (by) {
            case BY_ID: return STRATEGY_RESOURCE_ID;
            case BY_TEXT: return STRATEGY_TEXT;
            case BY_CONTENT_DESC: return STRATEGY_CONTENT_DESC;
            case BY_CLASS: return STRATEGY_CLASS;
            case BY_CLASS_TEXT: return STRATEGY_CLASS_TEXT;
            case BY_CLASS_CONTENT_DESC: return STRATEGY_CLASS_CONTENT_DESC;
            default: return STRATEGY_XPATH;
        }
    }

    // ================================================================
    //  XPath literal utilities
    // ================================================================

    /**
     * Quote a string for use in XPath literal comparisons.
     *
     * <p>Handles the edge case where a value contains BOTH double and
     * single quotes by using XPath concat():</p>
     * <pre>
     *   "hello"           → "hello"
     *   it's              → "it's"
     *   he said "hi"      → 'he said "hi"'
     *   a"b'c             → concat("a", '"', "b'c")</pre>
     */
    static String quoteXLiteral(String value) {
        if (value == null) return "\"\"";
        if (!value.contains("\"")) return "\"" + value + "\"";
        if (!value.contains("'")) return "'" + value + "'";
        // Both quote types present — use concat() to join segments.
        StringBuilder sb = new StringBuilder("concat(");
        String[] parts = value.split("\"");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(", '\"', ");
            sb.append('"').append(parts[i]).append('"');
        }
        sb.append(")");
        return sb.toString();
    }

    /**
     * Check if a string is a valid XML element name.
     * Valid names start with a letter/underscore, then letters/digits/._/-
     * Used to decide between tag selectors (//Button) and attribute
     * selectors (//*[@class="foo.bar.Baz$Inner"]).
     */
    static boolean isValidXmlName(String name) {
        return name != null && name.matches("^[A-Za-z_][A-Za-z0-9._-]*$");
    }

    // ================================================================
    //  Tree/index utilities
    // ================================================================

    /** Find the position of a node by key within a list. -1 if not found. */
    private static int indexOfNode(List<HierarchyNode> nodes, String key) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).getKey().equals(key)) return i;
        }
        return -1;
    }

    /** Find the index of a child node within its parent's children list. */
    private static int childIndex(HierarchyNode parent, String childKey) {
        for (int i = 0; i < parent.getChildren().size(); i++) {
            if (parent.getChildren().get(i).getKey().equals(childKey)) return i;
        }
        return -1;
    }

    /**
     * Count how many siblings up to selectedIdx have the same class name.
     * Returns 1-based position. Used for "nth Button in parent" expressions.
     */
    private static int sameClassPosition(HierarchyNode parent, int selectedIdx,
                                          String name) {
        int pos = 0;
        for (int i = 0; i <= selectedIdx; i++) {
            if (Objects.equals(parent.getChildren().get(i).getName(), name))
                pos++;
        }
        return Math.max(pos, 1);
    }

    /**
     * Build an XPath axis expression, e.g. "following-sibling::Button"
     * or "preceding-sibling::*[@class='CustomView']".
     */
    private static String axisExpr(String name, String axis) {
        if (isValidXmlName(name)) return axis + "::" + name;
        return axis + "::*[@class=" + quoteXLiteral(name) + "]";
    }

    // ================================================================
    //  General helpers
    // ================================================================

    /**
     * Extract the effective node name from an XML element.
     * For {@code <node class="Foo">}, the name is "Foo".
     * For any other element, the name is the tag name itself.
     */
    private static String resolveNodeName(Element el) {
        if ("node".equals(el.getTagName())) {
            String cls = el.getAttribute("class");
            if (cls != null && !cls.isEmpty()) return cls;
        }
        return el.getTagName();
    }

    /** Read all attributes from an XML element into a LinkedHashMap. */
    private static LinkedHashMap<String, String> readProperties(Element el) {
        LinkedHashMap<String, String> props = new LinkedHashMap<>();
        NamedNodeMap attrs = el.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            props.put(attrs.item(i).getNodeName(), attrs.item(i).getNodeValue());
        }
        return props;
    }

    /** Parse "[x1,y1][x2,y2]" into [x1, y1, x2, y2]. */
    private static List<Integer> parseBounds(String v) {
        List<Integer> bounds = new ArrayList<>();
        Matcher m = BOUNDS_PATTERN.matcher(v);
        while (m.find()) bounds.add(Integer.parseInt(m.group()));
        return bounds;
    }

    /**
     * Normalize a pixel coordinate to 0~1 range, rounded to 4 decimal places.
     * This makes bounds resolution-independent.
     */
    private static double roundNorm(int value, int size) {
        if (size <= 0) return 0D;
        return Math.round((value * 1.0 / size) * 10000D) / 10000D;
    }

    /** Join DFS path indices into a key string: [0,1,2] → "0-1-2". */
    private static String joinIndexes(List<Integer> indexes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < indexes.size(); i++) {
            if (i > 0) sb.append("-");
            sb.append(indexes.get(i));
        }
        return sb.toString();
    }

    /** True if the string is non-null and non-empty. */
    private static boolean hasText(String s) {
        return s != null && !s.isEmpty();
    }

    /** Convert null to empty string. */
    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** Remove duplicates while preserving insertion order. */
    private static <T> List<T> dedupe(List<T> items) {
        List<T> out = new ArrayList<>();
        Set<T> seen = new HashSet<>();
        for (T item : items) {
            if (seen.add(item)) out.add(item);
        }
        return out;
    }

    // ================================================================
    //  XPathCandidate — internal data class
    // ================================================================

    /**
     * One locator candidate produced by suggestXpath().
     *
     * <p>Fields:</p>
     * <ul>
     *   <li><b>strategy</b> — internal name ("contentDesc", "resourceId", …)</li>
     *   <li><b>propertyValue</b> — extracted property value, becomes
     *       LocatorResult.value</li>
     *   <li><b>xpath</b> — the generated XPath expression</li>
     *   <li><b>matchCount</b> — how many nodes match (1 = unique)</li>
     *   <li><b>selectedIndex</b> — position of target in matched list (0-based)</li>
     * </ul>
     */
    public static class XPathCandidate {
        public final String strategy;
        public final String propertyValue;
        public final String xpath;
        public final int matchCount;
        public final int selectedIndex;

        XPathCandidate(String strategy, String propertyValue, String xpath,
                       int matchCount, int selectedIndex) {
            this.strategy = strategy;
            this.propertyValue = propertyValue;
            this.xpath = xpath;
            this.matchCount = matchCount;
            this.selectedIndex = selectedIndex;
        }

        @Override
        public String toString() {
            return "XC{strategy=" + strategy + ", pv='" + propertyValue
                    + "', xpath=" + xpath + ", matches=" + matchCount
                    + ", idx=" + selectedIndex + "}";
        }
    }
}
