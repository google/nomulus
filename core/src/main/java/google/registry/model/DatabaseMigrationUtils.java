// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryEnvironment;
import google.registry.model.common.DatabaseTransitionSchedule;
import google.registry.model.common.DatabaseTransitionSchedule.PrimaryDatabase;
import google.registry.model.common.DatabaseTransitionSchedule.TransitionId;

/** Utility methods related to migrating dual-read/dual-write entities. */
public class DatabaseMigrationUtils {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** Throws exceptions only in unit tests, otherwise only logs exceptions. */
  public static void suppressExceptionUnlessInTest(Runnable work, String message) {
    try {
      work.run();
    } catch (Exception e) {
      if (RegistryEnvironment.get().equals(RegistryEnvironment.UNITTEST)) {
        throw e;
      }
      logger.atWarning().withCause(e).log(message);
    }
  }

  /** Gets the value for the database currently considered primary. */
  public static PrimaryDatabase getPrimaryDatabase(TransitionId transitionId) {
    return DatabaseTransitionSchedule.getCached(transitionId)
        .map(DatabaseTransitionSchedule::getPrimaryDatabase)
        .orElse(PrimaryDatabase.DATASTORE);
  }

  private DatabaseMigrationUtils() {}
}
