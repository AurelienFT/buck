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

package com.facebook.buck.parser;

import static com.android.common.SdkConstants.UTF_8;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cli.TestWithBuckd;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.io.watchman.WatchmanFactory;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class WatchmanGlobberPackageBoundaryCheckerTest {

  private ProjectFilesystem projectFilesystem;
  private Watchman watchman;

  @Rule public ExpectedException thrown = ExpectedException.none();
  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  @Rule public TestWithBuckd testWithBuckd = new TestWithBuckd(tmp); // set up Watchman

  @Before
  public void setUp() throws Exception {
    projectFilesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    WatchmanFactory watchmanFactory = new WatchmanFactory();
    watchman =
        watchmanFactory.build(
            ImmutableSet.of(tmp.getRoot()),
            ImmutableMap.of(),
            new TestConsole(),
            FakeClock.doNotCare(),
            Optional.empty());
    assumeTrue(watchman.getTransportPath().isPresent());
  }

  @Test
  public void testEnforceFailsWhenPathReferencesParentDirectory() throws IOException {
    PackageBoundaryChecker boundaryChecker = new WatchmanGlobberPackageBoundaryChecker(watchman);
    AbsPath a = tmp.getRoot().resolve("a");
    Files.createDirectories(a.getPath());
    touch(tmp.getRoot().resolve("a/Test.java"));

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        Matchers.matchesRegex("'..[/\\\\]Test.java' in '//a/b:c' refers to a parent directory."));

    boundaryChecker.enforceBuckPackageBoundaries(
        new TestCellBuilder().setFilesystem(projectFilesystem).build().getRootCell(),
        BuildTargetFactory.newInstance("//a/b:c"),
        ImmutableSet.of(ForwardRelativePath.of("a/Test.java")));
  }

  @Test
  public void testEnforceSkippedWhenNotConfigured() throws IOException {
    PackageBoundaryChecker boundaryChecker = new WatchmanGlobberPackageBoundaryChecker(watchman);
    AbsPath a = tmp.getRoot().resolve("a");
    Files.createDirectories(a.getPath());
    touch(tmp.getRoot().resolve("a/Test.java"));

    boundaryChecker.enforceBuckPackageBoundaries(
        new TestCellBuilder()
            .setFilesystem(projectFilesystem)
            .setBuckConfig(
                FakeBuckConfig.builder()
                    .setSections("[project]", "check_package_boundary = false")
                    .build())
            .build()
            .getRootCell(),
        BuildTargetFactory.newInstance("//a/b:c"),
        ImmutableSet.of(ForwardRelativePath.of("a/Test.java")));
  }

  @Test
  public void testEnforceFailsWhenPathDoNotBelongsToAPackage() throws IOException {
    PackageBoundaryChecker boundaryChecker = new WatchmanGlobberPackageBoundaryChecker(watchman);
    AbsPath a = tmp.getRoot().resolve("a/b");
    Files.createDirectories(a.getPath());
    touch(tmp.getRoot().resolve("a/b/Test.java"));

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "Target '//a/b:c' refers to file 'a/b/Test.java', which doesn't belong to any package."
            + " More info at:\nhttps://buck.build/about/overview.html\n");
    boundaryChecker.enforceBuckPackageBoundaries(
        new TestCellBuilder().setFilesystem(projectFilesystem).build().getRootCell(),
        BuildTargetFactory.newInstance("//a/b:c"),
        ImmutableSet.of(ForwardRelativePath.of("a/b/Test.java")));
  }

  @Test
  public void testEnforceFailsWhenAncestorNotEqualsToBasePath() throws IOException {
    PackageBoundaryChecker boundaryChecker = new WatchmanGlobberPackageBoundaryChecker(watchman);
    AbsPath a = tmp.getRoot().resolve("a/b");
    Files.createDirectories(a.getPath());
    touch(tmp.getRoot().resolve("a/b/Test.java"));
    touch(tmp.getRoot().resolve("a/b/BUCK"));
    touch(tmp.getRoot().resolve("a/BUCK"));

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        Matchers.matchesRegex(
            "The target '//a:c' tried to reference 'a[/\\\\]b[/\\\\]Test.java'.\n"
                + "This is not allowed because 'a[/\\\\]b[/\\\\]Test.java' "
                + "can only be referenced from 'a[/\\\\]b[/\\\\]BUCK' \n"
                + "which is its closest parent 'BUCK' file.\n\n"
                + "You should find or create a rule in 'a[/\\\\]b[/\\\\]BUCK' that references\n'"
                + "a[/\\\\]b[/\\\\]Test.java' and use that in '//a:c'\n"
                + "instead of directly referencing 'a[/\\\\]b[/\\\\]Test.java'.\n"
                + "More info at:\n"
                + "https://buck.build/concept/build_rule.html\n\n"
                + "This issue might also be caused by a bug in buckd's caching.\n"
                + "Please check whether using `buck kill` resolves it."));

    boundaryChecker.enforceBuckPackageBoundaries(
        new TestCellBuilder().setFilesystem(projectFilesystem).build().getRootCell(),
        BuildTargetFactory.newInstance("//a:c"),
        ImmutableSet.of(ForwardRelativePath.of("a/b/Test.java")));
  }

  @Test
  public void testEnforceSucceed() throws IOException {
    PackageBoundaryChecker boundaryChecker = new WatchmanGlobberPackageBoundaryChecker(watchman);
    AbsPath a = tmp.getRoot().resolve("a/b");
    Files.createDirectories(a.getPath());
    touch(tmp.getRoot().resolve("a/b/Test.java"));
    touch(tmp.getRoot().resolve("a/b/BUCK"));

    boundaryChecker.enforceBuckPackageBoundaries(
        new TestCellBuilder().setFilesystem(projectFilesystem).build().getRootCell(),
        BuildTargetFactory.newInstance("//a/b:c"),
        ImmutableSet.of(ForwardRelativePath.of("a/b/Test.java")));
  }

  @Test
  public void testEnforceSucceedWithFolder() throws IOException {
    PackageBoundaryChecker boundaryChecker = new WatchmanGlobberPackageBoundaryChecker(watchman);
    AbsPath a = tmp.getRoot().resolve("a/b");
    Files.createDirectories(a.getPath());
    touch(tmp.getRoot().resolve("a/b/BUCK"));

    boundaryChecker.enforceBuckPackageBoundaries(
        new TestCellBuilder().setFilesystem(projectFilesystem).build().getRootCell(),
        BuildTargetFactory.newInstance("//a/b:c"),
        ImmutableSet.of(ForwardRelativePath.of("a/b")));
  }

  @Test
  public void testEnforceSucceedWithFolderAndFile() throws IOException {
    PackageBoundaryChecker boundaryChecker = new WatchmanGlobberPackageBoundaryChecker(watchman);
    AbsPath a = tmp.getRoot().resolve("a/b");
    Files.createDirectories(a.getPath());
    touch(tmp.getRoot().resolve("a/b/BUCK"));
    touch(tmp.getRoot().resolve("a/b/Test.java"));

    boundaryChecker.enforceBuckPackageBoundaries(
        new TestCellBuilder().setFilesystem(projectFilesystem).build().getRootCell(),
        BuildTargetFactory.newInstance("//a/b:c"),
        ImmutableSet.of(ForwardRelativePath.of("a/b"), ForwardRelativePath.of("a/b/Test.java")));
  }

  @Test
  public void testEnforceSucceedWithMultipleLevelFolders() throws IOException {
    PackageBoundaryChecker boundaryChecker = new WatchmanGlobberPackageBoundaryChecker(watchman);
    AbsPath a = tmp.getRoot().resolve("a/b/c/d");
    Files.createDirectories(a.getPath());
    touch(tmp.getRoot().resolve("a/b/BUCK"));
    touch(tmp.getRoot().resolve("a/b/c/d/Test.java"));

    boundaryChecker.enforceBuckPackageBoundaries(
        new TestCellBuilder().setFilesystem(projectFilesystem).build().getRootCell(),
        BuildTargetFactory.newInstance("//a/b:c"),
        ImmutableSet.of(
            ForwardRelativePath.of("a/b"),
            ForwardRelativePath.of("a/b/c"),
            ForwardRelativePath.of("a/b/c/d"),
            ForwardRelativePath.of("a/b/c/d/Test.java")));
  }

  private void touch(AbsPath path) throws IOException {
    Files.write(path.getPath(), "".getBytes(UTF_8));
  }
}