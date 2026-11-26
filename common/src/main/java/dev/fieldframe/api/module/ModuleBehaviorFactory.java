package dev.fieldframe.api.module;

/** Creates a fresh behavior for each installed module instance. */
@FunctionalInterface
public interface ModuleBehaviorFactory {
    ModuleBehavior create(ModuleDefinition definition);
}
