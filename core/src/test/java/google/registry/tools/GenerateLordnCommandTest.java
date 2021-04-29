// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static java.nio.charset.StandardCharsets.UTF_8;

import google.registry.model.domain.launch.LaunchNotice;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.TestOfyAndSql;
import java.nio.file.Files;
import java.nio.file.Path;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

@DualDatabaseTest
class GenerateLordnCommandTest extends CommandTestCase<GenerateLordnCommand> {

  @TempDir Path outputDir;

  @BeforeEach
  void beforeEach() {
    fakeClock.setTo(DateTime.parse("2021-04-16T10:04:00.000Z"));
    command.clock = fakeClock;
  }

  @TestOfyAndSql
  void testExample() throws Exception {
    createTld("tld");
    persistResource(newDomainBase("sneezy.tld").asBuilder().setSmdId("smd1").build());
    persistResource(newDomainBase("wheezy.tld").asBuilder().setSmdId("smd2").build());
    persistResource(
        newDomainBase("fleecey.tld")
            .asBuilder()
            .setLaunchNotice(LaunchNotice.create("smd3", "validator", START_OF_TIME, START_OF_TIME))
            .setSmdId("smd3")
            .build());
    Path claimsCsv = outputDir.resolve("claims.csv");
    Path sunriseCsv = outputDir.resolve("sunrise.csv");
    runCommand("-t tld", "-c " + claimsCsv, "-s " + sunriseCsv);
    assertThat(Files.readAllBytes(claimsCsv))
        .isEqualTo(
            ("1,2021-04-16T10:04:00.000Z,1\n"
                 + "roid,domain-name,notice-id,registrar-id,registration-datetime,ack-datetime,application-datetime\n"
                 + "6-TLD,fleecey.tld,smd3,1,1970-01-01T00:00:00.000Z,1970-01-01T00:00:00.000Z\n")
                .getBytes(UTF_8));
    assertThat(Files.readAllBytes(sunriseCsv))
        .isEqualTo(
            ("1,2021-04-16T10:04:00.001Z,2\n"
                    + "roid,domain-name,SMD-id,registrar-id,registration-datetime,"
                    + "application-datetime\n"
                    + "2-TLD,sneezy.tld,smd1,1,1970-01-01T00:00:00.000Z\n"
                    + "4-TLD,wheezy.tld,smd2,1,1970-01-01T00:00:00.000Z\n")
                .getBytes(UTF_8));
  }
}
