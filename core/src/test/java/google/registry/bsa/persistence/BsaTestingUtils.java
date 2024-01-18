// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

package google.registry.bsa.persistence;

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import google.registry.util.Clock;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/** Exposes BSA persistence entities and tools to test classes. */
public final class BsaTestingUtils {

  public static final Duration DEFAULT_DOWNLOAD_INTERVAL = Duration.standardHours(1);
  public static final Duration DEFAULT_NOP_INTERVAL = Duration.standardDays(1);

  /** An arbitrary point of time used as BsaLabels' creation time. */
  public static final DateTime BSA_LABEL_CREATION_TIME = DateTime.parse("2023-12-31T00:00:00Z");

  private BsaTestingUtils() {}

  public static void persistBsaLabel(String domainLabel) {
    tm().transact(() -> tm().put(new BsaLabel(domainLabel, BSA_LABEL_CREATION_TIME)));
  }

  public static DownloadScheduler createDownloadScheduler(Clock clock) {
    return new DownloadScheduler(DEFAULT_DOWNLOAD_INTERVAL, DEFAULT_NOP_INTERVAL, clock);
  }
}
