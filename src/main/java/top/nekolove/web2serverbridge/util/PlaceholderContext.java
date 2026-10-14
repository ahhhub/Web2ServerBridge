package top.nekolove.web2serverbridge.util;

/**
 * 占位符解析上下文。
 *
 * <p>把"占位符解析"与 Bukkit / PlaceholderAPI 解耦：
 * {@code Messages} 只依赖本接口，因此可以在没有 MC 服务端的环境（自检、单元测试）中运行。</p>
 *
 * <p>两种实现：</p>
 * <ul>
 *   <li>{@link #NONE}：无上下文，{@code %xxx%} 原样保留。</li>
 *   <li>{@link PapiContext}：服务端环境中把 {@code %xxx%} 交给 PlaceholderAPI 解析。</li>
 * </ul>
 */
public interface PlaceholderContext {

    /** 解析文本中的占位符；实现应保证不抛异常（失败时返回原文） */
    String resolve(String text);

    /** 空上下文：不做任何额外解析 */
    PlaceholderContext NONE = text -> text;

    /** 包装一个解析函数 */
    static PlaceholderContext of(java.util.function.UnaryOperator<String> resolver) {
        if (resolver == null) {
            return NONE;
        }
        return text -> {
            try {
                String out = resolver.apply(text);
                return out == null ? text : out;
            } catch (Throwable ignored) {
                return text;
            }
        };
    }
}
