package com.pdd.bestlocate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parsed hierarchy node. */
public class HierarchyNode {
    private String key;
    private String name;
    private Map<String, String> properties = new LinkedHashMap<>();
    private List<Double> bounds; // [x1, y1, x2, y2] normalized 0~1
    private HierarchyRect rect;  // {x, y, width, height} in pixels
    private List<HierarchyNode> children = new ArrayList<>();

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Map<String, String> getProperties() { return properties; }
    public void setProperties(Map<String, String> properties) { this.properties = properties; }
    public List<Double> getBounds() { return bounds; }
    public void setBounds(List<Double> bounds) { this.bounds = bounds; }
    public HierarchyRect getRect() { return rect; }
    public void setRect(HierarchyRect rect) { this.rect = rect; }
    public List<HierarchyNode> getChildren() { return children; }
    public void setChildren(List<HierarchyNode> children) { this.children = children; }
}
