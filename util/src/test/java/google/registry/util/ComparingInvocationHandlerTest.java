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

package google.registry.util;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.ArrayList;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ComparingInvocationHandler}. */
@RunWith(JUnit4.class)
public class ComparingInvocationHandlerTest {

  static class Dummy {}

  interface MyInterface {
    String func(int a, String b);

    Dummy func();
  }

  static class MyException extends RuntimeException {
    MyException(String msg) {
      super(msg);
    }
  }

  static class MyOtherException extends RuntimeException {
    MyOtherException(String msg) {
      super(msg);
    }
  }

  static final ArrayList<String> log = new ArrayList<>();

  static final class MyInterfaceComparingInvocationHandler
      extends ComparingInvocationHandler<MyInterface> {

    private boolean dummyEqualsResult = true;
    private boolean exceptionEqualsResult = true;

    MyInterfaceComparingInvocationHandler(MyInterface actual, MyInterface second) {
      super(MyInterface.class, actual, second);
    }

    MyInterfaceComparingInvocationHandler setExeptionsEquals(boolean result) {
      this.exceptionEqualsResult = result;
      return this;
    }

    MyInterfaceComparingInvocationHandler setDummyEquals(boolean result) {
      this.dummyEqualsResult = result;
      return this;
    }

    @Override
    protected void log(Method method, String message) {
      log.add(String.format("%s: %s", method.getName(), message));
    }

    @Override
    protected boolean compareResults(Method method, @Nullable Object a, @Nullable Object b) {
      if (method.getReturnType().equals(Dummy.class)) {
        return dummyEqualsResult;
      }
      return super.compareResults(method, a, b);
    }

    @Override
    protected String stringifyResult(Method method, @Nullable Object a) {
      if (method.getReturnType().equals(Dummy.class)) {
        return "dummy";
      }
      return super.stringifyResult(method, a);
    }

    @Override
    protected boolean compareThrown(Method method, Throwable a, Throwable b) {
      return exceptionEqualsResult && super.compareThrown(method, a, b);
    }

    @Override
    protected String stringifyThrown(Method method, Throwable a) {
      return String.format("testException(%s)", super.stringifyThrown(method, a));
    }
  }

  private static final String ACTUAL_RESULT = "actual result";
  private static final String SECOND_RESULT = "second result";

  private final MyInterface myActualMock = mock(MyInterface.class);
  private final MyInterface mySecondMock = mock(MyInterface.class);
  private MyInterfaceComparingInvocationHandler invocationHandler;

  @Before
  public void setUp() {
    log.clear();
    invocationHandler = new MyInterfaceComparingInvocationHandler(myActualMock, mySecondMock);
  }

  @Test
  public void test_actualThrows_logDifference() {
    MyInterface comparator = invocationHandler.makeProxy();
    MyException myException = new MyException("message");
    when(myActualMock.func(3, "str")).thenThrow(myException);
    when(mySecondMock.func(3, "str")).thenReturn(SECOND_RESULT);

    assertThrows(MyException.class, () -> comparator.func(3, "str"));
    assertThat(log)
        .containsExactly(
            String.format(
                "func: Only actual implementation threw exception: testException(%s)",
                myException.toString()));
  }

  @Test
  public void test_secondThrows_logDifference() {
    MyInterface comparator = invocationHandler.makeProxy();
    MyOtherException myOtherException = new MyOtherException("message");
    when(myActualMock.func(3, "str")).thenReturn(ACTUAL_RESULT);
    when(mySecondMock.func(3, "str")).thenThrow(myOtherException);

    assertThat(comparator.func(3, "str")).isEqualTo(ACTUAL_RESULT);

    assertThat(log)
        .containsExactly(
            String.format(
                "func: Only second implementation threw exception: testException(%s)",
                myOtherException.toString()));
  }

  @Test
  public void test_bothThrowEqual_noLog() {
    MyInterface comparator = invocationHandler.setExeptionsEquals(true).makeProxy();
    MyException myException = new MyException("actual message");
    MyOtherException myOtherException = new MyOtherException("second message");
    when(myActualMock.func(3, "str")).thenThrow(myException);
    when(mySecondMock.func(3, "str")).thenThrow(myOtherException);

    assertThrows(MyException.class, () -> comparator.func(3, "str"));
    assertThat(log).isEmpty();
  }

  @Test
  public void test_bothThrowDifferent_logDifference() {
    MyInterface comparator = invocationHandler.setExeptionsEquals(false).makeProxy();
    MyException myException = new MyException("actual message");
    MyOtherException myOtherException = new MyOtherException("second message");
    when(myActualMock.func(3, "str")).thenThrow(myException);
    when(mySecondMock.func(3, "str")).thenThrow(myOtherException);

    assertThrows(MyException.class, () -> comparator.func(3, "str"));
    assertThat(log)
        .containsExactly(
            String.format(
                "func: Both implementations threw, but got different exceptions! "
                    + "'testException(%s)' vs 'testException(%s)'",
                myException.toString(), myOtherException.toString()));
  }

  @Test
  public void test_bothReturnSame_noLog() {
    MyInterface comparator = invocationHandler.makeProxy();
    when(myActualMock.func(3, "str")).thenReturn(ACTUAL_RESULT);
    when(mySecondMock.func(3, "str")).thenReturn(ACTUAL_RESULT);

    assertThat(comparator.func(3, "str")).isEqualTo(ACTUAL_RESULT);

    assertThat(log).isEmpty();
  }

  @Test
  public void test_bothReturnDifferent_logDifference() {
    MyInterface comparator = invocationHandler.makeProxy();
    when(myActualMock.func(3, "str")).thenReturn(ACTUAL_RESULT);
    when(mySecondMock.func(3, "str")).thenReturn(SECOND_RESULT);

    assertThat(comparator.func(3, "str")).isEqualTo(ACTUAL_RESULT);

    assertThat(log)
        .containsExactly("func: Got different results! 'actual result' vs 'second result'");
  }

  @Test
  public void test_usesOverriddenMethods_noDifference() {
    MyInterface comparator = invocationHandler.setDummyEquals(true).makeProxy();
    when(myActualMock.func()).thenReturn(new Dummy());
    when(mySecondMock.func()).thenReturn(new Dummy());

    comparator.func();

    assertThat(log).isEmpty();
  }

  @Test
  public void test_usesOverriddenMethods_logDifference() {
    MyInterface comparator = invocationHandler.setDummyEquals(false).makeProxy();
    when(myActualMock.func()).thenReturn(new Dummy());
    when(mySecondMock.func()).thenReturn(new Dummy());

    comparator.func();

    assertThat(log).containsExactly("func: Got different results! 'dummy' vs 'dummy'");
  }
}
