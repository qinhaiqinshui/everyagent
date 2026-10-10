// ea: JUnit5 冒烟测试：只依赖 every-agent-plugin-api，不依赖 worker（§14.9 红线）
package {{package}};

import dev.everyagent.plugin.api.EveryAgentPlugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {{entryClass}} 冒烟测试。
 *
 * <p>§14.9 红线：插件测试<b>不得依赖 every-agent-worker</b> —— 本测试只依赖
 * every-agent-plugin-api（编译期接口）与 spring-boot-starter-test 传递的 JUnit 5，
 * 不启动 worker、不需要 Spring 上下文；activate() 依赖运行期 WorkerPluginContext，留待真机验证。
 */
class {{entryClass}}SmokeTest {

    @Test
    void entryClassInstantiableAndIdMatchesManifest() {
        EveryAgentPlugin plugin = new {{entryClass}}();
        assertEquals("{{pluginId}}", plugin.id(), "id() 必须与 plugin.json 的 id 字段一致");
    }

    @Test
    void idMatchesScaffoldNamingRule() {
        // 与脚手架 create-everyagent-plugin 同一条校验规则：^[a-z0-9][a-z0-9-]{1,38}$
        assertTrue("{{pluginId}}".matches("[a-z0-9][a-z0-9-]{1,38}"),
                "id 只允许小写字母/数字/连字符，总长 2~39，首尾须为字母或数字");
    }
}
