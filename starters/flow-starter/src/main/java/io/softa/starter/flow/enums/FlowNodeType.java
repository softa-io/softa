package io.softa.starter.flow.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.softa.framework.base.annotation.OptionItem;
import io.softa.framework.base.annotation.OptionSet;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * All node types supported by the flow graph.
 * <p>
 * Serves as the single discriminator for both front-end palette rendering and
 * back-end handler/executor routing. Replaces the old two-key (FlowNodeKind + executor)
 * model with a flat, typed enum.
 *
 * <ul>
 *   <li><b>Control</b>: Start, End, Timer</li>
 *   <li><b>Routing</b>: InclusiveGateway, ParallelFork, ParallelJoin</li>
 *   <li><b>Human</b>: Approval, HumanTask</li>
 *   <li><b>Task</b>: Script, CreateRecord, GetRecord, UpdateRecord, DeleteRecord,
 *       QueryRecords, ValidateData, Transform, CallService, CallWebhook,
 *       SendEmail, SendSms, SendInboxNotification, QueryAI, AsyncTask, GenerateFile</li>
 *   <li><b>Subflow</b>: Subflow</li>
 *   <li><b>Data</b>: ForEach, ReturnValue</li>
 * </ul>
 */
@Getter
@AllArgsConstructor
@OptionSet
public enum FlowNodeType {

    // ── 控制流
    START("Start", FlowNodeCategory.CONTROL),
    END("End", FlowNodeCategory.CONTROL),
    /** 定时等待节点：挂起实例直到指定时长或时刻，由调度器唤醒 */
    TIMER("Timer", FlowNodeCategory.CONTROL),

    // ── 路由
    /** OR 网关：从所有出口边中选择所有满足条件的边（N≥1） */
    INCLUSIVE_GATEWAY("InclusiveGateway", FlowNodeCategory.ROUTING),
    /** 并行分叉：将执行拆分为 N 条并行分支 */
    PARALLEL_FORK("ParallelFork", FlowNodeCategory.ROUTING),
    /** 并行汇聚：等待所有入口分支到达后继续 */
    PARALLEL_JOIN("ParallelJoin", FlowNodeCategory.ROUTING),

    // ── 人工交互
    /** 多方投票审批节点（拒绝 / 回退 / 加签 / 转交 / 委托 / 催办…）*/
    APPROVAL("Approval", FlowNodeCategory.HUMAN),
    /** 人工确认 / 填表节点（无投票语义，等待人工提交后继续）*/
    HUMAN_TASK("HumanTask", FlowNodeCategory.HUMAN),

    // ── 自动化任务（统一由 TaskExecutor SPI 实现）
    /** AviatorScript 表达式 / 脚本，替代旧的 ComputeTask */
    SCRIPT("Script", FlowNodeCategory.TASK),
    CREATE_RECORD("CreateRecord", FlowNodeCategory.TASK),
    GET_RECORD("GetRecord", FlowNodeCategory.TASK),
    UPDATE_RECORD("UpdateRecord", FlowNodeCategory.TASK),
    DELETE_RECORD("DeleteRecord", FlowNodeCategory.TASK),
    QUERY_RECORDS("QueryRecords", FlowNodeCategory.TASK),
    VALIDATE_DATA("ValidateData", FlowNodeCategory.TASK),
    /** 数据提取 / 映射 / ETL */
    TRANSFORM("Transform", FlowNodeCategory.TASK),
    /** 调用内部 Spring Bean 方法 */
    CALL_SERVICE("CallService", FlowNodeCategory.TASK),
    /** 调用外部 HTTP Webhook */
    CALL_WEBHOOK("CallWebhook", FlowNodeCategory.TASK),
    /** 发送邮件通知 */
    SEND_EMAIL("SendEmail", FlowNodeCategory.TASK),
    /** 发送短信通知 */
    @OptionItem(label = "Send SMS")
    SEND_SMS("SendSms", FlowNodeCategory.TASK),
    /** 发送站内通知 */
    SEND_INBOX_NOTIFICATION("SendInboxNotification", FlowNodeCategory.TASK),
    @OptionItem(label = "Query AI")
    QUERY_AI("QueryAI", FlowNodeCategory.TASK),
    /** Pulsar 异步任务（fire-and-forget）；节点执行后实例挂起等待回调 */
    ASYNC_TASK("AsyncTask", FlowNodeCategory.TASK),
    /** 生成文档 / 报表（替代旧的 GenerateReport，名称更通用）*/
    GENERATE_FILE("GenerateFile", FlowNodeCategory.TASK),

    // ── 子流程
    SUBFLOW("Subflow", FlowNodeCategory.SUBFLOW),

    // ── 数据
    /** 遍历数据集合，对每个 item 执行子节点（替代旧的 Loop）*/
    FOR_EACH("ForEach", FlowNodeCategory.DATA),
    /** 从流程返回命名值（替代旧的 ReturnData）*/
    RETURN_VALUE("ReturnValue", FlowNodeCategory.DATA),
    ;

    /** JSON / 前端 palette 使用的字符串键 */
    @JsonValue
    private final String type;

    /** 所属 palette 分组 */
    private final FlowNodeCategory category;

    @JsonCreator
    public static FlowNodeType fromValue(String value) {
        for (FlowNodeType nt : values()) {
            if (nt.type.equalsIgnoreCase(value) || nt.name().equalsIgnoreCase(value)) {
                return nt;
            }
        }
        throw new IllegalArgumentException("Unsupported node type: " + value);
    }
}
