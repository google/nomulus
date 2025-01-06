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

package google.registry.model.common;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import google.registry.model.ImmutableObject;
import google.registry.model.UnsafeSerializable;
import google.registry.model.UpdateAutoTimestampEntity;
import google.registry.model.common.Cursor.CursorId;
import google.registry.model.tld.Tld;
import google.registry.persistence.VKey;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import java.util.Optional;
import org.joda.time.DateTime;

/**
 * Shared entity for date cursors.
 *
 * <p>This type supports both "scoped" cursors (i.e. one per TLD) and global (i.e. one per
 * environment) cursors.
 */
@Entity
@IdClass(CursorId.class)
@AttributeOverride(
    name = "updateTimestamp.lastUpdateTime",
    column = @Column(nullable = false, name = "lastUpdateTime"))
public class Cursor extends UpdateAutoTimestampEntity {

  private static final long serialVersionUID = 5777891565780594961L;

  /** The scope of a global cursor. A global cursor is a cursor that is not specific to one tld. */
  public static final String GLOBAL = "GLOBAL";

  /** The types of cursors, used as the string id field for each cursor in the database. */
  public enum CursorType {
    /** Cursor for ensuring rolling transactional isolation of BRDA staging operation. */
    BRDA(true),

    /** Cursor for ensuring rolling transactional isolation of RDE report operation. */
    RDE_REPORT(true),

    /** Cursor for ensuring rolling transactional isolation of RDE staging operation. */
    RDE_STAGING(true),

    /** Cursor for ensuring rolling transactional isolation of RDE upload operation. */
    RDE_UPLOAD(true),

    /**
     * Cursor that tracks the last time we talked to the escrow provider's SFTP server for a given
     * TLD.
     *
     * <p>Our escrow provider has an odd feature where separate deposits uploaded within two hours
     * of each other will be merged into a single deposit. This is problematic in situations where
     * the cursor might be a few days behind and is trying to catch up.
     *
     * <p>The way we solve this problem is by having {@code RdeUploadAction} check this cursor
     * before performing an upload for a given TLD. If the cursor is less than two hours old, the
     * action will fail with a status code above 300 and App Engine will keep retrying the action
     * until it's ready.
     */
    RDE_UPLOAD_SFTP(true),

    /**
     * Cursor for ensuring rolling transactional isolation of recurring billing expansion. The value
     * of this cursor represents the exclusive upper bound on the range of billing times for which
     * Recurring billing events have been expanded (i.e. the inclusive first billing time for the
     * next expansion job).
     */
    RECURRING_BILLING(false),

    /**
     * Cursor for {@link google.registry.export.sheet.SyncRegistrarsSheetAction}. The DateTime
     * stored is the last time that registrar changes were successfully synced to the sheet. If
     * there were no changes since the last time the action run, the cursor is not updated.
     */
    SYNC_REGISTRAR_SHEET(false),

    /** Cursor for tracking monthly uploads of ICANN transaction reports. */
    ICANN_UPLOAD_TX(true),

    /** Cursor for tracking monthly uploads of ICANN activity reports. */
    ICANN_UPLOAD_ACTIVITY(true);

    private final boolean scoped;

    CursorType(boolean scoped) {
      this.scoped = scoped;
    }

    public boolean isScoped() {
      return scoped;
    }
  }

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  @Id
  CursorType type;

  @Column(nullable = false)
  @Id
  String scope;

  @Column(nullable = false)
  DateTime cursorTime = START_OF_TIME;

  @Override
  public VKey<Cursor> createVKey() {
    return createVKey(type, scope);
  }

  public static VKey<Cursor> createGlobalVKey(CursorType type) {
    return createVKey(type, GLOBAL);
  }

  public static VKey<Cursor> createScopedVKey(CursorType type, Tld tld) {
    return createVKey(type, tld.getTldStr());
  }

  private static VKey<Cursor> createVKey(CursorType type, String scope) {
    checkValidCursorTypeForScope(type, scope);
    return VKey.create(Cursor.class, new CursorId(type, scope));
  }

  public DateTime getLastUpdateTime() {
    return getUpdateTimestamp().getTimestamp();
  }


  public String getScope() {
    return scope;
  }

  public CursorType getType() {
    return type;
  }

  /** Checks that the specified scope matches the required type for the specified cursor. */
  private static void checkValidCursorTypeForScope(CursorType cursorType, String scope) {
    checkNotNull(cursorType, "Cursor type cannot be null");
    checkNotNull(scope, "Cursor scope cannot be null");
    checkArgument(
        cursorType.isScoped() != scope.equals(GLOBAL),
        "Scope %s does not match cursor type %s",
        scope,
        cursorType);
  }

  /** Creates a new global cursor instance. */
  public static Cursor createGlobal(CursorType cursorType, DateTime cursorTime) {
    return create(cursorType, cursorTime, GLOBAL);
  }

  /** Creates a new cursor instance with a given {@link Tld} scope. */
  public static Cursor createScoped(CursorType cursorType, DateTime cursorTime, Tld scope) {
    checkNotNull(scope, "Cursor scope cannot be null");
    return create(cursorType, cursorTime, scope.getTldStr());
  }

  /**
   * Creates a new cursor instance with a given TLD scope, or global if the scope is {@link
   * #GLOBAL}.
   */
  private static Cursor create(CursorType cursorType, DateTime cursorTime, String scope) {
    checkNotNull(cursorTime, "Cursor time cannot be null");
    checkValidCursorTypeForScope(cursorType, scope);
    Cursor instance = new Cursor();
    instance.cursorTime = checkNotNull(cursorTime, "Cursor time cannot be null");
    instance.type = cursorType;
    instance.scope = scope;
    return instance;
  }

  /**
   * Returns the current time for a given cursor, or {@code START_OF_TIME} if the cursor is null.
   */
  public static DateTime getCursorTimeOrStartOfTime(Optional<Cursor> cursor) {
    return cursor.map(Cursor::getCursorTime).orElse(START_OF_TIME);
  }

  public DateTime getCursorTime() {
    return cursorTime;
  }

  public static class CursorId extends ImmutableObject implements UnsafeSerializable {

    private static final long serialVersionUID = -6749584762913095437L;

    public CursorType type;
    public String scope;

    // Hibernate requires a no-arg constructor.
    @SuppressWarnings("unused")
    private CursorId() {}

    public CursorId(CursorType type, String scope) {
      this.type = type;
      this.scope = scope;
    }
  }
}
