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
import static com.google.common.truth.Truth.assertWithMessage;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.base.Joiner;
import google.registry.persistence.transaction.JpaTransactionManager;
import java.io.File;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
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
              "  <datastore-index kind=\"Contact\" ancestor=\"false\" source=\"manual\">",
              "    <property name=\"currentSponsorClientId\" direction=\"asc\"/>",
              "    <property name=\"deletionTime\" direction=\"asc\"/>",
              "    <property name=\"searchName\" direction=\"asc\"/>",
              "  </datastore-index>",
              "</datastore-indexes>");

  private static final String UNDECLARED_INDEX =
      DECLARED_INDEX.replace("Contact", "NoSuchResource");

  /**
   * Sets up test AppEngine instance.
   *
   * <p>Not registered as extension since this instance's afterEach() method is also under test. All
   * methods should call {@link AppEngineExtension#afterEach appEngine.afterEach} explicitly.
   */
  private final AppEngineExtension appEngine = AppEngineExtension.builder().withCloudSql().build();

  private JpaTransactionManager originalJpa;

  @RegisterExtension
  final ContextCapturingMetaExtension context = new ContextCapturingMetaExtension();

  @BeforeEach
  void beforeEach() throws Exception {
    originalJpa = jpaTm();
    appEngine.beforeEach(context.getContext());
  }

  @AfterEach
  void afterEach() {
    // Note: cannot use isSameInstanceAs() because DummyTransactionManager would throw on any
    // access.
    assertWithMessage("Original state not restore. Is appEngine.afterEach not called by this test?")
        .that(originalJpa == jpaTm())
        .isTrue();
  }

  @Test
  void testTeardown_successNoAutoIndexFile() throws Exception {
    appEngine.afterEach(context.getContext());
  }

  @Test
  void testTeardown_successEmptyAutoIndexFile() throws Exception {
    writeAutoIndexFile("");
    appEngine.afterEach(context.getContext());
  }

  @Test
  void testTeardown_successWhiteSpacesOnlyAutoIndexFile() throws Exception {
    writeAutoIndexFile("  ");
    appEngine.afterEach(context.getContext());
  }

  @Test
  void testTeardown_successOnlyDeclaredIndexesUsed() throws Exception {
    writeAutoIndexFile(DECLARED_INDEX);
    appEngine.afterEach(context.getContext());
  }

  @Test
  void testTeardown_failureUndeclaredIndexesUsed() throws Exception {
    writeAutoIndexFile(UNDECLARED_INDEX);
    assertThrows(AssertionError.class, () -> appEngine.afterEach(context.getContext()));
  }

  private void writeAutoIndexFile(String content) throws IOException {
    asCharSink(new File(appEngine.tmpDir, "datastore-indexes-auto.xml"), UTF_8).write(content);
  }
}
