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

import static com.google.appengine.api.search.checkers.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import google.registry.model.common.Cursor.CursorType;
import google.registry.schema.cursor.Cursor.CursorId;
import java.util.List;
import javax.annotation.Nullable;

/** Data access object class for {@link Cursor}. */
public class CursorDao {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static void save(Cursor cursor) {
    jpaTm()
        .transact(
            () -> {
              jpaTm().getEntityManager().merge(cursor);
            });
  }

  public static void saveAll(ImmutableSet<Cursor> cursors) {
    jpaTm()
        .transact(
            () -> {
              for (Cursor cursor : cursors) {
                jpaTm().getEntityManager().merge(cursor);
              }
            });
  }

  public static Cursor load(CursorType type, String scope) {
    checkNotNull(scope, "The scope of the cursor to load cannot be null");
    checkNotNull(type, "The type of the cursor to load cannot be null");
    return jpaTm()
        .transact(() -> jpaTm().getEntityManager().find(Cursor.class, new CursorId(type, scope)));
  }

  /** If no scope is given, use {@link Cursor#GLOBAL} as the scope. */
  public static Cursor load(CursorType type) {
    checkNotNull(type, "The type of the cursor to load must be specified");
    return load(type, Cursor.GLOBAL);
  }

  public static List<Cursor> loadAll() {
    return jpaTm()
        .transact(
            () ->
                jpaTm()
                    .getEntityManager()
                    .createQuery("SELECT cursor FROM Cursor cursor", Cursor.class)
                    .getResultList());
  }

  public static List<Cursor> loadByType(CursorType type) {
    checkNotNull(type, "The type of the cursors to load must be specified");
    return jpaTm()
        .transact(
            () ->
                jpaTm()
                    .getEntityManager()
                    .createQuery(
                        "SELECT cursor FROM Cursor cursor WHERE cursor.type = :type", Cursor.class)
                    .setParameter("type", type)
                    .getResultList());
  }

  /**
   * This writes the given cursor to Datastore. If the save to Datastore succeeds, then a new
   * Schema/Cursor object is created and attempted to save to Cloud SQL. If the save to Cloud SQL
   * fails, the exception is logged, but does not cause the method to fail.
   */
  public static void saveCursor(google.registry.model.common.Cursor cursor, String scope) {
    tm().transact(() -> ofy().save().entity(cursor));
    CursorType type = cursor.getType();
    checkArgumentNotNull(scope, "The scope of the cursor cannot be null");
    Cursor cloudSqlCursor = Cursor.create(type, scope, cursor.getCursorTime());
    try {
      save(cloudSqlCursor);
      logger.atInfo().log(
          "Rolled forward CloudSQL cursor for %s to %s.", scope, cursor.getCursorTime());
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("Error saving cursor to Cloud SQL: %s.", cloudSqlCursor);
    }
  }

  /**
   * This takes in multiple cursors and saves them to Datastore. If those saves succeed, it attempts
   * to save the cursors to Cloud SQL. If the save to Cloud SQL fails, the exception is logged, but
   * does not cause the method to fail.
   */
  public static void saveCursors(
      ImmutableMap<google.registry.model.common.Cursor, String> cursors) {
    // Save the cursors to Datastore
    tm().transact(
            () -> {
              for (google.registry.model.common.Cursor cursor : cursors.keySet()) {
                ofy().save().entity(cursor);
              }
            });
    // Try to save the cursors to Cloud SQL
    try {
      ImmutableSet.Builder<Cursor> cloudSqlCursors = new ImmutableSet.Builder<>();
      cursors
          .keySet()
          .forEach(
              cursor ->
                  cloudSqlCursors.add(
                      Cursor.create(
                          cursor.getType(), cursors.get(cursor), cursor.getCursorTime())));
      saveAll(cloudSqlCursors.build());
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("Error saving cursor to Cloud SQL.");
    }
  }

  /**
   * Loads in cursor from Cloud SQL and compares it to the Datastore cursor
   *
   * <p>This takes in a cursor from Datastore and checks to see if it exists in Cloud SQL and has
   * the same value. If a difference is detected, or the Cloud SQL cursor does not exist, a warning
   * is logged.
   */
  public static void loadAndCompare(
      @Nullable google.registry.model.common.Cursor datastoreCursor, String scope) {
    if (datastoreCursor == null) {
      return;
    }
    try {
      // Load the corresponding cursor from Cloud SQL
      Cursor cloudSqlCursor = load(datastoreCursor.getType(), scope);
      compare(datastoreCursor, cloudSqlCursor, scope);
    } catch (Throwable t) {
      logger.atSevere().withCause(t).log("Error comparing cursors.");
    }
  }

  /**
   * Loads in all cursors of a given type from Cloud SQL and compares them to Datastore
   *
   * <p>This takes in cursors from Datastore and checks to see if they exists in Cloud SQL and have
   * the same value. If a difference is detected, or a Cloud SQL cursor does not exist, a warning is
   * logged.
   */
  public static void loadAndCompareAll(
      ImmutableMap<google.registry.model.common.Cursor, String> cursors, CursorType type) {
    try {
      // Load all the cursors of that type from Cloud SQL
      List<Cursor> cloudSqlCursors = loadByType(type);

      // Create a map of each tld to its cursor if one exists
      ImmutableMap<String, Cursor> cloudSqlCursorMap =
          cloudSqlCursors.stream().collect(toImmutableMap(c -> c.getScope(), c -> c));

      // Compare each Datastore cursor with its corresponding Cloud SQL cursor
      for (google.registry.model.common.Cursor cursor : cursors.keySet()) {
        Cursor cloudSqlCursor = cloudSqlCursorMap.get(cursors.get(cursor));
        compare(cursor, cloudSqlCursor, cursors.get(cursor));
      }
    } catch (Throwable t) {
      logger.atSevere().withCause(t).log("Error comparing cursors.");
    }
  }

  private static void compare(
      google.registry.model.common.Cursor datastoreCursor,
      @Nullable Cursor cloudSqlCursor,
      String scope) {
    if (cloudSqlCursor == null) {
      logger.atWarning().log(
          String.format(
              "Cursor of type %s with the scope %s was not found in Cloud SQL.",
              datastoreCursor.getType().name(), scope));
    } else if (!datastoreCursor.getCursorTime().equals(cloudSqlCursor.getCursorTime())) {
      logger.atWarning().log(
          String.format(
              "This cursor of type %s with the scope %s has a cursorTime of %s in Datastore and %s"
                  + " in Cloud SQL.",
              datastoreCursor.getType(),
              scope,
              datastoreCursor.getCursorTime(),
              cloudSqlCursor.getCursorTime()));
    }
  }
}
