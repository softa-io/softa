package io.softa.starter.flow.enums;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
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
 * model with a flat, typed enum. Each type also declares the {@link FlowScenario}s whose
 * flows may use it — the palette and the compiler both read {@link #getScenarios()}.
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
    START("Start", FlowNodeCategory.CONTROL, Scenarios.ALL),
    END("End", FlowNodeCategory.CONTROL, Scenarios.ALL),
    /** 定时等待节点：挂起实例直到指定时长或时刻，由调度器唤醒 */
    TIMER("Timer", FlowNodeCategory.CONTROL, Scenarios.STATEFUL),

    // ── 路由
    /** OR 网关：从所有出口边中选择所有满足条件的边（N≥1） */
    INCLUSIVE_GATEWAY("InclusiveGateway", FlowNodeCategory.ROUTING, Scenarios.STATEFUL),
    /** 并行分叉：将执行拆分为 N 条并行分支 */
    PARALLEL_FORK("ParallelFork", FlowNodeCategory.ROUTING, Scenarios.STATEFUL),
    /** 并行汇聚：等待所有入口分支到达后继续 */
    PARALLEL_JOIN("ParallelJoin", FlowNodeCategory.ROUTING, Scenarios.STATEFUL),

    // ── 人工交互
    /** 多方投票审批节点（拒绝 / 回退 / 转交 / 委托 / 催办…）*/
    APPROVAL("Approval", FlowNodeCategory.HUMAN, Scenarios.APPROVAL_ONLY),
    /** 人工确认 / 填表节点（无投票语义，等待人工提交后继续）*/
    HUMAN_TASK("HumanTask", FlowNodeCategory.HUMAN, Scenarios.APPROVAL_ONLY),

    // ── 自动化任务（统一由 TaskExecutor SPI 实现）
    /** AviatorScript 表达式 / 脚本，替代旧的 ComputeTask */
    SCRIPT("Script", FlowNodeCategory.TASK, Scenarios.ALL),
    CREATE_RECORD("CreateRecord", FlowNodeCategory.TASK, Scenarios.ALL),
    GET_RECORD("GetRecord", FlowNodeCategory.TASK, Scenarios.ALL),
    UPDATE_RECORD("UpdateRecord", FlowNodeCategory.TASK, Scenarios.ALL),
    DELETE_RECORD("DeleteRecord", FlowNodeCategory.TASK, Scenarios.ALL),
    QUERY_RECORDS("QueryRecords", FlowNodeCategory.TASK, Scenarios.ALL),
    VALIDATE_DATA("ValidateData", FlowNodeCategory.TASK, Scenarios.STATEFUL),
    /** 数据提取 / 映射 / ETL */
    TRANSFORM("Transform", FlowNodeCategory.TASK, Scenarios.STATEFUL),
    /** 调用内部 Spring Bean 方法 */
    CALL_SERVICE("CallService", FlowNodeCategory.TASK, Scenarios.ALL),
    /** 调用外部 HTTP Webhook */
    CALL_WEBHOOK("CallWebhook", FlowNodeCategory.TASK, Scenarios.ALL),
    /** 发送邮件通知 */
    SEND_EMAIL("SendEmail", FlowNodeCategory.TASK, Scenarios.STATEFUL),
    /** 发送短信通知 */
    @OptionItem(label = "Send SMS")
    SEND_SMS("SendSms", FlowNodeCategory.TASK, Scenarios.STATEFUL),
    /** 发送站内通知 */
    SEND_INBOX_NOTIFICATION("SendInboxNotification", FlowNodeCategory.TASK, Scenarios.STATEFUL),
    @OptionItem(label = "Query AI")
    QUERY_AI("QueryAI", FlowNodeCategory.TASK, Scenarios.STATEFUL),
    /** Pulsar 异步任务（fire-and-forget）；节点执行后实例挂起等待回调 */
    ASYNC_TASK("AsyncTask", FlowNodeCategory.TASK, Scenarios.STATEFUL),
    /** 生成文档 / 报表（替代旧的 GenerateReport，名称更通用）*/
    GENERATE_FILE("GenerateFile", FlowNodeCategory.TASK, Scenarios.STATEFUL),

    // ── 子流程
    SUBFLOW("Subflow", FlowNodeCategory.SUBFLOW, Scenarios.STATEFUL),

    // ── 数据
    /** 遍历数据集合，对每个 item 执行子节点（替代旧的 Loop）*/
    FOR_EACH("ForEach", FlowNodeCategory.DATA, Scenarios.STATEFUL),
    /** 从流程返回命名值（替代旧的 ReturnData）*/
    RETURN_VALUE("ReturnValue", FlowNodeCategory.DATA, Scenarios.ALL),
    ;

    /** JSON / 前端 palette 使用的字符串键 */
    @JsonValue
    private final String type;

    /** 所属 palette 分组 */
    private final FlowNodeCategory category;

    /** 允许使用该节点的场景：节点面板和编译器都以此为准 */
    private final Set<FlowScenario> scenarios;

    /** Scenario sets the constants share — a holder, since a constant cannot refer to its own enum's statics. */
    private static final class Scenarios {
        static final Set<FlowScenario> ALL = Collections.unmodifiableSet(EnumSet.allOf(FlowScenario.class));
        static final Set<FlowScenario> STATEFUL =
                Collections.unmodifiableSet(EnumSet.of(FlowScenario.APPROVAL, FlowScenario.AUTOMATION));
        static final Set<FlowScenario> APPROVAL_ONLY = Collections.unmodifiableSet(EnumSet.of(FlowScenario.APPROVAL));
    }
}
