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

package google.registry.tools.server;

import static com.google.common.base.Predicates.not;
import static com.google.common.collect.Maps.toMap;
import static google.registry.flows.EppXmlTransformer.unmarshal;
import static google.registry.flows.picker.FlowPicker.getFlowClass;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.util.CollectionUtils.isNullOrEmpty;
import static google.registry.util.DomainNameUtils.ACE_PREFIX;

import com.google.common.base.Ascii;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multiset;
import google.registry.flows.EppException;
import google.registry.flows.Flow;
import google.registry.flows.contact.ContactCreateFlow;
import google.registry.flows.contact.ContactDeleteFlow;
import google.registry.flows.contact.ContactTransferApproveFlow;
import google.registry.flows.contact.ContactTransferCancelFlow;
import google.registry.flows.contact.ContactTransferRejectFlow;
import google.registry.flows.contact.ContactTransferRequestFlow;
import google.registry.flows.contact.ContactUpdateFlow;
import google.registry.flows.domain.DomainApplicationCreateFlow;
import google.registry.flows.domain.DomainApplicationDeleteFlow;
import google.registry.flows.domain.DomainApplicationUpdateFlow;
import google.registry.flows.domain.DomainCreateFlow;
import google.registry.flows.domain.DomainDeleteFlow;
import google.registry.flows.domain.DomainRenewFlow;
import google.registry.flows.domain.DomainRestoreRequestFlow;
import google.registry.flows.domain.DomainTransferApproveFlow;
import google.registry.flows.domain.DomainTransferCancelFlow;
import google.registry.flows.domain.DomainTransferRejectFlow;
import google.registry.flows.domain.DomainTransferRequestFlow;
import google.registry.flows.domain.DomainUpdateFlow;
import google.registry.flows.host.HostCreateFlow;
import google.registry.flows.host.HostDeleteFlow;
import google.registry.flows.host.HostUpdateFlow;
import google.registry.model.domain.DomainCommand;
import google.registry.model.domain.fee.FeeCreateCommandExtension;
import google.registry.model.domain.launch.LaunchCreateExtension;
import google.registry.model.domain.secdns.SecDnsCreateExtension;
import google.registry.model.domain.secdns.SecDnsUpdateExtension;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppinput.EppInput.ResourceCommandWrapper;
import google.registry.model.host.HostCommand;
import google.registry.model.reporting.HistoryEntry;
import google.registry.request.Action;
import google.registry.request.JsonActionRunner;
import google.registry.request.JsonActionRunner.JsonAction;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.inject.Inject;

/**
 * A servlet that verifies a registrar's OTE status. Note that this is eventually consistent, so
 * OT&amp;E commands that have been run just previously to verification may not be picked up yet.
 */
@Action(
    path = VerifyOteAction.PATH,
    method = Action.Method.POST,
    xsrfProtection = true,
    xsrfScope = "admin")
public class VerifyOteAction implements Runnable, JsonAction {

  public static final String PATH = "/_dr/admin/verifyOte";

  @Inject JsonActionRunner jsonActionRunner;
  @Inject VerifyOteAction() {}

  @Override
  public void run() {
    jsonActionRunner.run(this);
  }

  @Override
  @SuppressWarnings("unchecked")
  public Map<String, Object> handleJsonRequest(Map<String, ?> json) {
    final boolean summarize = Boolean.parseBoolean((String) json.get("summarize"));
    return toMap(
        (List<String>) json.get("registrars"),
        new Function<String, Object>() {
          @Nonnull
          @Override
          public Object apply(@Nonnull String registrar) {
            return checkRegistrar(registrar, summarize);
          }});
  }

  /** Checks whether the provided registrar has passed OT&amp;E and returns relevant information. */
  private String checkRegistrar(String registrarName, boolean summarize) {
    HistoryEntryStats historyEntryStats =
        new HistoryEntryStats().recordRegistrarHistory(registrarName);
    List<String> failureMessages = historyEntryStats.findFailures();
    String passedFraction = String.format(
        "%2d/%2d", StatType.NUM_REQUIREMENTS - failureMessages.size(), StatType.NUM_REQUIREMENTS);
    String status = failureMessages.isEmpty() ? "PASS" : "FAIL";
    return summarize
        ? String.format(
            "Num actions: %4d - Reqs passed: %s - Overall: %s",
            historyEntryStats.statCounts.size(),
            passedFraction,
            status)
        : String.format(
            "%s\n%s\nRequirements passed: %s\nOverall OT&E status: %s\n",
            historyEntryStats,
            Joiner.on('\n').join(failureMessages),
            passedFraction,
            status);
  }

  private static final Predicate<EppInput> HAS_CLAIMS_NOTICE = new Predicate<EppInput>() {
      @Override
      public boolean apply(@Nonnull EppInput eppInput) {
        LaunchCreateExtension launchCreate =
            eppInput.getSingleExtension(LaunchCreateExtension.class);
        return launchCreate != null && launchCreate.getNotice() != null;
      }};

  private static final Predicate<EppInput> HAS_FEE = new Predicate<EppInput>() {
      @Override
      public boolean apply(@Nonnull EppInput eppInput) {
        return eppInput.getSingleExtension(FeeCreateCommandExtension.class) != null;
      }};

  private static final Predicate<EppInput> HAS_SEC_DNS = new Predicate<EppInput>() {
      @Override
      public boolean apply(@Nonnull EppInput eppInput) {
        return (eppInput.getSingleExtension(SecDnsCreateExtension.class) != null)
            || (eppInput.getSingleExtension(SecDnsUpdateExtension.class) != null);
      }};

  private static final Predicate<EppInput> IS_SUNRISE = new Predicate<EppInput>() {
      @Override
      public boolean apply(@Nonnull EppInput eppInput) {
        LaunchCreateExtension launchCreate =
            eppInput.getSingleExtension(LaunchCreateExtension.class);
        return launchCreate != null && !isNullOrEmpty(launchCreate.getSignedMarks());
      }};

  private static final Predicate<EppInput> IS_IDN = new Predicate<EppInput>() {
      @Override
      public boolean apply(@Nonnull EppInput eppInput) {
        return ((DomainCommand.Create) ((ResourceCommandWrapper)
            eppInput.getCommandWrapper().getCommand()).getResourceCommand())
                .getFullyQualifiedDomainName().startsWith(ACE_PREFIX);
      }};

  private static final Predicate<EppInput> IS_SUBORDINATE = new Predicate<EppInput>() {
      @Override
      public boolean apply(@Nonnull EppInput eppInput) {
        return !isNullOrEmpty(((HostCommand.Create) ((ResourceCommandWrapper)
            eppInput.getCommandWrapper().getCommand()).getResourceCommand())
                .getInetAddresses());
      }};

  private static Predicate<EppInput> isFlow(final Class<? extends Flow> flowClass) {
    return new Predicate<EppInput>() {
      @Override
      public boolean apply(@Nonnull EppInput eppInput) {
        try {
          return flowClass.equals(getFlowClass(eppInput));
        } catch (EppException e) {
          throw new RuntimeException(e);
        }
      }};
  }

  /** Enum defining the distinct statistics (types of registrar actions) to record. */
  public enum StatType {
    CONTACT_CREATES(0, isFlow(ContactCreateFlow.class)),
    CONTACT_DELETES(0, isFlow(ContactDeleteFlow.class)),
    CONTACT_TRANSFER_APPROVES(0, isFlow(ContactTransferApproveFlow.class)),
    CONTACT_TRANSFER_CANCELS(0, isFlow(ContactTransferCancelFlow.class)),
    CONTACT_TRANSFER_REJECTS(0, isFlow(ContactTransferRejectFlow.class)),
    CONTACT_TRANSFER_REQUESTS(0, isFlow(ContactTransferRequestFlow.class)),
    CONTACT_UPDATES(0, isFlow(ContactUpdateFlow.class)),
    DOMAIN_APPLICATION_CREATES(0, isFlow(DomainApplicationCreateFlow.class)),
    DOMAIN_APPLICATION_CREATES_LANDRUSH(
        1, isFlow(DomainApplicationCreateFlow.class), not(IS_SUNRISE)),
    DOMAIN_APPLICATION_CREATES_SUNRISE(1, isFlow(DomainApplicationCreateFlow.class), IS_SUNRISE),
    DOMAIN_APPLICATION_DELETES(2, isFlow(DomainApplicationDeleteFlow.class)),
    DOMAIN_APPLICATION_UPDATES(2, isFlow(DomainApplicationUpdateFlow.class)),
    DOMAIN_CREATES(0, isFlow(DomainCreateFlow.class)),
    DOMAIN_CREATES_ASCII(1, isFlow(DomainCreateFlow.class), not(IS_IDN)),
    DOMAIN_CREATES_IDN(1, isFlow(DomainCreateFlow.class), IS_IDN),
    DOMAIN_CREATES_WITH_CLAIMS_NOTICE(1, isFlow(DomainCreateFlow.class), HAS_CLAIMS_NOTICE),
    DOMAIN_CREATES_WITH_FEE(1, isFlow(DomainCreateFlow.class), HAS_FEE),
    DOMAIN_CREATES_WITH_SEC_DNS(1, isFlow(DomainCreateFlow.class), HAS_SEC_DNS),
    DOMAIN_CREATES_WITHOUT_SEC_DNS(0, isFlow(DomainCreateFlow.class), not(HAS_SEC_DNS)),
    DOMAIN_DELETES(2, isFlow(DomainDeleteFlow.class)),
    DOMAIN_RENEWS(0, isFlow(DomainRenewFlow.class)),
    DOMAIN_RESTORES(1, isFlow(DomainRestoreRequestFlow.class)),
    DOMAIN_TRANSFER_APPROVES(1, isFlow(DomainTransferApproveFlow.class)),
    DOMAIN_TRANSFER_CANCELS(1, isFlow(DomainTransferCancelFlow.class)),
    DOMAIN_TRANSFER_REJECTS(1, isFlow(DomainTransferRejectFlow.class)),
    DOMAIN_TRANSFER_REQUESTS(1, isFlow(DomainTransferRequestFlow.class)),
    DOMAIN_UPDATES(0, isFlow(DomainUpdateFlow.class)),
    DOMAIN_UPDATES_WITH_SEC_DNS(1, isFlow(DomainUpdateFlow.class), HAS_SEC_DNS),
    DOMAIN_UPDATES_WITHOUT_SEC_DNS(0, isFlow(DomainUpdateFlow.class), not(HAS_SEC_DNS)),
    HOST_CREATES(0, isFlow(HostCreateFlow.class)),
    HOST_CREATES_EXTERNAL(0, isFlow(HostCreateFlow.class), not(IS_SUBORDINATE)),
    HOST_CREATES_SUBORDINATE(1, isFlow(HostCreateFlow.class), IS_SUBORDINATE),
    HOST_DELETES(1, isFlow(HostDeleteFlow.class)),
    HOST_UPDATES(1, isFlow(HostUpdateFlow.class)),
    UNCLASSIFIED_FLOWS(0, Predicates.<EppInput>alwaysFalse());

    /** The number of StatTypes with a non-zero requirement. */
    private static final int NUM_REQUIREMENTS = FluentIterable.from(values())
          .filter(new Predicate<StatType>() {
              @Override
              public boolean apply(@Nonnull StatType statType) {
                return statType.requirement > 0;
              }})
          .size();

    /** Required number of times registrars must complete this action. */
    final int requirement;

    /** Filters to determine if this action was performed by an EppInput. */
    private Predicate<EppInput>[] filters;

    @SafeVarargs
    StatType(int requirement, Predicate<EppInput>... filters) {
      this.requirement = requirement;
      this.filters = filters;
    }

    /** Returns a more human-readable translation of the enum constant. */
    String description() {
      return Ascii.toLowerCase(this.name().replace('_', ' '));
    }

    /** An {@link EppInput} might match multiple actions, so check if this action matches. */
    boolean matches(EppInput eppInput) {
      return Predicates.and(filters).apply(eppInput);
    }
  }

  /** Class to represent stats derived from HistoryEntry objects on actions taken by registrars. */
  static class HistoryEntryStats {

    /** Stores counts of how many times each action type was performed. */
    Multiset<StatType> statCounts = HashMultiset.create();

    /**
     * Records data in the passed historyEntryStats object on what actions have been performed by
     * the four numbered OT&amp;E variants of the registrar name.
     */
    HistoryEntryStats recordRegistrarHistory(String registrarName) {
      ImmutableList.Builder<String> clientIds = new ImmutableList.Builder<>();
      for (int i = 1; i <= 4; i++) {
        clientIds.add(String.format("%s-%d", registrarName, i));
      }
      for (HistoryEntry historyEntry :
          ofy().load().type(HistoryEntry.class).filter("clientId in", clientIds.build()).list()) {
        try {
          record(historyEntry);
        } catch (EppException e) {
          throw new RuntimeException(e);
        }
      }
      return this;
    }

    /** Interprets the data in the provided HistoryEntry and increments counters. */
    void record(HistoryEntry historyEntry) throws EppException {
      byte[] xmlBytes = historyEntry.getXmlBytes();
      // xmlBytes can be null on contact create and update for safe-harbor compliance.
      //
      // TODO(b/26161587): inspect the history entry itself to handle this properly.
      if (xmlBytes == null) {
        return;
      }
      final EppInput eppInput = unmarshal(EppInput.class, xmlBytes);
      if (!statCounts.addAll(
          FluentIterable.from(EnumSet.allOf(StatType.class))
              .filter(
                  new Predicate<StatType>() {
                    @Override
                    public boolean apply(@Nonnull StatType statType) {
                      return statType.matches(eppInput);
                    }
                  })
              .toList())) {
        statCounts.add(StatType.UNCLASSIFIED_FLOWS);
      }
    }

    /**
     * Returns a list of failure messages describing any cases where the passed stats fail to
     * meet the required thresholds, or the empty list if all requirements are met.
     */
    List<String> findFailures() {
      List<String> messages = new ArrayList<>();
      for (StatType statType : StatType.values()) {
        if (statCounts.count(statType) < statType.requirement) {
          messages.add(String.format(
              "Failure: %s %s found.",
              (statType.requirement == 1 ? "No" : "Not enough"),
              statType.description()));
        }
      }
      return messages;
    }

    /** Returns a string showing all possible actions and how many times each was performed. */
    @Override
    public String toString() {
      return FluentIterable.from(EnumSet.allOf(StatType.class))
          .transform(
              new Function<StatType, String>() {
                @Nonnull
                @Override
                public String apply(@Nonnull StatType statType) {
                  return String.format(
                      "%s: %d", statType.description(), statCounts.count(statType));
                }
              })
          .append(String.format("TOTAL: %d", statCounts.size()))
          .join(Joiner.on("\n"));
    }
  }
}
