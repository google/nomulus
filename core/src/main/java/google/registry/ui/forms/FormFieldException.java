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

package google.registry.ui.forms;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.Lists;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import javax.annotation.CheckReturnValue;
import javax.annotation.Detainted;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Exception thrown when a form field contains a bad value.
 *
 * <p>You can safely throw {@code FormFieldException} from within your validator functions, and the
 * field name will automatically be propagated into the exception object for you.
 *
 * <p>The way that field names work is a bit complicated, because we need to support complex nested
 * field names like {@code foo[3].bar}. So what happens is the original exception will be thrown by
 * a {@link FormField} validator without the field set. Then as the exception bubbles up the stack,
 * it'll be caught by the {@link FormField#convert(Object) convert} method, which then prepends the
 * name of that component. Then when the exception reaches the user, the {@link #getFieldName()}
 * method will produce the fully-qualified field name.
 *
 * <p>This propagation mechanism is also very important when writing {@link
 * FormField.Builder#transform} functions, which oftentimes will not know the name of the field
 * they're validating.
 */
@NotThreadSafe
@SuppressWarnings("OverrideThrowableToString")
public final class FormFieldException extends FormException {

  private final List<Object> names = new ArrayList<>();

  @Nullable
  private String lazyFieldName;

  /**
   * Creates a new {@link FormFieldException}
   *
   * <p>This exception should only be thrown from within a {@link FormField} converter function.
   * The field name will automatically be propagated into the exception object for you.
   *
   * @param userMessage should be a friendly message that's safe to show to the user.
   */
  public FormFieldException(@Detainted String userMessage) {
    super(checkNotNull(userMessage, "userMessage"), null);
  }

  /**
   * Creates a new {@link FormFieldException}
   *
   * <p>This exception should only be thrown from within a {@link FormField} converter function.
   * The field name will automatically be propagated into the exception object for you.
   *
   * @param userMessage should be a friendly message that's safe to show to the user.
   * @param cause the original cause of this exception (non-null).
   */
  public FormFieldException(@Detainted String userMessage, Throwable cause) {
    super(checkNotNull(userMessage, "userMessage"), checkNotNull(cause, "cause"));
  }

  /**
   * Creates a new {@link FormFieldException} for a particular form field.
   *
   * <p>This exception should only be thrown from within a {@link FormField} MAP converter function
   * in situations where you're performing additional manual validation.
   *
   * @param userMessage should be a friendly message that's safe to show to the user.
   */
  public FormFieldException(FormField<?, ?> field, @Detainted String userMessage) {
    this(field.name(), userMessage);
  }

  /**
   * Creates a new {@link FormFieldException} for a particular field name.
   *
   * @param field name corresponding to a {@link FormField#name()}
   * @param userMessage friendly message that's safe to show to the user
   */
  public FormFieldException(String field, @Detainted String userMessage) {
    super(checkNotNull(userMessage, "userMessage"), null);
    propagateImpl(field);
  }

  /** Returns the fully-qualified name (JavaScript syntax) of the form field causing this error. */
  public String getFieldName() {
    String fieldName = lazyFieldName;
    if (fieldName == null) {
      lazyFieldName = fieldName = getFieldNameImpl();
    }
    return fieldName;
  }

  private String getFieldNameImpl() {
    checkState(!names.isEmpty(),
        "FormFieldException was thrown outside FormField infrastructure!");
    Iterator<Object> namesIterator = Lists.reverse(names).iterator();
    StringBuilder result = new StringBuilder((String) namesIterator.next());
    while (namesIterator.hasNext()) {
      Object name = namesIterator.next();
      if (name instanceof String) {
        result.append('.').append(name);
      } else if (name instanceof Integer) {
        result.append('[').append(name).append(']');
      } else {
        throw new AssertionError();
      }
    }
    return result.toString();
  }

  /**
   * Returns self with {@code name} prepended, for propagating exceptions up the stack.
   *
   * <p>This would be package-private except that it needs to be called by a test class in another
   * package.
   */
  @CheckReturnValue
  public FormFieldException propagate(String name) {
    return propagateImpl(name);
  }

  /** Returns self with {@code index} prepended, for propagating exceptions up the stack. */
  @CheckReturnValue
  FormFieldException propagate(int index) {
    return propagateImpl(index);
  }

  /** Returns self with {@code name} prepended, for propagating exceptions up the stack. */
  private FormFieldException propagateImpl(Object name) {
    lazyFieldName = null;
    names.add(checkNotNull(name));
    return this;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    return this == obj
        || (obj instanceof FormFieldException
            && Objects.equals(getCause(), ((FormFieldException) obj).getCause())
            && Objects.equals(getMessage(), ((FormFieldException) obj).getMessage())
            && Objects.equals(names, ((FormFieldException) obj).names));
  }

  @Override
  public int hashCode() {
    return Objects.hash(getCause(), getMessage(), getFieldName());
  }

  @Override
  public String toString() {
    return toStringHelper(getClass())
        .add("fieldName", getFieldName())
        .add("message", getMessage())
        .add("cause", getCause())
        .toString();
  }
}
