package dev.everyagent.worker.task;

import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.model.ModelRequestContext;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * {@link ModelRequestContext} 默认实现：收集各节点注册的回调，
 * 内核通过 {@code invokeOnChunk} / {@code invokeOnComplete} / {@code invokeOnError} 逐个调用。
 *
 * <p>使用 {@link CopyOnWriteArrayList} 保护回调列表，确保流式 {@code doOnNext} 回调线程安全。
 */
class ModelRequestContextImpl implements ModelRequestContext {

    private final ModelConfig config;
    private final EventEmitter emitter;
    private final List<Consumer<String>> chunkCallbacks = new CopyOnWriteArrayList<>();
    private final List<LongConsumer> completeCallbacks = new CopyOnWriteArrayList<>();
    private final List<Consumer<Throwable>> errorCallbacks = new CopyOnWriteArrayList<>();

    ModelRequestContextImpl(ModelConfig config, EventEmitter emitter) {
        this.config = config;
        this.emitter = emitter;
    }

    @Override
    public ModelConfig config() {
        return config;
    }

    @Override
    public EventEmitter events() {
        return emitter;
    }

    @Override
    public void onChunk(Consumer<String> callback) {
        chunkCallbacks.add(callback);
    }

    @Override
    public void onComplete(LongConsumer callback) {
        completeCallbacks.add(callback);
    }

    @Override
    public void onError(Consumer<Throwable> callback) {
        errorCallbacks.add(callback);
    }

    void invokeOnChunk(String text) {
        for (Consumer<String> cb : chunkCallbacks) {
            cb.accept(text);
        }
    }

    void invokeOnComplete(long tokens) {
        for (LongConsumer cb : completeCallbacks) {
            cb.accept(tokens);
        }
    }

    void invokeOnError(Throwable e) {
        for (Consumer<Throwable> cb : errorCallbacks) {
            cb.accept(e);
        }
    }
}
