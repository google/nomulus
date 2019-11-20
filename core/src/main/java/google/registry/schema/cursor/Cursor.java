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

package google.registry.schema.cursor;

import google.registry.model.ImmutableObject;
import google.registry.model.UpdateAutoTimestamp;
import google.registry.schema.cursor.Cursor.CursorId;
import java.io.Serializable;
import java.time.ZonedDateTime;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.Table;

/**
 * Shared entity for date cursors. This uses a compound primary key as defined in {@link CursorId}.
 */
@Entity
@Table
@IdClass(CursorId.class)
public class Cursor {

  @Column(nullable = false)
  @Id
  private String type;

  @Column @Id private String scope;

  @Column(nullable = false)
  private ZonedDateTime cursorTime;

  @Column(nullable = false)
  private UpdateAutoTimestamp lastUpdateTime = UpdateAutoTimestamp.create(null);

  public static final String GLOBAL = "GLOBAL";

  /**
   * Since hibernate does not allow null values in a primary key, for now I am just using "Global"
   * for the tld value on a global cursor.
   */
  private Cursor(String type, String scope, ZonedDateTime cursorTime) {
    this.type = type;
    this.scope = (scope == null ? GLOBAL : scope);
    this.cursorTime = cursorTime;
  }

  // Hibernate requires a default constructor.
  private Cursor() {}

  /** Constructs a {@link Cursor} object. */
  public static Cursor create(String type, String scope, ZonedDateTime cursorTime) {
    return new Cursor(type, scope, cursorTime);
  }

  /** Returns the type of the cursor. */
  public String getType() {
    return type;
  }

  /**
   * Returns the scope of the cursor. The scope will typically be the tld the cursor is referring
   * to. If the cursor is a global cursor, the scope will be "GLOBAL".
   */
  public String getScope() {
    return scope;
  }

  /** Returns the time the cursor is set to. */
  public ZonedDateTime getCursorTime() {
    return cursorTime;
  }

  /** Returns the last time the cursor was updated. */
  public UpdateAutoTimestamp getLastUpdateTime() {
    return lastUpdateTime;
  }

  static class CursorId extends ImmutableObject implements Serializable {

    public String type;

    public String scope;

    private CursorId() {}

    public CursorId(String type, String scope) {
      this.type = type;
      this.scope = scope;
    }
  }
}
