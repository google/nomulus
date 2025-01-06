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

package google.registry.util;

import com.google.common.reflect.Reflection;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Abstract InvocationHandler comparing two implementations of some interface.
 *
 * <p>Given an interface, and two instances of that interface (the "original" instance we know
 * works, and a "second" instance we wish to test), creates an InvocationHandler that acts like an
 * exact proxy to the "original" instance.
 *
 * <p>In addition, it will log any differences in return values or thrown exception between the
 * "original" and "second" instances.
 *
 * <p>This can be used to create an exact proxy to the original instance that can be placed in any
 * code, while live testing the second instance.
 */
public abstract class ComparingInvocationHandler<T> implements InvocationHandler {

  private final T actualImplementation;
  private final T secondImplementation;
  private final Class<T> interfaceClass;

  /**
   * Creates a new InvocationHandler for the given interface.
   *
   * @param interfaceClass the interface we want to create.
   * @param actualImplementation the resulting proxy will be an exact proxy to this object
   * @param secondImplementation Only used to log difference compared to actualImplementation.
   *     Otherwise has no effect on the resulting proxy's behavior.
   */
  public ComparingInvocationHandler(
      Class<T> interfaceClass, T actualImplementation, T secondImplementation) {
    this.actualImplementation = actualImplementation;
    this.secondImplementation = secondImplementation;
    this.interfaceClass = interfaceClass;
  }

  /**
   * Returns the proxy to the actualImplementation.
   *
   * <p>The return value is a drop-in replacement to the actualImplementation, but will log any
   * difference with the secondImplementation during normal execution.
   */
  public final T makeProxy() {
    return Reflection.newProxy(interfaceClass, this);
  }

  /**
   * Called when there was a difference between the implementations.
   *
   * @param method the method where the difference was found
   * @param message human readable description of the difference found
   */
  protected abstract void log(Method method, String message);

  /**
   * Implements toString for specific types.
   *
   * <p>By default objects are logged using their .toString. If .toString isn't implemented for
   * some relevant classes (or if we want to use a different version), override this method with
   * the desired implementation.
   *
   * @param method the method whose return value is given
   * @param object the object returned by a call to method
   */
  protected String stringifyResult(
      @SuppressWarnings("unused") Method method,
      @Nullable Object object) {
    return String.valueOf(object);
  }

  /**
   * Checks whether the method results are as similar as we expect.
   *
   * <p>By default objects are compared using their .equals. If .equals isn't implemented for some
   * relevant classes (or if we want change what is considered "not equal"), override this method
   * with the desired implementation.
   *
   * @param method the method whose return value is given
   * @param actual the object returned by a call to method for the "actual" implementation
   * @param second the object returned by a call to method for the "second" implementation
   */
  protected boolean compareResults(
      @SuppressWarnings("unused") Method method,
      @Nullable Object actual,
      @Nullable Object second) {
    return Objects.equals(actual, second);
  }

  /**
   * Checks whether the thrown exceptions are as similar as we expect.
   *
   * <p>By default this returns 'true' for any input: all we check by default is that both
   * implementations threw something. Override if you need to actually compare both throwables.
   *
   * @param method the method whose return value is given
   * @param actual the exception thrown by a call to method for the "actual" implementation
   * @param second the exception thrown by a call to method for the "second" implementation
   */
  protected boolean compareThrown(
      @SuppressWarnings("unused") Method method,
      Throwable actual,
      Throwable second) {
    return true;
  }

  /**
   * Implements toString for thrown exceptions.
   *
   * <p>By default exceptions are logged using their .toString. If more data is needed (part of
   * stack trace for example), override this method with the desired implementation.
   *
   * @param method the method whose return value is given
   * @param throwable the exception thrown by a call to method
   */
  protected String stringifyThrown(
      @SuppressWarnings("unused") Method method,
      Throwable throwable) {
    return throwable.toString();
  }

  @Override
  public final Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    Object actualResult = null;
    Throwable actualException = null;
    try {
      actualResult = method.invoke(actualImplementation, args);
    } catch (InvocationTargetException e) {
      actualException = e.getCause();
    }

    Object secondResult = null;
    Throwable secondException = null;
    try {
      secondResult = method.invoke(secondImplementation, args);
    } catch (InvocationTargetException e) {
      secondException = e.getCause();
    }

    // First compare the two implementations' result, and log any differences:
    if (actualException != null && secondException != null) {
      if (!compareThrown(method, actualException, secondException)) {
        log(
            method,
            String.format(
                "Both implementations threw, but got different exceptions! '%s' vs '%s'",
                stringifyThrown(method, actualException),
                stringifyThrown(method, secondException)));
      }
    } else if (actualException != null) {
      log(
          method,
          String.format(
              "Only actual implementation threw exception: %s",
              stringifyThrown(method, actualException)));
    } else if (secondException != null) {
      log(
          method,
          String.format(
              "Only second implementation threw exception: %s",
              stringifyThrown(method, secondException)));
    } else {
      // Neither threw exceptions - we compare the results
      if (!compareResults(method, actualResult, secondResult)) {
        log(
            method,
            String.format(
                "Got different results! '%s' vs '%s'",
                stringifyResult(method, actualResult),
                stringifyResult(method, secondResult)));
      }
    }

    // Now reproduce the actual implementation's behavior:
    if (actualException != null) {
      throw actualException;
    }
    return actualResult;
  }
}
