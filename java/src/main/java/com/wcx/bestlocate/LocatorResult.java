package com.pdd.bestlocate;

/** Final locator result (type + value). */
public class LocatorResult {
    private String type;   // content_desc, resource_id, text, xpath
    private String value;  // actual property value or xpath expression

    public LocatorResult(String type, String value) {
        this.type = type;
        this.value = value;
    }

    public String getType() { return type; }
    public String getValue() { return value; }

    @Override
    public String toString() {
        return "LocatorResult{type='" + type + "', value='" + value + "'}";
    }
}
