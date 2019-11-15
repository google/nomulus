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

package google.registry.persistence;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.persistence.Entity;
import javax.persistence.Id;
import org.joda.money.CurrencyUnit;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.testcontainers.containers.PostgreSQLContainer;

/** Unit tests for {@link HibernateSchemaExporter}. */
@RunWith(JUnit4.class)
public class HibernateSchemaExporterTest {
  @ClassRule
  public static final PostgreSQLContainer database =
      new PostgreSQLContainer(NomulusPostgreSql.getDockerTag());

  private static HibernateSchemaExporter exporter;

  @Rule public final TemporaryFolder tempFolder = new TemporaryFolder();

  @BeforeClass
  public static void init() {
    exporter =
        HibernateSchemaExporter.create(
            database.getJdbcUrl(), database.getUsername(), database.getPassword());
  }

  @Test
  public void export_succeeds() throws IOException {
    File sqlFile = tempFolder.newFile();
    exporter.export(ImmutableList.of(TestEntity.class), sqlFile);
    assertThat(Files.readAllBytes(sqlFile.toPath()))
        .isEqualTo(
            ("\n"
                    + "    create table \"TestEntity\" (\n"
                    + "       name text not null,\n"
                    + "        cu text,\n"
                    + "        primary key (name)\n"
                    + "    );\n")
                .getBytes(StandardCharsets.UTF_8));
  }

  @Entity(name = "TestEntity") // Override entity name to avoid the nested class reference.
  private static class TestEntity {
    @Id String name;

    CurrencyUnit cu;
  }
}
