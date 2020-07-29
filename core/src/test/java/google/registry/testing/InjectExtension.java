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

package google.registry.testing;

import static com.google.common.base.Preconditions.checkState;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit extension for overriding {@code private static} fields during a test.
 *
 * <p>This rule uses reflection to change the value of a field while your test is running and then
 * restore it to its original value after it's done (even if the test fails). The injection will
 * work even if the field is marked {@code private} (but not if it's {@code final}). The downside is
 * that if you rename the field in the future, IDE refactoring won't be smart enough to update the
 * injection site.
 *
 * <p>We encourage you to consider using {@link google.registry.util.NonFinalForTesting
 * &#064;NonFinalForTesting} to document your injected fields.
 *
 * <p>This class is a horrible evil hack, but it alleviates you of the toil of having to break
 * encapsulation by making your fields non-{@code private}, using the {@link
 * com.google.common.annotations.VisibleForTesting &#064;VisibleForTesting} annotation to document
 * why you've reduced visibility, creating a temporary field to store the old value, and then
 * writing an {@link org.junit.After &#064;After} method to restore it. So sometimes it feels good
 * to be evil; but hopefully one day we'll be able to delete this class and do things
 * <i>properly</i> with <a href="http://square.github.io/dagger/">Dagger</a> dependency injection.
 *
 * <p>You use this class in by declaring it as an {@link
 * org.junit.jupiter.api.extension.RegisterExtension &#064;RegisterExtension} field and then call
 * {@link #setStaticField} from either your {@link org.junit.jupiter.api.Test &#064;Test} or {@link
 * org.junit.jupiter.api.BeforeEach &#064;BeforeEach} methods. For example:
 *
 * <pre>
 * // Doomsday.java
 * public class Doomsday {
 *
 *   private static Clock clock = new SystemClock();
 *
 *   public long getTime() {
 *     return clock.currentTimeMillis();
 *   }
 * }
 *
 * // DoomsdayTest.java
 * public class DoomsdayTest {
 *
 *   &#064;RegisterExtension
 *   public InjectExtension inject = new InjectExtension();
 *
 *   private final FakeClock clock = new FakeClock();
 *
 *   &#064;BeforeEach
 *   public void beforeEach() {
 *     inject.setStaticField(Doomsday.class, "clock", clock);
 *   }
 *
 *   &#064;Test
 *   public void test() {
 *     clock.advanceBy(666L);
 *     Doomsday doom = new Doomsday();
 *     assertEquals(666L, doom.getTime());
 *   }
 * }
 * </pre>
 *
 * @see google.registry.util.NonFinalForTesting
 */
public class InjectExtension implements AfterEachCallback {

  private static class Change {
    private final Field field;
    @Nullable private final Object oldValue;
    @Nullable private final Object newValue;

    Change(Field field, @Nullable Object oldValue, @Nullable Object newValue) {
      this.field = field;
      this.oldValue = oldValue;
      this.newValue = newValue;
    }
  }

  private final List<Change> changes = new ArrayList<>();
  private final Set<Field> injected = new HashSet<>();

  /**
   * Sets a static field and be restores its current value after the test completes.
   *
   * <p>The field is allowed to be {@code private}, but it most not be {@code final}.
   *
   * <p>This method may be called either from either your {@link org.junit.Before @Before} method or
   * from the {@link org.junit.Test @Test} method itself. However you may not inject the same field
   * multiple times during the same test.
   *
   * @throws IllegalArgumentException if the static field could not be found or modified.
   * @throws IllegalStateException if the field has already been injected during this test.
   */
  public void setStaticField(Class<?> clazz, String fieldName, @Nullable Object newValue) {
    Field field;
    Object oldValue;
    try {
      field = clazz.getDeclaredField(fieldName);
      field.setAccessible(true);
      oldValue = field.get(null);
    } catch (NoSuchFieldException
        | SecurityException
        | IllegalArgumentException
        | IllegalAccessException e) {
      throw new IllegalArgumentException(
          String.format("Static field not found: %s.%s", clazz.getSimpleName(), fieldName), e);
    }
    checkState(
        !injected.contains(field),
        "Static field already injected: %s.%s",
        clazz.getSimpleName(),
        fieldName);
    try {
      field.set(null, newValue);
    } catch (IllegalArgumentException | IllegalAccessException e) {
      throw new IllegalArgumentException(
          String.format("Static field not settable: %s.%s", clazz.getSimpleName(), fieldName), e);
    }
    changes.add(new Change(field, oldValue, newValue));
    injected.add(field);
  }

  @Override
  public void afterEach(ExtensionContext context) {
    RuntimeException thrown = null;
    for (Change change : changes) {
      try {
        checkState(
            change.field.get(null).equals(change.newValue),
            "Static field value was changed post-injection: %s.%s",
            change.field.getDeclaringClass().getSimpleName(),
            change.field.getName());
        change.field.set(null, change.oldValue);
      } catch (IllegalArgumentException | IllegalStateException | IllegalAccessException e) {
        if (thrown == null) {
          thrown = new RuntimeException(e);
        } else {
          thrown.addSuppressed(e);
        }
      }
    }
    changes.clear();
    injected.clear();
    if (thrown != null) {
      throw thrown;
    }
  }
}
