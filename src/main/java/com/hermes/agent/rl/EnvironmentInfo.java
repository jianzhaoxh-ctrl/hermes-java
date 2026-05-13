package com.hermes.agent.rl;

import java.nio.file.Path;
import java.util.Map;

/**
 * RL 环境信息。
 *
 * <p>参照 Python 版 rl_training_tool.py 的 EnvironmentInfo。
 * 记录从 Atropos environments 目录扫描到的环境元数据。
 */
public class EnvironmentInfo {

    private final String name;
    private final String className;
    private final String filePath;
    private final String description;
    private final String configClass;

    /** 动态发现的配置字段：fieldName → {type, default, description, locked, current_value} */
    private Map<String, Map<String, Object>> configFields;

    public EnvironmentInfo(String name, String className, String filePath,
                           String description, String configClass) {
        this.name = name;
        this.className = className;
        this.filePath = filePath;
        this.description = description;
        this.configClass = configClass;
    }

    public String getName() { return name; }
    public String getClassName() { return className; }
    public String getFilePath() { return filePath; }
    public String getDescription() { return description; }
    public String getConfigClass() { return configClass; }

    public Map<String, Map<String, Object>> getConfigFields() { return configFields; }
    public void setConfigFields(Map<String, Map<String, Object>> fields) { this.configFields = fields; }

    public Map<String, Object> toMap() {
        return Map.of(
                "name", name,
                "class_name", className,
                "file_path", filePath,
                "description", description != null ? description : "",
                "config_class", configClass != null ? configClass : "BaseEnvConfig"
        );
    }
}
