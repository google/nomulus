// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.readAllLines;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.collect.ImmutableMap;
import google.registry.model.tmch.ClaimsListDualDatabaseDao;
import google.registry.model.tmch.ClaimsListShard;
import java.io.File;
import java.nio.file.Files;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link GetClaimsListCommand}. */
class GetClaimsListCommandTest extends CommandTestCase<GetClaimsListCommand> {

  @Test
  void testSuccess_getWorks() throws Exception {
    ClaimsListDualDatabaseDao.save(
        ClaimsListShard.create(DateTime.now(UTC), ImmutableMap.of("a", "1", "b", "2")));
    File output = tmpDir.resolve("claims.txt").toFile();
    runCommand("--output=" + output.getAbsolutePath());
    assertThat(readAllLines(output.toPath(), UTF_8)).containsExactly("a,1", "b,2");
  }

  @Test
  void testSuccess_endsWithNewline() throws Exception {
    ClaimsListDualDatabaseDao.save(
        ClaimsListShard.create(DateTime.now(UTC), ImmutableMap.of("a", "1")));
    File output = tmpDir.resolve("claims.txt").toFile();
    runCommand("--output=" + output.getAbsolutePath());
    assertThat(new String(Files.readAllBytes(output.toPath()), UTF_8)).endsWith("\n");
  }
}
