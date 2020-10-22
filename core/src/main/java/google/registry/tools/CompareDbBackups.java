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

package google.registry.tools;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import java.io.File;

/**
 * Compares two Datastore backups in V3 format on local file system. This is for use in tests and
 * experiments with small data sizes.
 *
 * <p>This utility only supports the current Datastore backup format (version 3). A backup is a
 * two-level directory hierarchy with data files in level-db format (output-*) and Datastore
 * metadata files (*.export_metadata).
 */
class CompareDbBackups {
  private static final String DS_V3_BACKUP_FILE_PREFIX = "output-";

  private static boolean isDatastoreV3File(File file) {
    return file.isFile() && file.getName().startsWith(DS_V3_BACKUP_FILE_PREFIX);
  }

  public static void main(String[] args) {
    if (args.length != 2) {
      System.err.println("Usage: compare_db_backups <directory1> <directory2>");
      return;
    }

    ImmutableSet<EntityWrapper> entities1 =
        RecordAccumulator.readDirectory(new File(args[0]), CompareDbBackups::isDatastoreV3File)
            .getEntityWrapperSet();
    ImmutableSet<EntityWrapper> entities2 =
        RecordAccumulator.readDirectory(new File(args[1]), CompareDbBackups::isDatastoreV3File)
            .getEntityWrapperSet();

    // Calculate the entities added and removed.
    SetView<EntityWrapper> added = Sets.difference(entities2, entities1);
    SetView<EntityWrapper> removed = Sets.difference(entities1, entities2);

    printHeader(
        String.format("First backup: %d records", entities1.size()),
        String.format("Second backup: %d records", entities2.size()));

    if (!removed.isEmpty()) {
      printHeader(removed.size() + " records were removed:");
      for (EntityWrapper entity : removed) {
        System.out.println(entity);
      }
    }

    if (!added.isEmpty()) {
      printHeader(added.size() + " records were added:");
      for (EntityWrapper entity : added) {
        System.out.println(entity);
      }
    }

    if (added.isEmpty() && removed.isEmpty()) {
      System.out.printf("\nBoth sets have the same %d entities.\n", entities1.size());
    }
  }

  /** Print out multi-line text in a pretty ASCII header frame. */
  private static void printHeader(String... headerLines) {
    System.out.println("========================================================================");
    for (String line : headerLines) {
      System.out.println("| " + line);
    }
    System.out.println("========================================================================");
  }
}
