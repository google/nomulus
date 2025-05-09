// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

import static google.registry.model.tld.Tlds.assertTldsExist;
import static google.registry.persistence.transaction.QueryComposer.Comparator;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import google.registry.model.domain.Domain;
import google.registry.util.Clock;
import jakarta.inject.Inject;
import java.util.List;
import org.joda.time.DateTime;

/** Command to show the count of active domains on a given TLD. */
@Parameters(separators = " =", commandDescription = "Show count of domains on TLD")
final class CountDomainsCommand implements Command {

  @Parameter(
      names = {"-t", "--tld", "--tlds"},
      description = "Comma-delimited list of TLD(s) to count domains on",
      required = true)
  private List<String> tlds;

  @Inject Clock clock;

  @Override
  public void run() {
    DateTime now = clock.nowUtc();
    assertTldsExist(tlds)
        .forEach(tld -> System.out.printf("%s,%d\n", tld, getCountForTld(tld, now)));
  }

  private long getCountForTld(String tld, DateTime now) {
    return tm().transact(
            () ->
                tm().createQueryComposer(Domain.class)
                    .where("tld", Comparator.EQ, tld)
                    .where("deletionTime", Comparator.GT, now)
                    .count());
  }
}
