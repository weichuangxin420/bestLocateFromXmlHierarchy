package com.wcx.bestlocate;

/**
 * 从 Android UIAutomator XML 中可推断出的定位类型。
 *
 * <p>value 表示 XML / UiSelector 语义下的定位分类名，
 * resultType 表示对外兼容的 {@link LocatorResult#getType()} 返回值。</p>
 */
public enum LocateType {
    /** XML 属性 resource-id，对应 Appium id / UiSelector.resourceId。 */
    RESOURCE_ID("resource-id", "resource_id"),
    /** XML 属性 content-desc，对应 Appium accessibility id。 */
    CONTENT_DESC("content-desc", "content_desc"),
    /** XML 属性 text，对应 UiSelector.text 或 XPath @text。 */
    TEXT("text", "text"),
    /** XML 属性 class，对应 Appium class name；通常不唯一，更多作为兜底候选。 */
    CLASS("class", "xpath"),
    /** 基于 XML 层级构造的 XPath 表达式。 */
    XPATH("xpath", "xpath"),
    /** UiSelector.description 语义，对应 XML 的 content-desc。 */
    DESCRIPTION("description", "content_desc");

    private final String value;
    private final String resultType;

    LocateType(String value, String resultType) {
        this.value = value;
        this.resultType = resultType;
    }

    /**
     * XML / UiSelector 语义下的定位分类名。
     */
    public String getValue() {
        return value;
    }

    /**
     * 对外兼容的 LocatorResult.type 返回值。
     */
    public String getResultType() {
        return resultType;
    }
}
