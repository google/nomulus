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

import com.google.common.base.Joiner;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.registry.Registry;
import google.registry.model.registry.label.ReservedList;
import google.registry.model.registry.label.ReservedList.ReservedListEntry;
import google.registry.model.registry.label.ReservedListDao;
import java.util.Set;
import java.util.TreeSet;
import javax.inject.Inject;

/** Container class for exported-related utility methods. */
public final class ExportUtils {

  private final String reservedTermsExportDisclaimer;

  @Inject
  public ExportUtils(
      @Config("reservedTermsExportDisclaimer") String reservedTermsExportDisclaimer) {
    this.reservedTermsExportDisclaimer = reservedTermsExportDisclaimer;
  }

  /** Returns the file contents of the auto-export reserved terms document for the given TLD. */
  public String exportReservedTerms(Registry registry) {
    StringBuilder termsBuilder = new StringBuilder(reservedTermsExportDisclaimer).append("\n");
    Set<String> reservedTerms = new TreeSet<>();
    for (String reservedListName : registry.getReservedListNames()) {
      ReservedList reservedList =
          ReservedListDao.getLatestRevision(reservedListName)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          String.format("Reserved list %s does not exist", reservedListName)));
      if (reservedList.getShouldPublish()) {
        for (ReservedListEntry entry : reservedList.getReservedListEntries().values()) {
          reservedTerms.add(entry.getDomainLabel());
        }
      }
    }
    Joiner.on("\n").appendTo(termsBuilder, reservedTerms);
    return termsBuilder.append("\n").toString();
  }
}
