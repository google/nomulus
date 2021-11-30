// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.model.reporting.HistoryEntry.Type.SYNTHETIC;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import java.util.Optional;

/**
 * Tool to enqueue a poll message for a registrar.
 *
 * <p>The poll message in question must correspond to an existing domain owing to schema
 * limitations, but does not necessarily need to correspond to the owner of that domain if an
 * alternative <code>clientId</code> is provided.
 *
 * <p>If general broadcast messages are being sent to registrars (e.g. a notice of an upcoming
 * maintenance window), then it is recommended to use a well-known registry-owned domain for the
 * enqueueing of these poll messages, such as the nic domain.
 */
@Parameters(separators = " =", commandDescription = "Enqueue a poll message for a domain")
class EnqueuePollMessageCommand extends MutatingCommand {

  @Parameter(
      names = {"-m", "--message"},
      description = "The poll message to enqueue",
      required = true)
  String message;

  @Parameter(
      names = {"-d", "--domain"},
      description = "The domain name to enqueue the poll message for",
      required = true)
  String domainName;

  @Parameter(
      names = {"-c", "--client"},
      description =
          "Client identifier of the registrar to send the poll message to, if not the owning"
              + " registrar of the domain")
  String clientId;

  @Override
  protected final void init() {
    tm().transact(
            () -> {
              Optional<DomainBase> domainOpt =
                  loadByForeignKey(DomainBase.class, domainName, tm().getTransactionTime());
              checkArgument(
                  domainOpt.isPresent(), "Domain %s doesn't exist or isn't active", domainName);
              DomainBase domain = domainOpt.get();
              String registrarId =
                  Optional.ofNullable(clientId).orElse(domain.getCurrentSponsorRegistrarId());
              HistoryEntry historyEntry =
                  new DomainHistory.Builder()
                      .setDomain(domain)
                      .setType(SYNTHETIC)
                      .setBySuperuser(true)
                      .setReason("Manual enqueueing of poll message")
                      .setModificationTime(tm().getTransactionTime())
                      .setRequestedByRegistrar(false)
                      .setRegistrarId(registrarId)
                      .build();
              PollMessage.OneTime pollMessage =
                  new PollMessage.OneTime.Builder()
                      .setRegistrarId(registrarId)
                      .setParent(historyEntry)
                      .setEventTime(tm().getTransactionTime())
                      .setMsg(message)
                      .build();
              stageEntityChange(null, historyEntry);
              stageEntityChange(null, pollMessage);
            });
  }
}
