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

package google.registry.model.tld.label;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.collect.Ordering;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Enum describing reservation on a label in a {@link ReservedList}.
 *
 * <p>Note that superusers can override reservations and register a domain no matter what.
 */
public enum ReservationType {

  // We explicitly set the severity, even though we have a checkState that makes it equal to the
  // ordinal, so that no one accidentally reorders these values and changes the sort order. If a
  // label has multiple reservation types, its message is the that of the one with the highest
  // severity.

  /** The domain can only be registered during sunrise, and is reserved thereafter. */
  ALLOWED_IN_SUNRISE("Reserved", 0),

  /** The domain can only be registered by providing a specific token. */
  RESERVED_FOR_SPECIFIC_USE("Reserved; alloc. token required", 1),

  /** The domain is for an anchor tenant and can only be registered using a specific token. */
  RESERVED_FOR_ANCHOR_TENANT("Reserved; alloc. token required", 2),

  /**
   * The domain can only be registered during sunrise for defensive purposes, and will never
   * resolve.
   */
  NAME_COLLISION("Cannot be delegated", 3),

  /** The domain can never be registered. */
  FULLY_BLOCKED("Reserved", 4);

  @Nullable
  private final String messageForCheck;

  ReservationType(@Nullable String messageForCheck, int severity) {
    this.messageForCheck = messageForCheck;
    checkState(
        ordinal() == severity,
        "ReservationType enum values aren't defined in severity order");
  }

  @Nullable
  public String getMessageForCheck() {
    return messageForCheck;
  }

  private static final Ordering<ReservationType> ORDERING = new Ordering<ReservationType>() {
    @Override
    public int compare(ReservationType left, ReservationType right) {
      return Integer.compare(left.ordinal(), right.ordinal());
    }
  };

  /**
   * Returns the {@code ReservationType} with the highest severity, used when a label has multiple
   * reservation types and a reservation message is needed.
   *
   * @param types the set of reservation types that a label is associated with.
   * @return the reservation type with the highest severity.
   */
  public static ReservationType getTypeOfHighestSeverity(Set<ReservationType> types) {
    checkArgumentNotNull(types, "types must not be null");
    checkArgument(!types.isEmpty(), "types must not be empty");
    return ORDERING.max(types);
  }
}
