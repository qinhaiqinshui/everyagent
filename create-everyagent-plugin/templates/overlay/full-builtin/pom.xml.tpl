<?xml version="1.0" encoding="UTF-8"?>
<!-- ea: Maven 构建（builtin 仓库内形态：parent=every-agent-parent；插件不进根 reactor，须 mvn -f 单独构建） -->
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <!-- 仓库内形态：继承根 every-agent-parent（编码 / Java 25 / 依赖管理均随 parent，无须自行声明）。
         注意：本文件是 overlay/java-builtin/pom.xml.tpl 的逐份拷贝（kind=full 与 kind=java 的 Maven 构建完全相同），
         并与 overlay/full-standalone 版本保持同步 —— build 与 dependencies 两段公共内容一致，
         差异只在 parent 段（standalone 无 parent，自带属性与显式版本）。
         改公共段落时四份一起改：java-builtin / java-standalone / full-builtin / full-standalone。 -->
    <parent>
        <groupId>dev.everyagent</groupId>
        <artifactId>every-agent-parent</artifactId>
        <version>1.0.0</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>
    <version>{{version}}</version>

    <artifactId>{{pluginId}}</artifactId>
    <name>{{pluginName}}</name>
    <description>{{description}}</description>

    <build>
        <plugins>
            <!-- 将插件根目录的 plugin.json 复制到 classpath 根目录（target/classes），供内置插件扫描器发现
                 （照抄 every-agent-plugins/git/pom.xml 的真实写法；版本由 spring-boot-starter-parent 4.1.1 管理） -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-resources-plugin</artifactId>
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
        <!-- 插件 API（编译期纯接口；版本显式 1.0.0，与根 parent 的 plugin-api 模块同版本；
             不能写 ${project.version}：本工程默认版本是 0.1.0（新插件起点），而 plugin-api 是 1.0.0） -->
        <dependency>
            <groupId>dev.everyagent</groupId>
            <artifactId>every-agent-plugin-api</artifactId>
            <version>1.0.0</version>
        </dependency>
        <!-- 测试：JUnit 5（经 spring-boot-starter-test 传递）。红线 §14.9：不得依赖 every-agent-worker -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
