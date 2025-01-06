// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.logging.Logger.getLogger;

import com.google.common.testing.TestLogHandler;
import google.registry.persistence.transaction.JpaTestExtensions.JpaUnitTestExtension;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Verifies that specific {@code ERROR}-level logging by Hibernate does not appear in the log files.
 *
 * <p>Please refer to the class javadoc of {@link DatabaseException} for more information.
 */
public class HibernateLoggingSuppressionTest {

  private static final String LOG_SUPPRESSION_TARGET =
      "org.hibernate.engine.jdbc.spi.SqlExceptionHelper";

  // The line that should be added to the `logging.properties` file.
  private static final String LOGGING_PROPERTIES_LINE = LOG_SUPPRESSION_TARGET + ".level=OFF\n";

  @RegisterExtension
  public final JpaUnitTestExtension jpa =
      new JpaTestExtensions.Builder()
          .withoutCannedData()
          .withEntityClass(TestEntity.class)
          .buildUnitTestExtension();

  private Logger logger;
  private TestLogHandler testLogHandler;

  @BeforeEach
  void setup() {
    testLogHandler = new TestLogHandler();
    logger = getLogger(LOG_SUPPRESSION_TARGET);
    logger.addHandler(testLogHandler);
  }

  @AfterEach
  void teardown() {
    logger.removeHandler(testLogHandler);
  }

  // Updates logging configs to suppress logging. Call `revertSuppressionOfHibernateLogs` to revert.
  void suppressHibernateLogs() throws IOException {
    try (ByteArrayInputStream additionalProperties =
        new ByteArrayInputStream(LOGGING_PROPERTIES_LINE.getBytes(UTF_8))) {
      LogManager.getLogManager()
          .updateConfiguration(
              additionalProperties,
              x ->
                  (o, n) -> {
                    if (!x.startsWith(LOG_SUPPRESSION_TARGET)) {
                      return o;
                    }
                    checkArgument(o == null, "Cannot override old value in this test");
                    return n;
                  });
    }
  }

  void revertSuppressionOfHibernateLogs() throws IOException {
    try (ByteArrayInputStream additionalProperties =
        new ByteArrayInputStream(LOGGING_PROPERTIES_LINE.getBytes(UTF_8))) {
      LogManager.getLogManager()
          .updateConfiguration(
              additionalProperties,
              x ->
                  (o, n) -> {
                    if (!x.startsWith(LOG_SUPPRESSION_TARGET)) {
                      return o;
                    }
                    return null;
                  });
    }
  }

  @Test
  void noLoggingSuppression() {
    try {
      tm().transact(() -> tm().insert(new TestEntity(1, 1)));
      tm().transact(() -> tm().insert(new TestEntity(1, 1)));
    } catch (DatabaseException e) {
      assertThat(e).hasMessageThat().contains("SQLState: ");
    }
    assertThat(
            testLogHandler.getStoredLogRecords().stream()
                .anyMatch(
                    logRecord ->
                        logRecord.getLevel().equals(Level.SEVERE)
                            && logRecord.getMessage().contains("duplicate key")))
        .isTrue();
  }

  @Test
  void withLoggingSuppression() throws Exception {
    suppressHibernateLogs();
    try {
      tm().transact(() -> tm().insert(new TestEntity(1, 1)));
      tm().transact(() -> tm().insert(new TestEntity(1, 1)));
    } catch (DatabaseException e) {
      assertThat(e).hasMessageThat().contains("SQLState: ");
    }
    assertThat(
            testLogHandler.getStoredLogRecords().stream()
                .anyMatch(
                    logRecord ->
                        logRecord.getLevel().equals(Level.SEVERE)
                            && logRecord.getMessage().contains("duplicate key")))
        .isFalse();
    revertSuppressionOfHibernateLogs();
  }

  @Entity
  static class TestEntity {
    @Id long id;
    int value;

    // For Hibernate
    public TestEntity() {}

    TestEntity(long id, int value) {
      this.id = id;
      this.value = value;
    }

    TestEntity incValue() {
      this.value++;
      return this;
    }
  }
}
