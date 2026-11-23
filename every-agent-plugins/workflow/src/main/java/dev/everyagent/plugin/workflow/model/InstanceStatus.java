package dev.everyagent.plugin.workflow.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 实例状态机（与 task 同名）：created → running →（waiting-user 挂起/恢复）→ done | failed | cancelled。
 *
 * <p>worker 重启时 activate 扫描把全部非终态实例标 failed("worker 重启中断")——v1 不做断点续跑（同任务语义）。
 */
public enum InstanceStatus {

    @JsonProperty("created") CREATED,

    @JsonProperty("running") RUNNING,

    @JsonProperty("waiting-user") WAITING_USER,

    @JsonProperty("done") DONE,

    @JsonProperty("failed") FAILED,

    @JsonProperty("cancelled") CANCELLED;

    /** 是否终态（done/failed/cancelled）。 */
    public boolean terminal() {
        return this == DONE || this == FAILED || this == CANCELLED;
    }
}
