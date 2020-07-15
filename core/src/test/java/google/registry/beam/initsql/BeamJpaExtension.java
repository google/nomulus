// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.beam.initsql;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;
import java.util.function.Supplier;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.rules.ExternalResource;
import org.junit.rules.TemporaryFolder;
import org.testcontainers.containers.JdbcDatabaseContainer;

/**
 * Helpers for setting up {@link BeamJpaModule} in tests.
 *
 * <p>This extension is often used with a Database container and/or temporary file folder. User must
 * make sure that all dependent rules/extensions are set up before this extension. In Junit 4, this
 * needs to be achieved by using a {@code RuleChain}. In JUnit 5, this can be done by annotating
 * this extension with an {@code Order}.
 */
public final class BeamJpaExtension extends ExternalResource
    implements BeforeEachCallback, AfterEachCallback, Serializable {

  private final transient Supplier<File> credentialFolderSupplier;
  private final transient JdbcDatabaseContainer<?> database;

  private File credentialFile;

  private transient BeamJpaModule beamJpaModule;

  public BeamJpaExtension(Supplier<File> credentialFolderSupplier, JdbcDatabaseContainer database) {
    this.credentialFolderSupplier = credentialFolderSupplier;
    this.database = database;
  }

  public BeamJpaExtension(TemporaryFolder temporaryFolder, JdbcDatabaseContainer database) {
    this(
        () -> {
          try {
            return temporaryFolder.newFolder();
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        },
        database);
  }

  public File getCredentialFile() {
    return credentialFile;
  }

  public BeamJpaModule getBeamJpaModule() {
    if (beamJpaModule != null) {
      return beamJpaModule;
    }
    return beamJpaModule = new BeamJpaModule(credentialFile.getAbsolutePath());
  }

  @Override
  public void beforeEach(ExtensionContext context) throws IOException {
    credentialFile = new File(credentialFolderSupplier.get(), "credential");
    new PrintStream(credentialFile)
        .printf("%s %s %s", database.getJdbcUrl(), database.getUsername(), database.getPassword())
        .close();
  }

  @Override
  public void afterEach(ExtensionContext context) {
    credentialFile.delete();
  }

  @Override
  protected void before() throws IOException {
    beforeEach(null);
  }

  @Override
  protected void after() {
    afterEach(null);
  }
}
