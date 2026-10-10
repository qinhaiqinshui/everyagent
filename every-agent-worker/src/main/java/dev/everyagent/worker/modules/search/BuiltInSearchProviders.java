package dev.everyagent.worker.modules.search;

import dev.everyagent.worker.plugin.registry.SearchProviderRegistry;
import org.springframework.stereotype.Component;

/**
 * 内置搜索引擎启动装配:把内置 file-content / file-name / task 三个
 * {@link dev.everyagent.plugin.api.spi.SearchProvider} 注册进 {@link SearchProviderRegistry}。
 *
 * <p>内置 provider 的 {@code order()} 取很小的负值(见各 provider 的 {@code ORDER}),
 * 排序上<b>先于</b>插件 provider(缺省 order 0),即内置结果在前、插件结果追加在后。
 * 插件 provider 随插件生命周期进退场,内置恒在注册表中。
 */
@Component
public class BuiltInSearchProviders {

    public BuiltInSearchProviders(SearchProviderRegistry registry,
            FileContentSearchProvider fileContent,
            FileNameSearchProvider fileName,
            TaskSearchProvider task) {
        registry.register(fileContent);
        registry.register(fileName);
        registry.register(task);
    }
}