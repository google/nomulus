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

package google.registry.persistence.transaction;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static java.nio.charset.StandardCharsets.UTF_8;

import google.registry.persistence.transaction.JpaTestExtensions.JpaUnitTestExtension;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit test for {@link JpaTestExtensions.Builder#withSqlLogging()}. */
class JpaTestExtensionsSqlLoggingTest {

  // Entity under test: configured to log SQL statements to Stdout.
  @RegisterExtension
  JpaUnitTestExtension jpaExtension =
      new JpaTestExtensions.Builder().withSqlLogging().buildUnitTestExtension();

  private PrintStream orgStdout;
  private ByteArrayOutputStream stdoutBuffer;

  @BeforeEach
  void beforeEach() {
    orgStdout = System.out;
    System.setOut(new PrintStream(stdoutBuffer = new ByteArrayOutputStream()));
  }

  @AfterEach
  void afterEach() {
    System.setOut(orgStdout);
  }

  @Test
  void sqlLog_displayed() throws UnsupportedEncodingException {
    jpaTm()
        .transact(() -> jpaTm().getEntityManager().createNativeQuery("select 1").getSingleResult());
    assertThat(stdoutBuffer.toString(UTF_8.name())).contains("select 1");
  }
}
