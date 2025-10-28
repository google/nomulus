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

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import google.registry.model.ForeignKeyUtils;
import google.registry.model.domain.Domain;
import google.registry.persistence.transaction.QueryComposer.Comparator;
import google.registry.util.DomainNameUtils;
import java.util.List;
import java.util.Optional;

/** Command to show a domain resource. */
@Parameters(separators = " =", commandDescription = "Show domain resource(s)")
final class GetDomainCommand extends GetEppResourceCommand {

  @Parameter(names = "--show_deleted", description = "Include deleted domains in the print out")
  private boolean showDeleted = false;

  @Parameter(
      description = "Fully qualified domain name(s)",
      required = true)
  private List<String> mainParameters;

  @Override
  public void runAndPrint() {
    for (String domainName : mainParameters) {
      String canonicalDomain = DomainNameUtils.canonicalizeHostname(domainName);
      if (showDeleted) {
        tm().transact(
                () ->
                    tm()
                        .createQueryComposer(Domain.class)
                        .where("domainName", Comparator.EQ, canonicalDomain)
                        .orderBy("creationTime")
                        .stream()
                        .forEach(
                            d -> {
                              printResource("Domain", canonicalDomain, Optional.of(d));
                            }));
      } else {
        printResource(
            "Domain",
            canonicalDomain,
            ForeignKeyUtils.loadResource(Domain.class, canonicalDomain, readTimestamp));
      }
    }
  }
}
