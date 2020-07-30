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

import static com.google.common.io.Files.asCharSink;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static google.registry.util.CollectionUtils.entriesToImmutableMap;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.base.Joiner;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.Multimaps;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Unit tests for {@link AppEngineExtension}.
 *
 * <p>Tests focus on Datastore-related assertions made during teardown checks.
 */
class AppEngineExtensionTest {

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

  private final AppEngineExtension appEngineRule =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  @RegisterExtension
  final ContextCapturingMetaExtension context = new ContextCapturingMetaExtension();

  @BeforeEach
  void beforeEach() throws Exception {
    appEngineRule.beforeEach(context.getContext());
  }

  @Test
  void testTeardown_successNoAutoIndexFile() throws Exception {
    appEngineRule.afterEach(context.getContext());
  }

  @Test
  void testTeardown_successEmptyAutoIndexFile() throws Exception {
    writeAutoIndexFile("");
    appEngineRule.afterEach(context.getContext());
  }

  @Test
  void testTeardown_successWhiteSpacesOnlyAutoIndexFile() throws Exception {
    writeAutoIndexFile("  ");
    appEngineRule.afterEach(context.getContext());
  }

  @Test
  void testTeardown_successOnlyDeclaredIndexesUsed() throws Exception {
    writeAutoIndexFile(DECLARED_INDEX);
    appEngineRule.afterEach(context.getContext());
  }

  @Test
  void testTeardown_failureUndeclaredIndexesUsed() throws Exception {
    writeAutoIndexFile(UNDECLARED_INDEX);
    assertThrows(AssertionError.class, () -> appEngineRule.afterEach(context.getContext()));
  }

  @Test
  void testRegisterOfyEntities_duplicateEntitiesWithSameName_fails() {
    AppEngineExtension appEngineRule =
        AppEngineExtension.builder()
            .withDatastoreAndCloudSql()
            .withOfyTestEntities(google.registry.testing.TestObject.class, TestObject.class)
            .build();
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class, () -> appEngineRule.beforeEach(context.getContext()));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            String.format(
                "Cannot register %s. The Kind %s is already registered with %s.",
                TestObject.class.getName(),
                "TestObject",
                google.registry.testing.TestObject.class.getName()));
  }

  @Test
  void testOfyEntities_uniqueKinds() {
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
    asCharSink(new File(appEngineRule.tmpDir, "datastore-indexes-auto.xml"), UTF_8).write(content);
  }

  @Entity
  private static final class TestObject {
    @Id long id;
  }
}
