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
import static google.registry.model.transaction.TransactionManagerFactory.jpaTm;

import google.registry.schema.cursor.Cursor.CursorId;
import google.registry.schema.cursor.Cursor.CursorType;
import java.util.List;

/** Data access object class for {@link Cursor}. */
public class CursorDao {

  public static void save(Cursor cursor) {
    jpaTm()
        .transact(
            () -> {
              jpaTm().getEntityManager().merge(cursor);
            });
  }

  public static Cursor load(CursorType type, String scope) {
    checkNotNull(scope, "The scope of the cursor to load cannot be null.");
    return jpaTm()
        .transact(() -> jpaTm().getEntityManager().find(Cursor.class, new CursorId(type, scope)));
  }

  /** If no scope is given, use {@link Cursor.GLOBAL} as the scope. */
  public static Cursor load(CursorType type) {
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
}
