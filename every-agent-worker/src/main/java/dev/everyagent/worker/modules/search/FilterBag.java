package dev.everyagent.worker.modules.search;

import java.util.Map;

/**
 * provider 侧读取统一 search「不透明过滤袋」的小工具:按 {@code ${kind}.${field}} 命名空间
 * 取本 provider 自己声明的字段值。核心只透传整个袋、绝不解释 key/value;解释(字符串/布尔)
 * 在此由各 provider 自行完成。
 */
final class FilterBag {

    private FilterBag() {
    }

    /** 取 {@code kind.field} 原始值(缺省 null)。 */
    static Object raw(Map<String, Object> filters, String kind, String field) {
        return filters == null ? null : filters.get(kind + "." + field);
    }

    /** 取 {@code kind.field} 字符串值(缺省 null)。 */
    static String str(Map<String, Object> filters, String kind, String field) {
        Object v = raw(filters, kind, field);
        return v == null ? null : String.valueOf(v);
    }

    /** 取 {@code kind.field} 布尔值(兼容 JSON 布尔与字符串 "true";缺省 false)。 */
    static boolean bool(Map<String, Object> filters, String kind, String field) {
        Object v = raw(filters, kind, field);
        if (v instanceof Boolean b) {
            return b;
        }
        return v != null && "true".equalsIgnoreCase(String.valueOf(v).trim());
    }
}