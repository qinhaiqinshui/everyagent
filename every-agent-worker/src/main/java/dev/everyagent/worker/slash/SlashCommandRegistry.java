package dev.everyagent.worker.slash;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import dev.everyagent.worker.rpc.NotFoundException;

/**
 * `/` 斜杠命令的动态注册中心(worker 侧,对应老项目前端 {@code slashCommandRegistry})。
 *
 * <p>不写死任何命令来源:任何 Spring 组件在构造器/{@code @PostConstruct} 里调用
 * {@link #registerProvider} 即可把自己的命令注入 `/` 菜单(如内置 skill、常用语、
 * 后续插件),删除注册即自动消失。与老项目「id → 异步 loader」语义一致,
 * 只是 loader 从浏览器本地换成 worker 侧同步/异步加载。
 *
 * <p>list() 聚合全部来源条目并按 id 去重(后者覆盖),与老项目行为一致。
 */
@Component
public class SlashCommandRegistry {

    /** provider loader:返回该来源的全部条目。 */
    public interface SlashProvider {
        List<SlashCommandItem> load();
    }

    private final Map<String, SlashProvider> providers = new ConcurrentHashMap<>();

    /** 注册一个来源(同 id 覆盖)。 */
    public void registerProvider(String id, SlashProvider provider) {
        providers.put(id, provider);
    }

    /** 注销一个来源。 */
    public void unregisterProvider(String id) {
        providers.remove(id);
    }

    /** 取全部来源的条目(按 id 去重,后者覆盖)。 */
    public List<SlashCommandItem> list() {
        Map<String, SlashCommandItem> byId = new LinkedHashMap<>();
        for (SlashProvider provider : providers.values()) {
            List<SlashCommandItem> items = provider.load();
            if (items == null) {
                continue;
            }
            for (SlashCommandItem item : items) {
                byId.put(item.id(), item);
            }
        }
        return new ArrayList<>(byId.values());
    }

    /**
     * 按 id 取单条(供 select/cancel 反查):触发全部 provider load 后在聚合结果里找 id 相同者;
     * 找不到抛 {@link NotFoundException}(由 RpcDispatcher 转 NOT_FOUND)。
     */
    public SlashCommandItem itemById(String id) {
        for (SlashCommandItem item : list()) {
            if (item.id().equals(id)) {
                return item;
            }
        }
        throw new NotFoundException("未知 slash 条目: " + id);
    }
}
