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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.containers.JdbcDatabaseContainer;

/**
 * Helpers for setting up {@link BeamJpaModule} in tests.
 *
 * <p>This extension is often used with a Database container and/or temporary file folder. User must
 * make sure that all dependent extensions are set up before this extension, e.g., by assigning
 * {@link org.junit.jupiter.api.Order orders}.
 */
public final class BeamJpaExtension implements BeforeEachCallback, AfterEachCallback, Serializable {

  private final transient JdbcDatabaseContainer<?> database;
  private final transient Supplier<Path> credentialPathSupplier;
  private transient BeamJpaModule beamJpaModule;

  private File credentialFile;

  public BeamJpaExtension(Supplier<Path> credentialPathSupplier, JdbcDatabaseContainer database) {
    this.database = database;
    this.credentialPathSupplier = credentialPathSupplier;
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
    credentialFile = Files.createFile(credentialPathSupplier.get()).toFile();
    new PrintStream(credentialFile)
        .printf("%s %s %s", database.getJdbcUrl(), database.getUsername(), database.getPassword())
        .close();
  }

  @Override
  public void afterEach(ExtensionContext context) {
    credentialFile.delete();
  }
}
