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

package google.registry.model.common;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Parent;
import google.registry.model.ImmutableObject;
import google.registry.model.UpdateAutoTimestamp;
import google.registry.model.registry.Registry;
import org.joda.time.DateTime;

/**
 * Shared entity for date cursors. This type supports both "scoped" cursors (i.e. per resource
 * of a given type, such as a TLD) and global (i.e. one per environment) cursors, defined internally
 * as scoped on {@link EntityGroupRoot}.
 */
@Entity
public class Cursor extends ImmutableObject {

  /** The types of cursors, used as the string id field for each cursor in Datastore. */
  public enum CursorType {
    /** Cursor for ensuring rolling transactional isolation of BRDA staging operation. */
    BRDA(Registry.class),

    /** Cursor for ensuring rolling transactional isolation of RDE report operation. */
    RDE_REPORT(Registry.class),

    /** Cursor for ensuring rolling transactional isolation of RDE staging operation. */
    RDE_STAGING(Registry.class),

    /** Cursor for ensuring rolling transactional isolation of RDE upload operation. */
    RDE_UPLOAD(Registry.class),

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
    RDE_UPLOAD_SFTP(Registry.class),

    /**
     * Cursor for ensuring rolling transactional isolation of recurring billing expansion. The
     * value of this cursor represents the exclusive upper bound on the range of billing times
     * for which Recurring billing events have been expanded (i.e. the inclusive first billing time
     * for the next expansion job).
     */
    RECURRING_BILLING(EntityGroupRoot.class),

    /**
     * Cursor for {@link google.registry.export.sheet.SyncRegistrarsSheetAction}. The DateTime
     * stored is the last time that registrar changes were successfully synced to the sheet. If
     * there were no changes since the last time the action run, the cursor is not updated.
     */
    SYNC_REGISTRAR_SHEET(EntityGroupRoot.class),

    /** Cursor for tracking monthly uploads of ICANN transaction reports. */
    ICANN_UPLOAD_TX(Registry.class),

    /** Cursor for tracking monthly uploads of ICANN activity reports. */
    ICANN_UPLOAD_ACTIVITY(Registry.class),

    /** Cursor for tracking monthly upload of MANIFEST.txt to ICANN. */
    ICANN_UPLOAD_MANIFEST(EntityGroupRoot.class);

    /** See the definition of scope on {@link #getScopeClass}. */
    private final Class<? extends ImmutableObject> scope;

    CursorType(Class<? extends ImmutableObject> scope) {
      this.scope = scope;
    }

    /**
     * If there are multiple cursors for a given cursor type, a cursor must also have a scope
     * defined (distinct from a parent, which is always the EntityGroupRoot key). For instance,
     * for a cursor that is defined at the registry level, the scope type will be Registry.class.
     * For a cursor (theoretically) defined for each EPP resource, the scope type will be
     * EppResource.class. For a global cursor, i.e. one that applies per environment, this will be
     * {@link EntityGroupRoot}.
     */
    public Class<?> getScopeClass() {
      return scope;
    }
  }

  @Parent
  Key<EntityGroupRoot> parent = getCrossTldKey();

  @Id
  String id;

  DateTime cursorTime = START_OF_TIME;

  /** An automatically managed timestamp of when this object was last written to Datastore. */
  UpdateAutoTimestamp lastUpdateTime = UpdateAutoTimestamp.create(null);

  public DateTime getLastUpdateTime() {
    return lastUpdateTime.getTimestamp();
  }

  /**
   * Checks that the type of the scoped object (or null) matches the required type for the specified
   * cursor (or null, if the cursor is a global cursor).
   */
  private static void checkValidCursorTypeForScope(
      CursorType cursorType, Key<? extends ImmutableObject> scope) {
    checkArgument(
        cursorType.getScopeClass().equals(
            scope.equals(EntityGroupRoot.getCrossTldKey())
                ? EntityGroupRoot.class
                : ofy().factory().getMetadata(scope).getEntityClass()),
        "Class required for cursor does not match scope class");
  }

  /** Generates a unique ID for a given scope key and cursor type. */
  private static String generateId(CursorType cursorType, Key<? extends ImmutableObject> scope) {
    return String.format("%s_%s", scope.getString(), cursorType.name());
  }

  /** Creates a unique key for a given scope and cursor type. */
  public static Key<Cursor> createKey(CursorType cursorType, ImmutableObject scope) {
    Key<? extends ImmutableObject> scopeKey = Key.create(scope);
    checkValidCursorTypeForScope(cursorType, scopeKey);
    return Key.create(getCrossTldKey(), Cursor.class, generateId(cursorType, scopeKey));
  }

  /** Creates a unique key for a given global cursor type. */
  public static Key<Cursor> createGlobalKey(CursorType cursorType) {
    checkArgument(
        cursorType.getScopeClass().equals(EntityGroupRoot.class),
        "Cursor type is not a global cursor.");
    return Key.create(
        getCrossTldKey(), Cursor.class, generateId(cursorType, EntityGroupRoot.getCrossTldKey()));
  }

  /** Creates a new global cursor instance. */
  public static Cursor createGlobal(CursorType cursorType, DateTime cursorTime) {
    return create(cursorType, cursorTime, EntityGroupRoot.getCrossTldKey());
  }

  /** Creates a new cursor instance with a given {@link Key} scope. */
  private static Cursor create(
      CursorType cursorType, DateTime cursorTime, Key<? extends ImmutableObject> scope) {
    Cursor instance = new Cursor();
    instance.cursorTime = checkNotNull(cursorTime, "Cursor time cannot be null");
    checkNotNull(scope, "Cursor scope cannot be null");
    checkNotNull(cursorType, "Cursor type cannot be null");
    checkValidCursorTypeForScope(cursorType, scope);
    instance.id = generateId(cursorType, scope);
    return instance;
   }

  /** Creates a new cursor instance with a given {@link ImmutableObject} scope. */
  public static Cursor create(CursorType cursorType, DateTime cursorTime, ImmutableObject scope) {
    checkNotNull(scope, "Cursor scope cannot be null");
    return create(cursorType, cursorTime, Key.create(scope));
  }

  /**
   * Returns the current time for a given cursor, or {@code START_OF_TIME} if the cursor is null.
   */
  public static DateTime getCursorTimeOrStartOfTime(Cursor cursor) {
    return cursor != null ? cursor.getCursorTime() : START_OF_TIME;
  }

  public DateTime getCursorTime() {
    return cursorTime;
  }
}
