package com.wcx.bestlocate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parsed hierarchy node. */
public class HierarchyNode {
    private String key;
    private String name;
    private Map<String, String> properties = new LinkedHashMap<>();
    private List<Double> bounds; // [x1, y1, x2, y2] 归一化的相对坐标
    private HierarchyRect rect;  // {x, y, width, height} 像素层面的绝对坐标，单位pixel
    private List<HierarchyNode> children = new ArrayList<>();//子节点

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
