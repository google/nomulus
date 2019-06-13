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

package google.registry.webdriver;

import static com.google.common.base.Preconditions.checkState;

import com.google.common.flogger.FluentLogger;
import java.lang.reflect.Field;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;

/**
 * A JUnit test runner which can retry each test up to 3 times.
 *
 * <p>To use this runner, annotate the test class with {@link RepeatableRunner} and define a field
 * with type of {@link AttemptNumber}:
 *
 * <pre>
 * &#064;RunWith(RepeatableRunner.class)
 * public class RepeatableRunnerTest {
 *   private AttemptNumber attemptNumber = new AttemptNumber();
 *
 *   &#064;Test
 *   public void test() {
 *     print(attemptNumber.get());
 *   }
 * }
 * </pre>
 *
 * <p>This runner is for our visual regression to prevent flakes during the run. The test is
 * repeated multiple times until it passes and it only fails if it failed 3 times.
 */
public class RepeatableRunner extends BlockJUnit4ClassRunner {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final boolean RUN_ALL_ATTEMPTS =
      Boolean.parseBoolean(System.getProperty("test.screenshot.runAllAttempts", "false"));

  private static final int MAX_ATTEMPTS =
      Integer.parseInt(System.getProperty("test.screenshot.maxAttempts", "3"));

  private final Field attemptNumberField;
  private AttemptNumber lastAttemptNumber;

  /** Constructs a new instance of the default runner */
  public RepeatableRunner(Class<?> clazz) throws InitializationError {
    super(clazz);
    attemptNumberField = getAttemptNumberField();
  }

  private Field getAttemptNumberField() {
    List<Field> attemptNumberFields =
        FieldUtils.getAllFieldsList(getTestClass().getJavaClass()).stream()
            .filter(declaredField -> declaredField.getType().equals(AttemptNumber.class))
            .collect(Collectors.toList());

    if (attemptNumberFields.size() == 0) {
      throw new IllegalArgumentException("Missing a field with type of AttemptNumber");
    } else if (attemptNumberFields.size() > 1) {
      throw new IllegalArgumentException(
          "Cannot have more than 1 field with type of AttemptNumber");
    }
    Field attemptNumberField = attemptNumberFields.get(0);
    // It should not matter if that field is set to private
    attemptNumberField.setAccessible(true);
    return attemptNumberField;
  }

  @Override
  protected Object createTest() throws Exception {
    Object testObject = super.createTest();
    // lastAttemptNumber must be null at this moment to indicate that we have
    // created the RepeatableStatement for the previous AttemptNumber object
    // or this is the first time we run the test.
    // If it is not the case, either the tests are run in parallel or the
    // behavior of BlockJUnit4ClassRunner is changed.
    checkState(lastAttemptNumber == null);
    lastAttemptNumber = (AttemptNumber) attemptNumberField.get(testObject);
    return testObject;
  }

  @Override
  protected Statement methodBlock(FrameworkMethod method) {
    Statement statement = super.methodBlock(method);
    RepeatableStatement repeatableStatement =
        new RepeatableStatement(statement, method, lastAttemptNumber);
    // Explicitly set lastAttemptNumber to null because it should
    // not be reused accidentally by the next test.
    lastAttemptNumber = null;
    return repeatableStatement;
  }

  /** A simple POJO to store the number of the test attempt. */
  public static class AttemptNumber {
    private int attemptNumber;

    /** Returns the number of the test attempt. */
    public int get() {
      return attemptNumber;
    }

    private void set(int attemptNumber) {
      this.attemptNumber = attemptNumber;
    }
  }

  private static class RepeatableStatement extends Statement {

    private Statement statement;
    private FrameworkMethod method;
    private AttemptNumber attemptNumber;

    public RepeatableStatement(
        Statement statement, FrameworkMethod method, AttemptNumber attemptNumber) {
      this.statement = statement;
      this.method = method;
      this.attemptNumber = attemptNumber;
    }

    @Override
    public void evaluate() throws Throwable {
      checkState(MAX_ATTEMPTS > 0);
      int numSuccess = 0, numFailure = 0;
      Throwable lastException = null;
      for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
        attemptNumber.set(attempt);
        try {
          statement.evaluate();
          numSuccess++;
          logger.atInfo().log(
              "[%s] Attempt %d of %d succeeded!\n", method.getName(), attempt, MAX_ATTEMPTS);
          if (!RUN_ALL_ATTEMPTS) {
            return;
          }
        } catch (Throwable e) {
          numFailure++;
          lastException = e;
          logger.atWarning().withCause(e).log(
              "[%s] Attempt %d of %d failed!\n", method.getName(), attempt, MAX_ATTEMPTS);
        }
      }
      logger.atInfo().log(
          "Test [%s] was executed %d times, %d attempts succeeded and %d attempts failed.",
          method.getName(), numSuccess + numFailure, numSuccess, numFailure);
      if (numSuccess == 0) {
        logger.atSevere().log(
            "[%s] didn't pass after all %d attempts failed!\n", method.getName(), MAX_ATTEMPTS);
        // In most cases, setting RUN_ALL_ATTEMPTS to true is to find the golden image, so we should
        // not throw an exception to reduce confusion
        if (!RUN_ALL_ATTEMPTS) {
          throw lastException;
        }
      }
    }
  }
}
