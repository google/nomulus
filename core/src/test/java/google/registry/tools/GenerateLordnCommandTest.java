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

import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mockStatic;

import com.google.common.collect.ImmutableList;
import google.registry.model.domain.launch.LaunchNotice;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.TestOfyAndSql;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.mockito.MockedStatic;

@DualDatabaseTest
public class GenerateLordnCommandTest extends CommandTestCase<GenerateLordnCommand> {

  public GenerateLordnCommandTest() {}

  @TestOfyAndSql
  public void testExample() throws Exception {
    createTld("tld");
    persistResource(newDomainBase("sneezy.tld").asBuilder().setSmdId("smd1").build());
    persistResource(newDomainBase("wheezy.tld").asBuilder().setSmdId("smd2").build());
    persistResource(
        newDomainBase("fleecey.tld")
            .asBuilder()
            .setLaunchNotice(LaunchNotice.create("smd3", "validator", START_OF_TIME, START_OF_TIME))
            .setSmdId("smd3")
            .build());
    DateTime now = DateTime.parse("2021-04-16T10:04:00.000Z");
    try (MockedStatic<DateTime> dateTime = mockStatic(DateTime.class);
        MockedStatic<Files> files = mockStatic(Files.class)) {
      dateTime.when(() -> DateTime.now(any(DateTimeZone.class))).thenReturn(now);
      files.when(() -> Files.exists(any(Path.class))).thenReturn(true);
      files.when(() -> Files.isWritable(any(Path.class))).thenReturn(true);
      runCommand("-t tld", "-c claims.csv", "-s sunrise.csv");
      files.verify(
          () ->
              Files.write(
                  Paths.get("claims.csv"),
                  ImmutableList.of(
                      "1,2021-04-16T10:04:00.000Z,1",
                      "roid,domain-name,notice-id,registrar-id,registration-datetime,ack-datetime,"
                          + "application-datetime",
                      "6-TLD,fleecey.tld,smd3,1,1970-01-01T00:00:00.000Z,1970-01-01T00:00:00.000Z"),
                  UTF_8));
      files.verify(
          () ->
              Files.write(
                  Paths.get("sunrise.csv"),
                  ImmutableList.of(
                      "1,2021-04-16T10:04:00.001Z,2",
                      "roid,domain-name,SMD-id,registrar-id,registration-datetime,"
                          + "application-datetime",
                      "2-TLD,sneezy.tld,smd1,1,1970-01-01T00:00:00.000Z",
                      "4-TLD,wheezy.tld,smd2,1,1970-01-01T00:00:00.000Z"),
                  UTF_8));
    }
  }
}
