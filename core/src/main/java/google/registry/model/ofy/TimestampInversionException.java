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

package google.registry.model.ofy;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.Objectify;
import google.registry.model.BackupGroupRoot;
import google.registry.model.annotations.DeleteAfterMigration;
import java.util.Arrays;
import java.util.Map;
import org.joda.time.DateTime;

/**
 * Exception when trying to write to Datastore with a timestamp that is inconsistent with a partial
 * ordering on transactions that touch the same entities.
 */
@DeleteAfterMigration
class TimestampInversionException extends RuntimeException {

  static String getFileAndLine(StackTraceElement callsite) {
    return callsite.getFileName() + ":" + callsite.getLineNumber();
  }

  TimestampInversionException(
      DateTime transactionTime, Map<Key<BackupGroupRoot>, DateTime> problematicRoots) {
    this(transactionTime, "entities rooted under:\n" + problematicRoots);
  }

  TimestampInversionException(DateTime transactionTime, DateTime updateTimestamp) {
    this(transactionTime, String.format("update timestamp (%s)", updateTimestamp));
  }

  private TimestampInversionException(DateTime transactionTime, String problem) {
    super(
        String.format(
            "Timestamp inversion between transaction time (%s) and %s\n%s",
            transactionTime,
            problem,
            getFileAndLine(
                Arrays.stream(new Exception().getStackTrace())
                    .filter(
                        element ->
                            !element
                                    .getClassName()
                                    .startsWith(Objectify.class.getPackage().getName())
                                && !element.getClassName().startsWith(Ofy.class.getName()))
                    .findFirst()
                    .get())));
  }
}
