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

package google.registry.export;

import static com.google.common.collect.ImmutableSortedSet.toImmutableSortedSet;
import static google.registry.util.TypeUtils.hasAnnotation;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Ordering;
import com.googlecode.objectify.Key;
import google.registry.model.EntityClasses;
import google.registry.model.annotations.DeleteAfterMigration;
import google.registry.model.annotations.InCrossTld;
import google.registry.model.annotations.NotBackedUp;
import google.registry.model.annotations.ReportedOn;
import google.registry.model.annotations.VirtualEntity;

/** Constants related to export code. */
@DeleteAfterMigration
public final class AnnotatedEntities {

  /** Returns the names of kinds to include in Datastore backups. */
  public static ImmutableSet<String> getBackupKinds() {
    // Back up all entity classes that aren't annotated with @VirtualEntity (never even persisted
    // to Datastore, so they can't be backed up) or @NotBackedUp (intentionally omitted).
    return EntityClasses.ALL_CLASSES
        .stream()
        .filter(hasAnnotation(VirtualEntity.class).negate())
        .filter(hasAnnotation(NotBackedUp.class).negate())
        .map(Key::getKind)
        .collect(toImmutableSortedSet(Ordering.natural()));
  }

  /** Returns the names of kinds to import into reporting tools (e.g. BigQuery). */
  public static ImmutableSet<String> getReportingKinds() {
    return EntityClasses.ALL_CLASSES
        .stream()
        .filter(hasAnnotation(ReportedOn.class))
        .filter(hasAnnotation(VirtualEntity.class).negate())
        .map(Key::getKind)
        .collect(toImmutableSortedSet(Ordering.natural()));
  }

  /** Returns the names of kinds that are in the cross-TLD entity group. */
  public static ImmutableSet<String> getCrossTldKinds() {
    return EntityClasses.ALL_CLASSES.stream()
        .filter(hasAnnotation(InCrossTld.class))
        .filter(hasAnnotation(VirtualEntity.class).negate())
        .map(Key::getKind)
        .collect(toImmutableSortedSet(Ordering.natural()));
  }
}
