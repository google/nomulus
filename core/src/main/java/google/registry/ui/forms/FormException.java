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

import static com.google.common.base.Preconditions.checkNotNull;

import javax.annotation.Detainted;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Exception thrown when a form is invalid.  Problems with a specific
 * form field should use {@link FormFieldException} instead.
 *
 * <p>You can safely throw {@code FormException} from within your form
 * validator, and the message will automatically be propagated to the
 * client form interface.
 */
@NotThreadSafe
public class FormException extends RuntimeException {

  /**
   * Creates a new {@link FormException}
   *
   * @param userMessage should be a friendly message that's safe to show to the user.
   */
  public FormException(@Detainted String userMessage) {
    super(checkNotNull(userMessage, "userMessage"), null);
  }

  /**
   * Creates a new {@link FormException}
   *
   * @param userMessage should be a friendly message that's safe to show to the user.
   * @param cause the original cause of this exception.  May be null.
   */
  public FormException(@Detainted String userMessage, Throwable cause) {
    super(checkNotNull(userMessage, "userMessage"), cause);
  }

  /** Returns an error message that's safe to display to the user. */
  @Override
  @Detainted
  public String getMessage() {
    return super.getMessage();
  }
}
