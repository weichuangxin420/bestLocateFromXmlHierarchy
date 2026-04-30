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
 * 读取 Android UIAutomator 层级 XML，对任意节点返回最佳定位器。
 *
 * <p>算法源自 uiautodev 的 {@code suggest_xpath()} +
 * {@code resolveLocatorByPriorityFast()} 流程。</p>
 *
 * <h3>算法概览</h3>
 * <ol>
 *   <li><b>XML → 层级树</b> — 解析 DOM，分配 DFS 兄弟索引 key</li>
 *   <li><b>树 → 扁平索引</b> — 前序遍历 + 4 个属性倒排索引</li>
 *   <li><b>suggest_xpath()</b> — 生成简单 + 复杂 XPath 候选，
 *       按唯一性排序（匹配数升序）</li>
 *   <li><b>resolvePriorityFast()</b> — 优先级链首个命中：
 *       content-desc → resource-id → text → class-text →
 *       class-content-desc → class → xpath</li>
 * </ol>
 *
 * <h3>用法</h3>
 * <pre>{@code
 *   BestLocator bl = new BestLocator(xmlString);
 *   LocatorResult result = bl.bestLocate("0-0-0-1-0-2");
 *   // result.getType()  → "content_desc" | "resource_id" | "text" | "xpath"
 *   // result.getValue() → 实际的定位值
 * }</pre>
 */
public class BestLocator {

    // 正则表达式，编译一次全局复用。
    private static final Pattern BOUNDS_PATTERN = Pattern.compile("\\d+");
    private static final Pattern XML_BOUNDS_PATTERN =
            Pattern.compile("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]");

    // ---- 输出定位器类型（返回到 LocatorResult.type） ----
    /** Accessibility ID / content-desc — 最快最稳定。 */
    private static final String TYPE_CONTENT_DESC = "content_desc";
    /** Resource ID — 速度快且唯一（Appium 基准 ~35ms）。 */
    private static final String TYPE_RESOURCE_ID = "resource_id";
    /** 文本内容 — 快速但可能随多语言变化。 */
    private static final String TYPE_TEXT = "text";
    /** XPath — 慢（~120ms）且脆弱，仅作最后手段。 */
    private static final String TYPE_XPATH = "xpath";

    // ---- 内部 "by" 类型（matchesBy / buildXpathExpr 使用的 key） ----
    private static final String BY_ID = "id";
    private static final String BY_TEXT = "text";
    private static final String BY_CONTENT_DESC = "content-desc";
    private static final String BY_CLASS = "class";
    private static final String BY_CLASS_TEXT = "class_and_text";
    private static final String BY_CLASS_CONTENT_DESC = "class_and_content_desc";

    // ================================================================
    //  实例状态 — 构造时一次性构建
    // ================================================================

    /** 解析后的层级树根节点。 */
    private HierarchyNode root;
    /** key → node 映射表，O(1) 查找。 */
    private Map<String, HierarchyNode> nodeMap;
    /** 前序遍历的全部节点（扁平列表）。 */
    private List<HierarchyNode> allNodes;
    /** 缓存的首选 XPath，用作 bestLocate() 的兜底。 */
    private String preferredXpath;

    /**
     * 属性倒排索引 — 在 buildIndex() 中构建，
     * 使 matchesBy() 实现 O(1) 属性查找。
     * 每个索引将属性值映射到拥有该值的节点列表。
     */
    private Map<String, List<HierarchyNode>> byRid = new HashMap<>();
    private Map<String, List<HierarchyNode>> byText = new HashMap<>();
    private Map<String, List<HierarchyNode>> byCd = new HashMap<>();
    private Map<String, List<HierarchyNode>> byClass = new HashMap<>();

    // ================================================================
    //  构造方法
    // ================================================================

    /**
     * 将 XML 字符串解析为层级树，然后构建全部索引。
     *
     * @param xmlData 原始 UIAutomator dump XML 字符串
     */
    public BestLocator(String xmlData) {
        int[] size = inferSize(xmlData);
        this.root = parseHierarchy(xmlData, size[0], size[1]);
        buildIndex();
    }

    /**
     * 便捷方法：从文件路径加载 XML。
     *
     * @param path 层级 XML 文件路径
     * @return 完全初始化好的 BestLocator 实例
     * @throws Exception 文件读取失败时抛出
     */
    public static BestLocator fromFile(String path) throws Exception {
        return new BestLocator(new String(Files.readAllBytes(Paths.get(path)), "UTF-8"));
    }

    // ================================================================
    //  公开 API
    // ================================================================

    /**
     * 对给定节点 key 返回最佳定位器 (type + value)。
     *
     * <p>这是主入口方法，完整执行算法流程:</p>
     * <ol>
     *   <li>通过 key 查找目标节点 (O(1))</li>
     *   <li>suggestXpath() → 生成所有候选</li>
     *   <li>resolvePriorityFast() → 按固定优先级链选取首个命中:
     *       content-desc → resource-id → text →
     *       class-text → class-content-desc → class → xpath</li>
     * </ol>
     *
     * <p>优先级顺序遵循行业共识:
     * content-desc 和 resource-id 最快最稳定（~35ms），
     * XPath 慢且脆弱（~120ms），仅作最后手段。</p>
     *
     * @param key 节点 key（如 "0-0-1-2"）
     * @return 最佳定位器，永不为 null
     * @throws IllegalArgumentException key 未找到时抛出
     */
    public LocatorResult bestLocate(String key) {
        HierarchyNode target = nodeMap.get(key);
        if (target == null) {
            throw new IllegalArgumentException("node key not found: " + key);
        }

        // 阶段3: 生成所有候选。
        List<XPathCandidate> candidates = suggestXpath(target);

        // 阶段4: resolveLocatorByPriorityFast()。
        // 固定优先级链 — 首个 propertyValue 非空的候选即胜出。
        LocateType[] priorityOrder = {
                LocateType.CONTENT_DESC, LocateType.RESOURCE_ID, LocateType.TEXT,
                LocateType.DESCRIPTION, LocateType.CLASS
        };

        for (LocateType type : priorityOrder) {
            for (XPathCandidate xc : candidates) {
                if (type == xc.locateType && !xc.propertyValue.isEmpty()) {
                    return new LocatorResult(toLocatorType(xc.locateType), xc.propertyValue);
                }
            }
        }

        // 兜底1: 首选 XPath（最唯一的简单策略）。
        if (!preferredXpath.isEmpty()) {
            return new LocatorResult(TYPE_XPATH, preferredXpath);
        }

        // 兜底2: 最后的最后。
        String xpath = candidates.isEmpty() ? "" : candidates.get(0).xpath;
        return new LocatorResult(TYPE_XPATH, xpath);
    }

    /**
     * 返回全部候选列表，用于调试 / 检查。
     *
     * @param key 节点 key
     * @return 包含复杂候选在内的完整 XPath 候选列表
     */
    public List<XPathCandidate> getAllCandidates(String key) {
        HierarchyNode target = nodeMap.get(key);
        if (target == null) {
            throw new IllegalArgumentException("node key not found: " + key);
        }
        return suggestXpath(target);
    }

    /** @return 解析后的层级树根节点 */
    public HierarchyNode getRoot() { return root; }

    /** @return key → node 查找映射表 */
    public Map<String, HierarchyNode> getNodeMap() { return nodeMap; }

    /** @return 前序遍历的全部节点 */
    public List<HierarchyNode> getAllNodes() { return allNodes; }

    // ================================================================
    //  阶段1: XML → HierarchyNode 树
    // ================================================================

    /**
     * 从 XML 中推断屏幕分辨率（扫描所有 bounds 取最大值）。
     * 扫描所有 {@code [x1,y1][x2,y2]} 格式的 bounds，返回 (maxX, maxY)。
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
     * 将完整 XML 字符串解析为 HierarchyNode 树。
     * 使用 DOM 解析器并启用安全特性标志（禁用外部实体）。
     */
    private static HierarchyNode parseHierarchy(String xml, int width, int height) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setExpandEntityReferences(false);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            Document doc = f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            // 根元素是 <hierarchy>；第一个子元素是第一个 <node>。
            return parseElement(doc.getDocumentElement(), width, height,
                    new ArrayList<>(Collections.singletonList(0)));
        } catch (Exception e) {
            throw new RuntimeException("failed to parse hierarchy xml", e);
        }
    }

    /**
     * 递归将一个 DOM 元素解析为 HierarchyNode。
     *
     * <p><b>Key 生成规则:</b> 每个节点的 key 是其 DFS 兄弟索引路径，
     * 如 root="0", 第一个子="0-0", 第二个子的第三个="0-1-2"。</p>
     *
     * <p><b>Bounds 归一化:</b> 像素 bounds [x1,y1][x2,y2] 除以屏幕尺寸，
     * 转为 0~1 范围的值，使其与分辨率无关。</p>
     */
    private static HierarchyNode parseElement(Element el, int width, int height,
                                               List<Integer> indexes) {
        HierarchyNode node = new HierarchyNode();
        node.setKey(joinIndexes(indexes));
        node.setName(resolveNodeName(el));
        node.setProperties(readProperties(el));

        // 解析 "[x1,y1][x2,y2]" 格式的 bounds 并做归一化。
        String bv = el.getAttribute("bounds");
        if (bv != null && !bv.isEmpty()) {
            List<Integer> bounds = parseBounds(bv);
            if (bounds.size() == 4) {
                int x1 = bounds.get(0), y1 = bounds.get(1),
                    x2 = bounds.get(2), y2 = bounds.get(3);
                // rect = 像素坐标（供前端 overlay 绘制）。
                node.setRect(new HierarchyRect(x1, y1, x2 - x1, y2 - y1));
                // bounds = 归一化 0~1 范围（供坐标匹配）。
                node.setBounds(Arrays.asList(
                        roundNorm(x1, width), roundNorm(y1, height),
                        roundNorm(x2, width), roundNorm(y2, height)));
            }
        }

        // 仅递归解析子 <node> 元素（跳过 #text 等节点）。
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
    //  阶段2: 索引构建
    // ================================================================

    /**
     * 前序遍历展平树，同时建立 4 个倒排索引，
     * 使 matchesBy() 实现 O(1) 属性查找。
     *
     * <p>建立的索引:</p>
     * <ul>
     *   <li>{@code byRid}   — resource-id 值 → 匹配节点列表</li>
     *   <li>{@code byText}  — text 值 → 匹配节点列表</li>
     *   <li>{@code byCd}    — content-desc 值 → 匹配节点列表</li>
     *   <li>{@code byClass} — class 名称 → 匹配节点列表</li>
     * </ul>
     *
     * <p>仅索引非空的属性值。</p>
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
     * buildIndex() 的递归辅助方法 — 填充 nodeMap、allNodes
     * 及全部 4 个属性倒排索引。
     */
    private void indexRecursive(HierarchyNode node) {
        if (node == null) return;

        // 标准索引: key → node，扁平列表。
        nodeMap.put(node.getKey(), node);
        allNodes.add(node);

        // 属性倒排索引: 填充全部 4 个映射表。
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
        // class 名称始终存在（从 XML tag 或 class 属性派生）。
        byClass.computeIfAbsent(node.getName(), k -> new ArrayList<>()).add(node);

        if (node.getChildren() != null) {
            for (HierarchyNode child : node.getChildren()) {
                indexRecursive(child);
            }
        }
    }

    // ================================================================
    //  阶段3: suggestXpath() — 主入口
    // ================================================================

    /**
     * 为给定节点生成所有定位候选。
     *
     * <p>步骤:</p>
     * <ol>
     *   <li>getAnchorByCandidates() — 根据目标节点具有哪些属性，
     *       决定哪些定位策略适用。</li>
     *   <li>按匹配数升序排列策略 — 匹配越少 = 越唯一。</li>
     *   <li>对每个策略，构建 XPath 表达式，统计匹配数，生成候选。</li>
     *   <li>buildComplexCandidates() — 生成父级锚定和同级锚定的
     *       XPath 候选，用于简单策略不够唯一的场景。</li>
     * </ol>
     *
     * @param selected 目标节点
     * @return 全部候选，按策略优先级排序
     */
    private List<XPathCandidate> suggestXpath(HierarchyNode selected) {
        // 步骤 1 & 2: 获取候选策略，按匹配数升序排列（越少越好）。
        List<String> byCandidates = getAnchorByCandidates(selected);
        byCandidates.sort(Comparator.comparingInt(
                by -> matchesBy(selected, by).size()));

        // 首个（最唯一）策略即为"首选"。
        String preferredBy = byCandidates.isEmpty() ? BY_CLASS : byCandidates.get(0);
        this.preferredXpath = buildXpathExpr(selected, preferredBy);
        List<HierarchyNode> prefMatches = matchesBy(selected, preferredBy);
        int prefIdx = indexOfNode(prefMatches, selected.getKey());
        // 如果不是第一个匹配节点，追加位置索引: (//expr)[n]。
        if (!preferredXpath.isEmpty() && prefIdx > 0) {
            preferredXpath = "(" + preferredXpath + ")[" + (prefIdx + 1) + "]";
        }

        // 步骤 3: 为每个适用策略构建候选。
        List<XPathCandidate> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (String by : byCandidates) {
            List<HierarchyNode> matches = matchesBy(selected, by);
            int idx = indexOfNode(matches, selected.getKey());
            String xpath = buildXpathExpr(selected, by);
            // 如果不是第一个匹配，追加位置索引 [n]。
            if (!xpath.isEmpty() && idx > 0) {
                xpath = "(" + xpath + ")[" + (idx + 1) + "]";
            }
            String pv = extractPropertyValue(selected, by);
            candidates.add(new XPathCandidate(
                    byToLocateType(by), pv, xpath, matches.size(), idx));
            if (!xpath.isEmpty()) seen.add(xpath);
        }

        // 步骤 4: 生成复杂候选（父级/同级锚定）。
        candidates.addAll(buildComplexCandidates(selected, seen));
        return candidates;
    }

    // ================================================================
    //  getAnchorByCandidates — 确定适用的定位策略
    // ================================================================

    /**
     * 根据目标节点实际拥有的属性，决定哪些定位策略可用。
     *
     * <p>class 始终包含（通用兜底）。其他策略仅在对应属性值
     * 非空时加入。</p>
     *
     * <p>顺序: class, id, content-desc, text, class_and_text,
     * class_and_content_desc — 在 suggestXpath() 中
     * 会按匹配数重新排序。</p>
     */
    private List<String> getAnchorByCandidates(HierarchyNode node) {
        List<String> out = new ArrayList<>();
        Map<String, String> p = node.getProperties();

        out.add(BY_CLASS);                              // 始终存在（兜底）
        if (hasText(p.get("resource-id"))) out.add(BY_ID);
        if (hasText(p.get("content-desc"))) out.add(BY_CONTENT_DESC);
        if (hasText(p.get("text"))) {
            out.add(BY_TEXT);                           // 纯文本
            out.add(BY_CLASS_TEXT);                     // class + text 组合
        }
        if (hasText(p.get("content-desc")) && hasText(node.getName())) {
            out.add(BY_CLASS_CONTENT_DESC);             // class + content-desc 组合
        }
        return dedupe(out);
    }

    // ================================================================
    //  matchesBy — O(1) 属性查找（通过预建索引）
    // ================================================================

    /**
     * 查找所有与给定策略匹配的节点。
     *
     * <p>使用预建的倒排索引（byRid, byText, byCd, byClass）
     * 实现 O(1) 查找，而非扫描全部节点。对于组合策略
     * （class_and_text, class_and_content_desc），
     * 计算两个索引的集合交集。</p>
     *
     * <p>这是优化前的性能瓶颈 — 每次调用曾需扫描 O(n) 个节点。
     * 现在已降至 O(1)。</p>
     */
    private List<HierarchyNode> matchesBy(HierarchyNode selected, String by) {
        Map<String, String> sp = selected.getProperties();
        String sName = selected.getName();

        switch (by) {
            // 单属性策略: 直接 O(1) 映射表查找。
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
            // 组合策略: 两个索引的集合交集。
            // O(min(|A|, |B|)) — 仍远快于扫描全部节点。
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
    //  buildXpathExpr — 为策略构建 XPath 表达式
    // ================================================================

    /**
     * 为给定节点和定位策略构建 XPath 表达式。
     *
     * <p>单属性策略生成简单的属性表达式:</p>
     * <pre>
     *   id          → //*[@resource-id="com.example:id/btn"]
     *   text        → //*[@text="OK"]
     *   content-desc → //*[@content-desc="Submit"]</pre>
     *
     * <p>基于 class 的策略生成标签或 class 表达式:</p>
     * <pre>
     *   合法 XML 名称  → //android.widget.Button
     *   非法 XML 名称  → //*[@class="com.example.CustomView"]</pre>
     *
     * <p>组合策略使用谓词语法:</p>
     * <pre>
     *   class+text → //android.widget.Button[@text="OK"]</pre>
     *
     * @return XPath 字符串，所需属性缺失时返回 ""
     */
    private String buildXpathExpr(HierarchyNode node, String by) {
        Map<String, String> p = node.getProperties();
        String name = node.getName();

        switch (by) {
            // ---- 单属性策略 ----
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
                // 如果类名是合法的 XML 元素名，使用标签选择器 (//Button)。
                // 否则使用 @class 属性选择器。
                return isValidXmlName(name)
                        ? "//" + name
                        : "//*[@class=" + quoteXLiteral(name) + "]";
            }
            // ---- 组合策略 ----
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
    //  阶段3d: 复杂 XPath 候选
    // ================================================================

    /**
     * 当简单 XPath 不够唯一时，生成复杂 XPath 候选。
     *
     * <h4>类型 A — 父级锚定</h4>
     * <p>使用具有唯一属性的父节点作锚点，
     * 然后通过子节点索引指定目标节点。</p>
     * <pre>
     *   (//*[@resource-id="title_bar"]/*[3])
     *   (//*[@resource-id="title_bar"]/android.widget.Button)[2]</pre>
     *
     * <h4>类型 B — 同级锚定</h4>
     * <p>使用可唯一定位的兄弟节点作参照，
     * 通过 XPath 轴定位目标节点。</p>
     * <pre>
     *   (//*[@content-desc="back"]/following-sibling::Button)[1]</pre>
     *
     * <p>仅在简单策略无法生成唯一定位器时才调用。</p>
     */
    private List<XPathCandidate> buildComplexCandidates(HierarchyNode selected,
                                                         Set<String> seen) {
        List<XPathCandidate> out = new ArrayList<>();
        String selectedKey = selected.getKey();

        // 根节点（key="0"）没有父节点 — 无法生成复杂候选。
        if (!selectedKey.contains("-")) return out;

        // 通过去掉最后一段索引找到父节点。
        String parentKey = selectedKey.substring(0, selectedKey.lastIndexOf('-'));
        HierarchyNode parent = nodeMap.get(parentKey);
        if (parent == null || parent.getChildren().isEmpty()) return out;

        int selectedIdx = childIndex(parent, selectedKey);
        if (selectedIdx < 0) return out;

        String sName = selected.getName();
        // 同级同类节点中的位次（1-based）。
        int sameClassPos = sameClassPosition(parent, selectedIdx, sName);
        // 父节点唯一锚定策略（匹配数 == 1）。
        List<String[]> parentAnchors = uniqueAnchorXpaths(parent);

        // ---- 类型 A: 父级锚定 ----
        for (String[] anchor : parentAnchors) {
            String px = anchor[1];   // 父节点 xpath
            String by = anchor[0];  // 父节点 by-type

            // A1: 父节点下任意子节点的索引定位。
            String childX = "(" + px + "/*[" + (selectedIdx + 1) + "])";
            addComplex(out, selectedKey, childX,
                    "parent_" + by + "_child_index", seen);

            // A2: 父节点下仅同类子节点的索引定位。
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

        // ---- 类型 B: 同级锚定 ----
        for (int si = 0; si < parent.getChildren().size(); si++) {
            if (si == selectedIdx) continue;

            HierarchyNode sibling = parent.getChildren().get(si);
            boolean isFollowing = si < selectedIdx;           // 兄弟在目标节点之前
            String direction = isFollowing
                    ? "following-sibling" : "preceding-sibling";
            String suffix = isFollowing ? "following" : "preceding";

            // 统计兄弟与目标之间有多少个同类节点。
            int betweenStart = isFollowing ? si + 1 : selectedIdx;
            int betweenEnd = isFollowing ? selectedIdx : si;
            int step = 0;
            for (int i = betweenStart; i < betweenEnd; i++) {
                if (Objects.equals(parent.getChildren().get(i).getName(), sName))
                    step++;
            }
            if (step <= 0) continue;

            String axis = axisExpr(sName, direction);

            // B1: 以兄弟节点自身的唯一锚点为基础。
            for (String[] anchor : uniqueAnchorXpaths(sibling)) {
                String xx = "(" + anchor[1] + "/" + axis + ")[" + step + "]";
                addComplex(out, selectedKey, xx,
                        "sibling_" + anchor[0] + "_" + suffix, seen);
            }
        }
        return out;
    }

    /**
     * 查找能够唯一标识给定节点的 XPath 表达式。
     *
     * <p>对每个适用策略，构建 XPath 并检查在整棵树中是否恰好
     * 匹配 1 个节点。仅返回唯一的 XPath。</p>
     *
     * @return (byType, xpathExpression) 对列表
     */
    private List<String[]> uniqueAnchorXpaths(HierarchyNode node) {
        List<String[]> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String by : getAnchorByCandidates(node)) {
            String xpath = buildXpathExpr(node, by);
            if (xpath.isEmpty() || seen.contains(xpath)) continue;
            List<HierarchyNode> matches = matchesBy(node, by);
            if (matches.size() != 1) continue;    // 必须唯一
            seen.add(xpath);
            result.add(new String[]{by, xpath});
        }
        return result;
    }

    /**
     * 向候选列表添加一个复杂 XPath 候选（去重）。
     * 复杂候选统一使用 locateType=LocateType.XPATH，matchCount=1
     * （因为是结构锚定的，天然唯一）。
     */
    private void addComplex(List<XPathCandidate> sink, String key,
                            String xpath, String name, Set<String> seen) {
        if (xpath.isEmpty() || seen.contains(xpath)) return;
        seen.add(xpath);
        sink.add(new XPathCandidate(LocateType.XPATH, xpath, xpath, 1, 0));
    }

    // ================================================================
    //  映射辅助函数（内部 → 输出）
    // ================================================================

    /** 将内部定位枚举映射为输出定位器类型。 */
    private static String toLocatorType(LocateType locateType) {
        return locateType == null ? TYPE_XPATH : locateType.getResultType();
    }

    /**
     * 提取给定 "by" 类型对应的实际属性值。
     * 成为 candidate.propertyValue — 最终返回给调用者的 LocatorResult.value。
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

    /** 将内部 "by" 类型映射为定位类型。 */
    private static LocateType byToLocateType(String by) {
        switch (by) {
            case BY_ID: return LocateType.RESOURCE_ID;
            case BY_TEXT:
            case BY_CLASS_TEXT: return LocateType.TEXT;
            case BY_CONTENT_DESC: return LocateType.CONTENT_DESC;
            case BY_CLASS: return LocateType.CLASS;
            case BY_CLASS_CONTENT_DESC: return LocateType.DESCRIPTION;
            default: return LocateType.XPATH;
        }
    }

    // ================================================================
    //  XPath 字面量工具函数
    // ================================================================

    /**
     * 为 XPath 字面量对比中的字符串值加引号。
     *
     * <p>处理属性值中同时包含双引号和单引号的边界情况，
     * 必要时使用 XPath concat() 函数:</p>
     * <pre>
     *   "hello"     → "hello"         （使用双引号）
     *   it's        → "it's"          （双引号包裹含单引号的值）
     *   he said "hi"  → 'he said "hi"'  （单引号包裹含双引号的值）
     *   a"b'c       → concat("a", '"', "b'c")  （同时含两种引号）</pre>
     */
    static String quoteXLiteral(String value) {
        if (value == null) return "\"\"";
        if (!value.contains("\"")) return "\"" + value + "\"";
        if (!value.contains("'")) return "'" + value + "'";
        // 两种引号同时存在 — 使用 concat() 拼接各段。
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
     * 检查字符串是否为合法的 XML 元素名。
     * 合法名称以字母/下划线开头，后续可为字母/数字/._/-
     * 用于决定使用标签选择器 (//Button) 还是属性选择器
     * (//*[@class="foo.bar.Baz$Inner"])。
     */
    static boolean isValidXmlName(String name) {
        return name != null && name.matches("^[A-Za-z_][A-Za-z0-9._-]*$");
    }

    // ================================================================
    //  树 / 索引工具函数
    // ================================================================

    /** 在节点列表中按 key 查找位置，未找到返回 -1。 */
    private static int indexOfNode(List<HierarchyNode> nodes, String key) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).getKey().equals(key)) return i;
        }
        return -1;
    }

    /** 在父节点的 children 列表中查找子节点索引。 */
    private static int childIndex(HierarchyNode parent, String childKey) {
        for (int i = 0; i < parent.getChildren().size(); i++) {
            if (parent.getChildren().get(i).getKey().equals(childKey)) return i;
        }
        return -1;
    }

    /**
     * 统计到 selectedIdx 为止有多少个同类兄弟节点。
     * 返回 1-based 位置。用于构建 "父节点下第n个Button" 表达式。
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
     * 构建 XPath 轴表达式，如 "following-sibling::Button"
     * 或 "preceding-sibling::*[@class='CustomView']"。
     */
    private static String axisExpr(String name, String axis) {
        if (isValidXmlName(name)) return axis + "::" + name;
        return axis + "::*[@class=" + quoteXLiteral(name) + "]";
    }

    // ================================================================
    //  通用辅助函数
    // ================================================================

    /**
     * 从 XML 元素中提取有效节点名。
     * 对 {@code <node class="Foo">}，名称为 "Foo"。
     * 对其他元素，名称为标签名本身。
     */
    private static String resolveNodeName(Element el) {
        if ("node".equals(el.getTagName())) {
            String cls = el.getAttribute("class");
            if (cls != null && !cls.isEmpty()) return cls;
        }
        return el.getTagName();
    }

    /** 读取 XML 元素的所有属性到 LinkedHashMap 中。 */
    private static LinkedHashMap<String, String> readProperties(Element el) {
        LinkedHashMap<String, String> props = new LinkedHashMap<>();
        NamedNodeMap attrs = el.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            props.put(attrs.item(i).getNodeName(), attrs.item(i).getNodeValue());
        }
        return props;
    }

    /** 将 "[x1,y1][x2,y2]" 解析为 [x1, y1, x2, y2]。 */
    private static List<Integer> parseBounds(String v) {
        List<Integer> bounds = new ArrayList<>();
        Matcher m = BOUNDS_PATTERN.matcher(v);
        while (m.find()) bounds.add(Integer.parseInt(m.group()));
        return bounds;
    }

    /**
     * 将像素坐标归一化到 0~1 范围，保留 4 位小数。
     * 使得 bounds 与分辨率无关。
     */
    private static double roundNorm(int value, int size) {
        if (size <= 0) return 0D;
        return Math.round((value * 1.0 / size) * 10000D) / 10000D;
    }

    /** 将 DFS 路径索引拼接为 key 字符串: [0,1,2] → "0-1-2"。 */
    private static String joinIndexes(List<Integer> indexes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < indexes.size(); i++) {
            if (i > 0) sb.append("-");
            sb.append(indexes.get(i));
        }
        return sb.toString();
    }

    /** 字符串非 null 且非空时返回 true。 */
    private static boolean hasText(String s) {
        return s != null && !s.isEmpty();
    }

    /** null 转空字符串。 */
    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 列表去重，保持插入顺序。 */
    private static <T> List<T> dedupe(List<T> items) {
        List<T> out = new ArrayList<>();
        Set<T> seen = new HashSet<>();
        for (T item : items) {
            if (seen.add(item)) out.add(item);
        }
        return out;
    }

    // ================================================================
    //  XPathCandidate — 内部数据类
    // ================================================================

    /**
     * suggestXpath() 生成的一个定位候选。
     *
     * <p>字段说明:</p>
     * <ul>
     *   <li><b>locateType</b> — 定位类型（resource-id、content-desc 等）</li>
     *   <li><b>propertyValue</b> — 提取的属性值，成为 LocatorResult.value</li>
     *   <li><b>xpath</b> — 生成的 XPath 表达式</li>
     *   <li><b>matchCount</b> — 匹配节点数（1 = 唯一）</li>
     *   <li><b>selectedIndex</b> — 目标在匹配列表中的位置（0-based）</li>
     * </ul>
     */
    public static class XPathCandidate {
        /** 定位类型 */
        public final LocateType locateType;
        /** 提取的属性值 */
        public final String propertyValue;
        /** XPath 表达式 */
        public final String xpath;
        /** 匹配节点数 */
        public final int matchCount;
        /** 目标在匹配列表中的位置 */
        public final int selectedIndex;

        XPathCandidate(LocateType locateType, String propertyValue, String xpath,
                       int matchCount, int selectedIndex) {
            this.locateType = locateType;
            this.propertyValue = propertyValue;
            this.xpath = xpath;
            this.matchCount = matchCount;
            this.selectedIndex = selectedIndex;
        }

        @Override
        public String toString() {
            return "XC{locateType=" + locateType.getValue() + ", pv='" + propertyValue
                    + "', xpath=" + xpath + ", matches=" + matchCount
                    + ", idx=" + selectedIndex + "}";
        }
    }
}
