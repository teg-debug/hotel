package com.hotel.ai;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 包装 {@link ToolCallback}，在真正执行前把工具名记入 {@link ToolInvocationRecorder}。
 *
 * <p>其它方法一律透传（包括 {@code getToolMetadata}，否则会丢掉工具自身的元数据）。</p>
 */
public class RecordingToolCallback implements ToolCallback {

    private final ToolCallback delegate;

    public RecordingToolCallback(ToolCallback delegate) {
        this.delegate = delegate;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        ToolInvocationRecorder.record(delegate.getToolDefinition().name(), null);
        return delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        ToolInvocationRecorder.record(delegate.getToolDefinition().name(), toolContext);
        return delegate.call(toolInput, toolContext);
    }
}
