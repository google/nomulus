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

package google.registry.model;

import google.registry.util.PreconditionsUtils;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.MappedSuperclass;
import jakarta.xml.bind.annotation.XmlTransient;

/**
 * Base class for entities that contains an {@link UpdateAutoTimestamp} which is updated every time
 * the entity is persisted.
 */
@MappedSuperclass
public abstract class UpdateAutoTimestampEntity extends ImmutableObject
    implements UnsafeSerializable {

  /**
   * An automatically managed timestamp of when this object was last written to the database.
   *
   * <p>Note that this is distinct from the EPP-specified {@link EppResource#lastEppUpdateTime}, in
   * that this is updated on every save, rather than only in response to an {@code <update>} command
   */
  @XmlTransient
  // Prevents subclasses from unexpectedly accessing as property (e.g., Host), which would
  // require an unnecessary non-private setter method.
  @Access(AccessType.FIELD)
  UpdateAutoTimestamp updateTimestamp = UpdateAutoTimestamp.create(null);

  /** Get the {@link UpdateAutoTimestamp} for this entity. */
  public UpdateAutoTimestamp getUpdateTimestamp() {
    return updateTimestamp;
  }

  /**
   * Copies {@link #updateTimestamp} from another entity.
   *
   * <p>This method is for the few cases when {@code updateTimestamp} is copied between different
   * types of entities. Use {@link #clone} for same-type copying.
   */
  protected void copyUpdateTimestamp(UpdateAutoTimestampEntity other) {
    this.updateTimestamp = PreconditionsUtils.checkArgumentNotNull(other, "other").updateTimestamp;
  }

  /**
   * Resets the {@link #updateTimestamp} to force Hibernate to persist it.
   *
   * <p>This method is for use in setters in derived builders that do not result in the derived
   * object being persisted.
   */
  protected void resetUpdateTimestamp() {
    this.updateTimestamp = UpdateAutoTimestamp.create(null);
  }

  /**
   * Sets the {@link #updateTimestamp}.
   *
   * <p>This method is for use in the few places where we need to restore the update timestamp after
   * mutating a collection in order to force the new timestamp to be persisted when it ordinarily
   * wouldn't.
   */
  protected void setUpdateTimestamp(UpdateAutoTimestamp timestamp) {
    updateTimestamp = timestamp;
  }
}
