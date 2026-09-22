package com.practice.weeklyreportmanager.service.tool;

import org.springframework.ai.tool.execution.ToolCallResultConverter;
import org.springframework.lang.Nullable;

import java.lang.reflect.Type;

/**
 * 工具结果原样返回，不做 JSON 编码。

 * 为什么需要：`@Tool(resultConverter = ...)` 不写的话用默认的 DefaultToolCallResultConverter，
 * 它把结果交给 JsonParser.toJson，而 toJson 只在「字符串本身已经是合法 JSON」时才原样返回：

 *     public static String toJson(@Nullable Object object) {
 *         if (object instanceof String str && isValidJson(str)) {
 *             return str;
 *         }
 *         return OBJECT_MAPPER.writeValueAsString(object);
 *     }

 * 我们两个工具返回的都是中文正文，isValidJson 为 false，于是整体被加引号、换行被转义成字面的 \n——
 * 模型读得懂（它本来就在读 JSON），但 ReportTextFormatter 拼出来的块格式被压成一行，token 也多花。
 * 这里直接返回，让工具结果就是它本来的样子。

 * 不用注册成 Spring Bean：框架按无参构造反射实例化（默认值 DefaultToolCallResultConverter 本身就是个
 * 非 Bean 的普通类，方法见 MethodToolCallback.Builder#toolCallResultConverter 收的是实例）。
 */
public class PlainTextResultConverter implements ToolCallResultConverter {

    @Override
    public String convert(@Nullable Object result, @Nullable Type returnType) {
        return result == null ? "" : result.toString();
    }
}
