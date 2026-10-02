package dev.everyagent.worker.interaction;

import dev.everyagent.plugin.api.model.EventEmitter;

public interface EmitterLookup {
    EventEmitter emitterFor(String subjectId);
}
