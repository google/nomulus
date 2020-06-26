// Copyright 2019 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.testing;

import static com.google.common.truth.Truth.assertWithMessage;
import static google.registry.util.CollectionUtils.entriesToImmutableMap;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import com.google.common.base.Joiner;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.Multimaps;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link google.registry.testing.AppEngineRule}. Tests focus on Datastoe-related
 * assertions made during teardown checks.
 */
@RunWith(JUnit4.class)
public class AppEngineRuleTest {

  // An arbitrary index in google/registry/env/common/default/WEB-INF/datastore-indexes.xml
  private static final String DECLARED_INDEX =
      Joiner.on('\n')
          .join(
              "<datastore-indexes autoGenerate=\"false\">",
              "  <datastore-index kind=\"ContactResource\" ancestor=\"false\" source=\"manual\">",
              "    <property name=\"currentSponsorClientId\" direction=\"asc\"/>",
              "    <property name=\"deletionTime\" direction=\"asc\"/>",
              "    <property name=\"searchName\" direction=\"asc\"/>",
              "  </datastore-index>",
              "</datastore-indexes>");
  private static final String UNDECLARED_INDEX =
      DECLARED_INDEX.replace("ContactResource", "NoSuchResource");

  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  private final AppEngineRule appEngineRule =
      AppEngineRule.builder().withDatastoreAndCloudSql().build();

  @Before
  public void setupAppEngineRule() throws Exception {
    appEngineRule.temporaryFolder = temporaryFolder;
    appEngineRule.before();
  }

  @Test
  public void testTeardown_successNoAutoIndexFile() {
    appEngineRule.after();
  }

  @Test
  public void testTeardown_successEmptyAutoIndexFile() throws Exception {
    writeAutoIndexFile("");
    appEngineRule.after();
  }

  @Test
  public void testTeardown_successWhiteSpacesOnlyAutoIndexFile() throws Exception {
    writeAutoIndexFile("  ");
    appEngineRule.after();
  }

  @Test
  public void testTeardown_successOnlyDeclaredIndexesUsed() throws Exception {
    writeAutoIndexFile(DECLARED_INDEX);
    appEngineRule.after();
  }

  @Test
  public void testTeardown_failureUndeclaredIndexesUsed() throws Exception {
    writeAutoIndexFile(UNDECLARED_INDEX);
    assertThrows(AssertionError.class, () -> appEngineRule.after());
  }

  @Test
  public void testRegisterOfyEntities_failure() {
    AppEngineRule appEngineRule =
        AppEngineRule.builder()
            .withDatastoreAndCloudSql()
            .withOfyTestEntities(google.registry.testing.TestObject.class, TestObject.class)
            .build();
    String expectedErrorMessage =
        String.format(
            "Cannot register %s. The Kind %s is already registered with %s",
            TestObject.class.getName(),
            "TestObject",
            google.registry.testing.TestObject.class.getName());
    assertThrows(expectedErrorMessage, IllegalStateException.class, appEngineRule::before);
    appEngineRule.after();
  }

  @Test
  public void testOfyEntities_uniqueKinds() {
    try (ScanResult scanResult =
        new ClassGraph()
            .enableAnnotationInfo()
            .ignoreClassVisibility()
            .whitelistPackages("google.registry")
            .scan()) {
      Multimap<String, Class<?>> kindToEntityMultiMap =
          scanResult.getClassesWithAnnotation(Entity.class.getName()).stream()
              .filter(clazz -> !clazz.getName().equals(TestObject.class.getName()))
              .map(clazz -> clazz.loadClass())
              .collect(
                  Multimaps.toMultimap(
                      Key::getKind,
                      clazz -> clazz,
                      MultimapBuilder.hashKeys().linkedListValues()::build));
      Map<String, Collection<Class<?>>> conflictingKinds =
          kindToEntityMultiMap.asMap().entrySet().stream()
              .filter(e -> e.getValue().size() > 1)
              .collect(entriesToImmutableMap());
      assertWithMessage(
              "Conflicting Ofy kinds found. Tests will break if they are registered with "
                  + " AppEngineRule in the same test executor.")
          .that(conflictingKinds)
          .isEmpty();
    }
  }

  private void writeAutoIndexFile(String content) throws IOException {
    com.google.common.io.Files.asCharSink(
            new File(temporaryFolder.getRoot(), "datastore-indexes-auto.xml"), UTF_8)
        .write(content);
  }

  @Entity
  private static final class TestObject {}
}
