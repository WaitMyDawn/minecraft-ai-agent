package yagen.waitmydawn.maa.service;

import tools.jackson.databind.ObjectMapper;

/** 标签内 JSON 转义定界字符，避免原文或项目摘录被前端误识别为清单协议；JSON 解码后字段保持原值。 */
public final class MessageJson {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private MessageJson() {}
    public static String write(Object value) { return escape(MAPPER.writeValueAsString(value)); }
    public static String escape(String json) { return json.replace("<", "\\u003c").replace(">", "\\u003e"); }
}
