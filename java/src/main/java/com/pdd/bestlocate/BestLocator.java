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
 * Read an Android hierarchy XML and return the best locator for a given node.
 *
 * <pre>
 * Usage:
 *   BestLocator bl = new BestLocator(xmlString);
 *   LocatorResult result = bl.bestLocate("0-0-0-1-0-2");
 *   // result.type  → "content_desc" | "resource_id" | "text" | "xpath"
 *   // result.value → actual locator value
 * </pre>
 */
public class BestLocator {

    private static final Pattern BOUNDS_PATTERN = Pattern.compile("\\d+");
    private static final Pattern XML_BOUNDS_PATTERN = Pattern.compile("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]");

    // ── strategy names (returned to caller) ──
    private static final String STRATEGY_CONTENT_DESC = "contentDesc";
    private static final String STRATEGY_RESOURCE_ID = "resourceId";
    private static final String STRATEGY_TEXT = "text";
    private static final String STRATEGY_CLASS_TEXT = "classText";
    private static final String STRATEGY_CLASS_CONTENT_DESC = "classContentDesc";
    private static final String STRATEGY_CLASS = "class";
    private static final String STRATEGY_XPATH = "xpath";

    // ── locator types (output) ──
    private static final String TYPE_CONTENT_DESC = "content_desc";
    private static final String TYPE_RESOURCE_ID = "resource_id";
    private static final String TYPE_TEXT = "text";
    private static final String TYPE_XPATH = "xpath";

    // ── by-type names (internal) ──
    private static final String BY_ID = "id";
    private static final String BY_TEXT = "text";
    private static final String BY_CONTENT_DESC = "content-desc";
    private static final String BY_CLASS = "class";
    private static final String BY_CLASS_TEXT = "class_and_text";
    private static final String BY_CLASS_CONTENT_DESC = "class_and_content_desc";

    // ── indexed structures ──
    private HierarchyNode root;
    private Map<String, HierarchyNode> nodeMap;
    private List<HierarchyNode> allNodes;
    private String preferredXpath;

    /**
     * Parse an XML string into the hierarchy.
     */
    public BestLocator(String xmlData) {
        int[] size = inferSize(xmlData);
        this.root = parseHierarchy(xmlData, size[0], size[1]);
        buildIndex();
    }

    /**
     * Convenience: load XML from a file path.
     */
    public static BestLocator fromFile(String path) throws Exception {
        return new BestLocator(new String(Files.readAllBytes(Paths.get(path)), "UTF-8"));
    }

    // ================================================================
    //  Public API
    // ================================================================

    /**
     * Return the best locator (type + value) for the given node key.
     */
    public LocatorResult bestLocate(String key) {
        HierarchyNode target = nodeMap.get(key);
        if (target == null) {
            throw new IllegalArgumentException("node key not found: " + key);
        }

        // Step 1: suggest_xpath() → candidates
        List<XPathCandidate> candidates = suggestXpath(target);

        // Step 2: resolveLocatorByPriorityFast()
        // content-desc → resource-id → text → class-text → class-content-desc → class → xpath
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

        // fallback: xpath
        if (!preferredXpath.isEmpty()) {
            return new LocatorResult(TYPE_XPATH, preferredXpath);
        }
        // absolute fallback
        String xpath = candidates.isEmpty() ? "" : candidates.get(0).xpath;
        return new LocatorResult(TYPE_XPATH, xpath);
    }

    /**
     * Return all candidates for inspection / debugging.
     */
    public List<XPathCandidate> getAllCandidates(String key) {
        HierarchyNode target = nodeMap.get(key);
        if (target == null) {
            throw new IllegalArgumentException("node key not found: " + key);
        }
        return suggestXpath(target);
    }

    public HierarchyNode getRoot() { return root; }
    public Map<String, HierarchyNode> getNodeMap() { return nodeMap; }
    public List<HierarchyNode> getAllNodes() { return allNodes; }

    // ================================================================
    //  XML → tree
    // ================================================================

    private static int[] inferSize(String xml) {
        int maxX = 1, maxY = 1;
        Matcher m = XML_BOUNDS_PATTERN.matcher(xml);
        while (m.find()) {
            maxX = Math.max(maxX, Integer.parseInt(m.group(3)));
            maxY = Math.max(maxY, Integer.parseInt(m.group(4)));
        }
        return new int[]{maxX, maxY};
    }

    private static HierarchyNode parseHierarchy(String xml, int width, int height) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setExpandEntityReferences(false);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            Document doc = f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            return parseElement(doc.getDocumentElement(), width, height, new ArrayList<>(Collections.singletonList(0)));
        } catch (Exception e) {
            throw new RuntimeException("failed to parse hierarchy xml", e);
        }
    }

    private static HierarchyNode parseElement(Element el, int width, int height, List<Integer> indexes) {
        HierarchyNode node = new HierarchyNode();
        node.setKey(joinIndexes(indexes));
        node.setName(resolveNodeName(el));
        node.setProperties(readProperties(el));

        String bv = el.getAttribute("bounds");
        if (bv != null && !bv.isEmpty()) {
            List<Integer> bounds = parseBounds(bv);
            if (bounds.size() == 4) {
                int x1 = bounds.get(0), y1 = bounds.get(1), x2 = bounds.get(2), y2 = bounds.get(3);
                node.setRect(new HierarchyRect(x1, y1, x2 - x1, y2 - y1));
                node.setBounds(Arrays.asList(
                        roundNorm(x1, width), roundNorm(y1, height),
                        roundNorm(x2, width), roundNorm(y2, height)));
            }
        }

        NodeList children = el.getChildNodes();
        int childIdx = 0;
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element)) continue;
            List<Integer> ci = new ArrayList<>(indexes);
            ci.add(childIdx);
            node.getChildren().add(parseElement((Element) children.item(i), width, height, ci));
            childIdx++;
        }
        return node;
    }

    // ================================================================
    //  Index
    // ================================================================

    private void buildIndex() {
        nodeMap = new LinkedHashMap<>();
        allNodes = new ArrayList<>();
        indexRecursive(root);
    }

    private void indexRecursive(HierarchyNode node) {
        if (node == null) return;
        nodeMap.put(node.getKey(), node);
        allNodes.add(node);
        if (node.getChildren() != null) {
            for (HierarchyNode child : node.getChildren()) {
                indexRecursive(child);
            }
        }
    }

    // ================================================================
    //  suggest_xpath() core
    // ================================================================

    private List<XPathCandidate> suggestXpath(HierarchyNode selected) {
        // 1. candidate strategies sorted by match count (fewer = better)
        List<String> byCandidates = getAnchorByCandidates(selected);
        byCandidates.sort(Comparator.comparingInt(by -> matchesBy(selected, by).size()));

        String preferredBy = byCandidates.isEmpty() ? BY_CLASS : byCandidates.get(0);
        this.preferredXpath = buildXpathExpr(selected, preferredBy);
        List<HierarchyNode> prefMatches = matchesBy(selected, preferredBy);
        int prefIdx = indexOfNode(prefMatches, selected.getKey());
        if (!preferredXpath.isEmpty() && prefIdx > 0) {
            preferredXpath = "(" + preferredXpath + ")[" + (prefIdx + 1) + "]";
        }

        // 2. simple candidates
        List<XPathCandidate> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (String by : byCandidates) {
            List<HierarchyNode> matches = matchesBy(selected, by);
            int idx = indexOfNode(matches, selected.getKey());
            String xpath = buildXpathExpr(selected, by);
            if (!xpath.isEmpty() && idx > 0) {
                xpath = "(" + xpath + ")[" + (idx + 1) + "]";
            }
            String pv = extractPropertyValue(selected, by);
            candidates.add(new XPathCandidate(byToStrategy(by), pv, xpath, matches.size(), idx));
            if (!xpath.isEmpty()) seen.add(xpath);
        }

        // 3. complex xpath candidates (parent-anchored / sibling-anchored)
        candidates.addAll(buildComplexCandidates(selected, seen));

        return candidates;
    }

    // ── anchor candidates ──

    private List<String> getAnchorByCandidates(HierarchyNode node) {
        List<String> out = new ArrayList<>();
        Map<String, String> p = node.getProperties();
        out.add(BY_CLASS);
        if (hasText(p.get("resource-id"))) out.add(BY_ID);
        if (hasText(p.get("content-desc"))) out.add(BY_CONTENT_DESC);
        if (hasText(p.get("text"))) {
            out.add(BY_TEXT);
            out.add(BY_CLASS_TEXT);
        }
        if (hasText(p.get("content-desc")) && hasText(node.getName())) {
            out.add(BY_CLASS_CONTENT_DESC);
        }
        return dedupe(out);
    }

    // ── matches_by ──

    private List<HierarchyNode> matchesBy(HierarchyNode selected, String by) {
        Map<String, String> sp = selected.getProperties();
        String sName = selected.getName();
        String sText = sp.get("text");
        String sContentDesc = sp.get("content-desc");
        String sResourceId = sp.get("resource-id");

        List<HierarchyNode> matched = new ArrayList<>();
        for (HierarchyNode node : allNodes) {
            Map<String, String> np = node.getProperties();
            switch (by) {
                case BY_ID:
                    if (Objects.equals(np.get("resource-id"), sResourceId)) matched.add(node);
                    break;
                case BY_TEXT:
                    if (Objects.equals(np.get("text"), sText) && hasText(sText)) matched.add(node);
                    break;
                case BY_CONTENT_DESC:
                    if (Objects.equals(np.get("content-desc"), sContentDesc)) matched.add(node);
                    break;
                case BY_CLASS:
                    if (Objects.equals(node.getName(), sName)) matched.add(node);
                    break;
                case BY_CLASS_TEXT:
                    if (Objects.equals(node.getName(), sName)
                            && Objects.equals(np.get("text"), sText)
                            && hasText(sText)) matched.add(node);
                    break;
                case BY_CLASS_CONTENT_DESC:
                    if (Objects.equals(node.getName(), sName)
                            && Objects.equals(np.get("content-desc"), sContentDesc)) matched.add(node);
                    break;
            }
        }
        return matched;
    }

    // ── build_xpath ──

    private String buildXpathExpr(HierarchyNode node, String by) {
        Map<String, String> p = node.getProperties();
        String name = node.getName();
        switch (by) {
            case BY_ID: {
                String rid = p.get("resource-id");
                return hasText(rid) ? "//*[@resource-id=" + quoteXLiteral(rid) + "]" : "";
            }
            case BY_TEXT: {
                String t = p.get("text");
                return hasText(t) ? "//*[@text=" + quoteXLiteral(t) + "]" : "";
            }
            case BY_CONTENT_DESC: {
                String cd = p.get("content-desc");
                return hasText(cd) ? "//*[@content-desc=" + quoteXLiteral(cd) + "]" : "";
            }
            case BY_CLASS: {
                if (!hasText(name)) return "";
                return isValidXmlName(name) ? "//" + name : "//*[@class=" + quoteXLiteral(name) + "]";
            }
            case BY_CLASS_TEXT: {
                String t = p.get("text");
                if (!hasText(name) || !hasText(t)) return "";
                if (isValidXmlName(name)) return "//" + name + "[@text=" + quoteXLiteral(t) + "]";
                return "//*[@class=" + quoteXLiteral(name) + " and @text=" + quoteXLiteral(t) + "]";
            }
            case BY_CLASS_CONTENT_DESC: {
                String cd = p.get("content-desc");
                if (!hasText(name) || !hasText(cd)) return "";
                if (isValidXmlName(name)) return "//" + name + "[@content-desc=" + quoteXLiteral(cd) + "]";
                return "//*[@class=" + quoteXLiteral(name) + " and @content-desc=" + quoteXLiteral(cd) + "]";
            }
            default: return "";
        }
    }

    // ── complex xpath candidates ──

    private List<XPathCandidate> buildComplexCandidates(HierarchyNode selected, Set<String> seen) {
        List<XPathCandidate> out = new ArrayList<>();
        String selectedKey = selected.getKey();
        if (!selectedKey.contains("-")) return out;

        String parentKey = selectedKey.substring(0, selectedKey.lastIndexOf('-'));
        HierarchyNode parent = nodeMap.get(parentKey);
        if (parent == null || parent.getChildren().isEmpty()) return out;

        int selectedIdx = childIndex(parent, selectedKey);
        if (selectedIdx < 0) return out;

        String sName = selected.getName();
        int sameClassPos = sameClassPosition(parent, selectedIdx, sName);

        // parent anchors (unique xpaths)
        List<String[]> parentAnchors = uniqueAnchorXpaths(parent);

        // Type A: parent-anchored
        for (String[] anchor : parentAnchors) {
            String px = anchor[1];
            String by = anchor[0];

            // child index
            String childX = "(" + px + "/*[" + (selectedIdx + 1) + "])";
            addComplex(out, selectedKey, childX, "parent_" + by + "_child_index", seen);

            // same-class index
            String scX;
            if (isValidXmlName(sName)) {
                scX = "(" + px + "/" + sName + ")[" + sameClassPos + "]";
            } else {
                scX = "(" + px + "/*[@class=" + quoteXLiteral(sName) + "])[" + sameClassPos + "]";
            }
            addComplex(out, selectedKey, scX, "parent_" + by + "_same_class_index", seen);
        }

        // Type B: sibling-anchored
        for (int si = 0; si < parent.getChildren().size(); si++) {
            if (si == selectedIdx) continue;
            HierarchyNode sibling = parent.getChildren().get(si);
            boolean isFollowing = si < selectedIdx;
            String direction = isFollowing ? "following-sibling" : "preceding-sibling";
            String suffix = isFollowing ? "following" : "preceding";

            int betweenStart = isFollowing ? si + 1 : selectedIdx;
            int betweenEnd = isFollowing ? selectedIdx : si;
            int step = 0;
            for (int i = betweenStart; i < betweenEnd; i++) {
                if (Objects.equals(parent.getChildren().get(i).getName(), sName)) step++;
            }
            if (step <= 0) continue;

            String axis = axisExpr(sName, direction);

            // B1: sibling's own anchor
            for (String[] anchor : uniqueAnchorXpaths(sibling)) {
                String xx = "(" + anchor[1] + "/" + axis + ")[" + step + "]";
                addComplex(out, selectedKey, xx, "sibling_" + anchor[0] + "_" + suffix, seen);
            }
        }

        return out;
    }

    private List<String[]> uniqueAnchorXpaths(HierarchyNode node) {
        List<String[]> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String by : getAnchorByCandidates(node)) {
            String xpath = buildXpathExpr(node, by);
            if (xpath.isEmpty() || seen.contains(xpath)) continue;
            List<HierarchyNode> matches = matchesBy(node, by);
            if (matches.size() != 1) continue;
            seen.add(xpath);
            result.add(new String[]{by, xpath});
        }
        return result;
    }

    private void addComplex(List<XPathCandidate> sink, String key, String xpath, String name, Set<String> seen) {
        if (xpath.isEmpty() || seen.contains(xpath)) return;
        seen.add(xpath);
        sink.add(new XPathCandidate(STRATEGY_XPATH, xpath, xpath, 1, 0));
    }

    // ================================================================
    //  Helpers
    // ================================================================

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

    // ── XPath utils ──

    static String quoteXLiteral(String value) {
        if (value == null) return "\"\"";
        if (!value.contains("\"")) return "\"" + value + "\"";
        if (!value.contains("'")) return "'" + value + "'";
        StringBuilder sb = new StringBuilder("concat(");
        String[] parts = value.split("\"");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(", '\"', ");
            sb.append('"').append(parts[i]).append('"');
        }
        sb.append(")");
        return sb.toString();
    }

    static boolean isValidXmlName(String name) {
        return name != null && name.matches("^[A-Za-z_][A-Za-z0-9._-]*$");
    }

    // ── index utils ──

    private static int indexOfNode(List<HierarchyNode> nodes, String key) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).getKey().equals(key)) return i;
        }
        return -1;
    }

    private static int childIndex(HierarchyNode parent, String childKey) {
        for (int i = 0; i < parent.getChildren().size(); i++) {
            if (parent.getChildren().get(i).getKey().equals(childKey)) return i;
        }
        return -1;
    }

    private static int sameClassPosition(HierarchyNode parent, int selectedIdx, String name) {
        int pos = 0;
        for (int i = 0; i <= selectedIdx; i++) {
            if (Objects.equals(parent.getChildren().get(i).getName(), name)) pos++;
        }
        return Math.max(pos, 1);
    }

    private static String axisExpr(String name, String axis) {
        if (isValidXmlName(name)) return axis + "::" + name;
        return axis + "::*[@class=" + quoteXLiteral(name) + "]";
    }

    // ── common utils ──

    private static String resolveNodeName(Element el) {
        if ("node".equals(el.getTagName())) {
            String cls = el.getAttribute("class");
            if (cls != null && !cls.isEmpty()) return cls;
        }
        return el.getTagName();
    }

    private static LinkedHashMap<String, String> readProperties(Element el) {
        LinkedHashMap<String, String> props = new LinkedHashMap<>();
        NamedNodeMap attrs = el.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            props.put(attrs.item(i).getNodeName(), attrs.item(i).getNodeValue());
        }
        return props;
    }

    private static List<Integer> parseBounds(String v) {
        List<Integer> bounds = new ArrayList<>();
        Matcher m = BOUNDS_PATTERN.matcher(v);
        while (m.find()) bounds.add(Integer.parseInt(m.group()));
        return bounds;
    }

    private static double roundNorm(int value, int size) {
        if (size <= 0) return 0D;
        return Math.round((value * 1.0 / size) * 10000D) / 10000D;
    }

    private static String joinIndexes(List<Integer> indexes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < indexes.size(); i++) {
            if (i > 0) sb.append("-");
            sb.append(indexes.get(i));
        }
        return sb.toString();
    }

    private static boolean hasText(String s) {
        return s != null && !s.isEmpty();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static <T> List<T> dedupe(List<T> items) {
        List<T> out = new ArrayList<>();
        Set<T> seen = new HashSet<>();
        for (T item : items) {
            if (seen.add(item)) out.add(item);
        }
        return out;
    }

    // ================================================================
    //  XPathCandidate (internal)
    // ================================================================

    public static class XPathCandidate {
        public final String strategy;
        public final String propertyValue;
        public final String xpath;
        public final int matchCount;
        public final int selectedIndex;

        XPathCandidate(String strategy, String propertyValue, String xpath, int matchCount, int selectedIndex) {
            this.strategy = strategy;
            this.propertyValue = propertyValue;
            this.xpath = xpath;
            this.matchCount = matchCount;
            this.selectedIndex = selectedIndex;
        }

        @Override
        public String toString() {
            return "XC{strategy=" + strategy + ", pv='" + propertyValue + "', xpath=" + xpath
                    + ", matches=" + matchCount + ", idx=" + selectedIndex + "}";
        }
    }
}
