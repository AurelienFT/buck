/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.apple;

import static com.facebook.buck.core.util.Optionals.compare;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import java.util.Optional;

@BuckStyleValue
public abstract class AppleBundlePart implements AddsToRuleKey {
  @AddToRuleKey
  public abstract SourcePath getSourcePath();

  @AddToRuleKey
  public abstract AppleBundleDestination getDestination();

  @AddToRuleKey
  public abstract Optional<SourcePath> getContentHashSourcePath();

  protected int compareTo(AppleBundlePart o) {
    if (getSourcePath() != o.getSourcePath()) {
      return getSourcePath().compareTo(o.getSourcePath());
    }
    if (getDestination() != o.getDestination()) {
      return getDestination().compareTo(o.getDestination());
    }
    if (getContentHashSourcePath() != o.getContentHashSourcePath()) {
      return compare(getContentHashSourcePath(), o.getContentHashSourcePath());
    }
    return 0;
  }
}