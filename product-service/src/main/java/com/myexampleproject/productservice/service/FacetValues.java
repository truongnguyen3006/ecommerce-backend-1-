package com.myexampleproject.productservice.service;

import java.util.Locale;

/** Preserve semantic spelling/accents/interior whitespace; ignore outer ASCII spaces and case for matching. */
public final class FacetValues {
    private FacetValues() {}
    public static String label(String value) {
        if(value==null) return null;
        String label=value.replaceAll("^ +| +$", "");
        return label.isEmpty()?null:label;
    }
    public static String key(String value) {String label=label(value);return label==null?null:label.toLowerCase(Locale.ROOT);}
}
