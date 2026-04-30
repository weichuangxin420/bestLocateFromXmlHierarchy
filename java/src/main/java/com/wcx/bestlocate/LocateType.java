package com.pdd.bestlocate;

/**
 * Supported locator categories inferred from Android UIAutomator XML.
 */
public enum LocateType {
    RESOURCE_ID("resource-id", "resource_id"),
    CONTENT_DESC("content-desc", "content_desc"),
    TEXT("text", "text"),
    CLASS("class", "xpath"),
    XPATH("xpath", "xpath"),
    DESCRIPTION("description", "content_desc");

    private final String value;
    private final String resultType;

    LocateType(String value, String resultType) {
        this.value = value;
        this.resultType = resultType;
    }

    /**
     * External locator category name.
     */
    public String getValue() {
        return value;
    }

    /**
     * Backward-compatible LocatorResult.type value.
     */
    public String getResultType() {
        return resultType;
    }
}
