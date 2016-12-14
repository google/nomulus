// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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

package google.registry.export;

import static com.google.common.base.Predicates.not;
import static google.registry.model.EntityClasses.CLASS_TO_KIND_FUNCTION;
import static google.registry.util.TypeUtils.hasAnnotation;

import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Ordering;
import google.registry.model.EntityClasses;
import google.registry.model.annotations.NotBackedUp;
import google.registry.model.annotations.ReportedOn;
import google.registry.model.annotations.VirtualEntity;

/** Constants related to export code. */
public final class ExportConstants {

  /** Returns the names of kinds to include in datastore backups. */
  public static ImmutableSet<String> getBackupKinds() {
    // Back up all entity classes that aren't annotated with @VirtualEntity (never even persisted
    // to datastore, so they can't be backed up) or @NotBackedUp (intentionally omitted).
    return FluentIterable.from(EntityClasses.ALL_CLASSES)
        .filter(not(hasAnnotation(VirtualEntity.class)))
        .filter(not(hasAnnotation(NotBackedUp.class)))
        .transform(CLASS_TO_KIND_FUNCTION)
        .toSortedSet(Ordering.natural());
  }

  /** Returns the names of kinds to import into reporting tools (e.g. BigQuery). */
  public static ImmutableSet<String> getReportingKinds() {
    return FluentIterable.from(EntityClasses.ALL_CLASSES)
        .filter(hasAnnotation(ReportedOn.class))
        .transform(CLASS_TO_KIND_FUNCTION)
        .toSortedSet(Ordering.natural());
  }
}
