package com.example.sqlagent.error;

/**
 * 全局错误码。
 *
 * <p>每个错误码同时定义 HTTP 状态、稳定业务编码和默认中文提示。
 * 前端应优先使用 errorCode 做逻辑判断，message 只用于展示，
 * 不应依赖会变化的中文文案。</p>
 */
public enum ErrorCode {
    REQUEST_VALIDATION_FAILED(400, "请求参数不合法"),
    INVALID_CREDENTIALS(401, "用户名或密码错误"),
    NOT_LOGGED_IN(401, "未登录或 token 已失效，请重新登录"),
    ACCESS_DENIED(403, "无权执行此操作"),
    USER_NOT_FOUND(404, "用户不存在"),
    SESSION_NOT_FOUND(404, "会话不存在"),
    DATA_SOURCE_NOT_FOUND(404, "数据源配置不存在"),
    ATTACHMENT_NOT_FOUND(404, "附件不存在"),
    SESSION_ACCESS_DENIED(403, "无权操作此会话"),
    DATA_SOURCE_ACCESS_DENIED(403, "无权使用此数据源"),
    ATTACHMENT_ACCESS_DENIED(403, "无权访问该附件"),
    USERNAME_ALREADY_EXISTS(409, "用户名已存在"),
    NO_RESULT_CONSENSUS(409, "候选 SQL 返回了互相冲突的结果"),
    ATTACHMENT_ALREADY_USED(409, "附件已经发送，不能重复使用"),
    ATTACHMENT_INVALID(422, "附件内容不合法"),
    ATTACHMENT_TOO_LARGE(413, "附件超过大小限制"),
    ATTACHMENT_UNSUPPORTED(415, "不支持该附件格式"),
    READ_ONLY_QUERY_ONLY(422, "当前只允许执行只读 SELECT 查询"),
    SQL_VALIDATION_FAILED(422, "SQL 未通过安全校验"),
    NO_SAFE_CANDIDATE(422, "没有生成通过安全校验的 SQL"),
    CONNECTION_TEST_FAILED(422, "数据库连接测试失败"),
    QUERY_EXECUTION_FAILED(422, "SQL 执行失败"),
    UNSUPPORTED_DATABASE_TYPE(400, "不支持的数据库类型"),
    DEEPSEEK_UNAVAILABLE(502, "DeepSeek 服务暂时不可用"),
    KNOWLEDGE_BASE_UNAVAILABLE(502, "知识库服务暂时不可用"),
    UPSTREAM_SERVICE_UNAVAILABLE(502, "外部服务暂时不可用"),
    ATTACHMENT_STORAGE_FAILED(500, "附件存储失败"),
    DATABASE_ACCESS_FAILED(500, "数据库访问失败"),
    INTERNAL_SERVER_ERROR(500, "服务器内部错误");

    private final int httpStatus;
    private final String defaultMessage;

    ErrorCode(int httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
