<?xml version="1.0" encoding="UTF-8"?>
<!-- ea: Maven 构建（standalone 独立形态：无 parent，自带编码/Java 25 属性；API jar 须先 install 到本地库） -->
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <!-- 独立仓库形态：工程在 everyagent 仓库之外，不继承 every-agent-parent。
         注意：与 overlay/java-builtin 版本保持同步 —— build 与 dependencies 两段公共内容一致，
         差异只在坐标/属性段（本版自带 groupId、编码与 Java 版本属性、依赖显式版本）。改公共段落时两份一起改。 -->
    <groupId>dev.everyagent.plugin.{{camelName}}</groupId>
    <artifactId>{{pluginId}}</artifactId>
    <version>{{version}}</version>

    <name>{{pluginName}}</name>
    <description>{{description}}</description>

    <properties>
        <!-- 编码 / Java 版本：builtin 形态随 parent，standalone 形态自己声明（与根 pom 口径一致：Java 25） -->
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <project.reporting.outputEncoding>UTF-8</project.reporting.outputEncoding>
        <maven.compiler.release>25</maven.compiler.release>
        <!-- 与 everyagent 根 pom 锁定的版本保持一致（Spring Boot 4.1.1 / 插件版本取 spring-boot-dependencies 4.1.1 所管理的） -->
        <spring-boot.version>4.1.1</spring-boot.version>
    </properties>

    <build>
        <plugins>
            <!-- 将插件根目录的 plugin.json 复制到 classpath 根目录（target/classes），供插件扫描器发现
                 （照抄 every-agent-plugins/git/pom.xml 的真实写法；无 parent ⇒ 插件版本须显式声明，
                  此处取 spring-boot-dependencies 4.1.1 管理的 3.5.0；compiler 同理取 3.15.0） -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.15.0</version>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-resources-plugin</artifactId>
                <version>3.5.0</version>
                <executions>
                    <execution>
                        <id>copy-plugin-manifest</id>
                        <phase>process-resources</phase>
                        <goals>
                            <goal>copy-resources</goal>
                        </goals>
                        <configuration>
                            <outputDirectory>${project.build.outputDirectory}</outputDirectory>
                            <resources>
                                <resource>
                                    <directory>${project.basedir}</directory>
                                    <includes>
                                        <include>plugin.json</include>
                                    </includes>
                                </resource>
                            </resources>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>

    <dependencies>
        <!-- 插件 API（编译期纯接口）。注意：every-agent-plugin-api 尚未发布到 Maven Central
             （repo1 上 dev/everyagent/ 目录 404）。先在 everyagent 仓库根执行：
                 mvn -pl every-agent-plugin-api -am install -DskipTests
             把 1.0.0 装进本地仓库；等官方发布到 Central 后把下面版本号改成发布号即可。 -->
        <dependency>
            <groupId>dev.everyagent</groupId>
            <artifactId>every-agent-plugin-api</artifactId>
            <version>1.0.0</version>
        </dependency>
        <!-- 测试：JUnit 5（经 spring-boot-starter-test 传递）。红线 §14.9：不得依赖 every-agent-worker -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <version>${spring-boot.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
