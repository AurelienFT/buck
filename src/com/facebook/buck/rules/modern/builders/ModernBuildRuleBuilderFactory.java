/*
 * Copyright 2018-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.rules.modern.builders;

import com.facebook.buck.core.build.engine.BuildStrategyContext;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.build.strategy.BuildRuleStrategy;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.log.TraceInfoProvider;
import com.facebook.buck.remoteexecution.config.RemoteExecutionConfig;
import com.facebook.buck.remoteexecution.factory.RemoteExecutionClientsFactory;
import com.facebook.buck.rules.modern.config.ModernBuildRuleConfig;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.util.hashing.FileHashLoader;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.util.Optional;

/**
 * Constructs various BuildRuleStrategies for ModernBuildRules based on the
 * modern_build_rule.strategy config option.
 */
public class ModernBuildRuleBuilderFactory {
  /** Creates a BuildRuleStrategy for ModernBuildRules based on the buck configuration. */
  public static Optional<BuildRuleStrategy> getBuildStrategy(
      ModernBuildRuleConfig config,
      RemoteExecutionConfig remoteExecutionConfig,
      BuildRuleResolver resolver,
      Cell rootCell,
      CellPathResolver cellResolver,
      FileHashLoader hashLoader,
      BuckEventBus eventBus,
      ListeningExecutorService remoteExecutorService,
      Optional<TraceInfoProvider> traceInfoProvider) {
    try {
      RemoteExecutionClientsFactory remoteExecutionFactory =
          new RemoteExecutionClientsFactory(remoteExecutionConfig);
      switch (config.getBuildStrategy()) {
        case NONE:
          return Optional.empty();

        case DEBUG_RECONSTRUCT:
          return Optional.of(
              createReconstructing(new SourcePathRuleFinder(resolver), cellResolver, rootCell));
        case DEBUG_PASSTHROUGH:
          return Optional.of(createPassthrough());
        case REMOTE:
        case GRPC_REMOTE:
        case DEBUG_GRPC_SERVICE_IN_PROCESS:
        case DEBUG_ISOLATED_OUT_OF_PROCESS_GRPC:
          return Optional.of(
              RemoteExecutionStrategy.createRemoteExecutionStrategy(
                  eventBus,
                  remoteExecutionConfig.useWorkerThreadPool()
                      ? Optional.of(remoteExecutorService)
                      : Optional.empty(),
                  remoteExecutionFactory.create(eventBus, traceInfoProvider),
                  new SourcePathRuleFinder(resolver),
                  cellResolver,
                  rootCell,
                  hashLoader::get));
      }
    } catch (IOException e) {
      throw new BuckUncheckedExecutionException(e, "When creating MBR build strategy.");
    }
    throw new IllegalStateException(
        "Unrecognized build strategy " + config.getBuildStrategy() + ".");
  }

  /** The passthrough strategy just forwards to executorRunner.runWithDefaultExecutor. */
  public static BuildRuleStrategy createPassthrough() {
    return new AbstractModernBuildRuleStrategy() {
      @Override
      public StrategyBuildResult build(BuildRule rule, BuildStrategyContext strategyContext) {
        return StrategyBuildResult.nonCancellable(
            Futures.submitAsync(
                strategyContext::runWithDefaultBehavior, strategyContext.getExecutorService()));
      }
    };
  }

  /**
   * The reconstructing strategy serializes and deserializes the build rule in memory and builds the
   * deserialized version.
   */
  public static BuildRuleStrategy createReconstructing(
      SourcePathRuleFinder ruleFinder, CellPathResolver cellResolver, Cell rootCell) {
    return new ReconstructingStrategy(ruleFinder, cellResolver, rootCell);
  }
}
