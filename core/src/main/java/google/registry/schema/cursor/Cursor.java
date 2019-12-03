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
import google.registry.util.DateTimeUtils;
import java.io.Serializable;
import java.time.ZonedDateTime;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.Table;
import org.joda.time.DateTime;

/**
 * Shared entity for date cursors. This uses a compound primary key as defined in {@link CursorId}.
 */
@Entity
@Table
@IdClass(CursorId.class)
public class Cursor {

  public enum CursorType {
    BRDA,
    RDE_REPORT,
    RDE_STAGING,
    RDE_UPLOAD,
    RDE_UPLOAD_SFTP,
    RECURRING_BILLING,
    SYNC_REGISTRAR_SHEET,
    ICANN_UPLOAD_TX,
    ICANN_UPLOAD_ACTIVITY,
    ICANN_UPLOAD_MANIFEST
  }

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  @Id
  private CursorType type;

  @Column @Id private String scope;

  @Column(nullable = false)
  private ZonedDateTime cursorTime;

  @Column(nullable = false)
  private UpdateAutoTimestamp lastUpdateTime = UpdateAutoTimestamp.create(null);

  /** The scope of a global cursor. A global cursor is a cursor that is not specific to one tld. */
  public static final String GLOBAL = "GLOBAL";

  private Cursor(CursorType type, String scope, DateTime cursorTime) {
    this.type = type;
    this.scope = scope;
    this.cursorTime = DateTimeUtils.toZonedDateTime(cursorTime);
  }

  // Hibernate requires a default constructor.
  private Cursor() {}

  /**
   * Constructs a {@link Cursor} object. Since hibernate does not allow null values in a primary
   * key, use {@link GLOBAL} for a null scope.
   */
  public static Cursor create(CursorType type, String scope, DateTime cursorTime) {
    return new Cursor(type, (scope == null ? GLOBAL : scope), cursorTime);
  }

  /** Constructs a {@link Cursor} object with a {@link GLOBAL} scope. */
  public static Cursor createGlobal(CursorType type, DateTime cursorTime) {
    return new Cursor(type, GLOBAL, cursorTime);
  }

  /** Returns the type of the cursor. */
  public CursorType getType() {
    return type;
  }

  /**
   * Returns the scope of the cursor. The scope will typically be the tld the cursor is referring
   * to. If the cursor is a global cursor, the scope will be {@link GLOBAL}.
   */
  public String getScope() {
    return scope;
  }

  /** Returns the time the cursor is set to. */
  public DateTime getCursorTime() {
    return DateTimeUtils.toJodaDateTime(cursorTime);
  }

  /** Returns the last time the cursor was updated. */
  public DateTime getLastUpdateTime() {
    return lastUpdateTime.getTimestamp();
  }

  static class CursorId extends ImmutableObject implements Serializable {

    public CursorType type;

    public String scope;

    private CursorId() {}

    public CursorId(CursorType type, String scope) {
      this.type = type;
      this.scope = scope;
    }
  }
}
