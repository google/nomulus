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

package google.registry.model;

import static com.google.common.base.Predicates.equalTo;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.model.eppcommon.EppXmlTransformer.unmarshal;
import static google.registry.util.CollectionUtils.isNullOrEmpty;
import static google.registry.util.DomainNameUtils.ACE_PREFIX;

import com.google.common.base.Ascii;
import com.google.common.base.Predicates;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multiset;
import com.googlecode.objectify.Key;
import google.registry.model.domain.DomainCommand;
import google.registry.model.domain.fee.FeeCreateCommandExtension;
import google.registry.model.domain.launch.LaunchCreateExtension;
import google.registry.model.domain.secdns.SecDnsCreateExtension;
import google.registry.model.domain.secdns.SecDnsUpdateExtension;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppinput.EppInput.ResourceCommandWrapper;
import google.registry.model.host.HostCommand;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.reporting.HistoryEntry.Type;
import google.registry.model.reporting.HistoryEntryDao;
import google.registry.xml.XmlException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Represents stats derived from HistoryEntry objects on actions taken by registrars. */
public class OteStats {

  /**
   * Returns the statistics about the OT&amp;E actions that have been taken by a particular
   * registrar.
   */
  public static OteStats getFromRegistrar(String registrarName) {
    return new OteStats().recordRegistrarHistory(registrarName);
  }

  private OteStats() {}

  private static final Predicate<EppInput> HAS_SEC_DNS =
      eppInput ->
          eppInput.getSingleExtension(SecDnsCreateExtension.class).isPresent()
              || eppInput.getSingleExtension(SecDnsUpdateExtension.class).isPresent();

  private static final Predicate<EppInput> IS_IDN =
      eppInput ->
          ((DomainCommand.Create)
                  ((ResourceCommandWrapper) eppInput.getCommandWrapper().getCommand())
                      .getResourceCommand())
              .getFullyQualifiedDomainName()
              .startsWith(ACE_PREFIX);

  private static final Predicate<EppInput> IS_SUBORDINATE =
      eppInput ->
          !isNullOrEmpty(
              ((HostCommand.Create)
                      ((ResourceCommandWrapper) eppInput.getCommandWrapper().getCommand())
                          .getResourceCommand())
                  .getInetAddresses());

  private static boolean hasClaimsNotice(EppInput eppInput) {
    Optional<LaunchCreateExtension> launchCreate =
        eppInput.getSingleExtension(LaunchCreateExtension.class);
    return launchCreate.isPresent() && launchCreate.get().getNotice() != null;
  }

  private static boolean isSunrise(EppInput eppInput) {
    Optional<LaunchCreateExtension> launchCreate =
        eppInput.getSingleExtension(LaunchCreateExtension.class);
    return launchCreate.isPresent() && !isNullOrEmpty(launchCreate.get().getSignedMarks());
  }

  /** Enum defining the distinct statistics (types of registrar actions) to record. */
  public enum StatType {
    CONTACT_CREATES(0, equalTo(Type.CONTACT_CREATE)),
    CONTACT_DELETES(0, equalTo(Type.CONTACT_DELETE)),
    CONTACT_TRANSFER_APPROVES(0, equalTo(Type.CONTACT_TRANSFER_APPROVE)),
    CONTACT_TRANSFER_CANCELS(0, equalTo(Type.CONTACT_TRANSFER_CANCEL)),
    CONTACT_TRANSFER_REJECTS(0, equalTo(Type.CONTACT_TRANSFER_REJECT)),
    CONTACT_TRANSFER_REQUESTS(0, equalTo(Type.CONTACT_TRANSFER_REQUEST)),
    CONTACT_UPDATES(0, equalTo(Type.CONTACT_UPDATE)),
    DOMAIN_AUTORENEWS(0, equalTo(Type.DOMAIN_AUTORENEW)),
    DOMAIN_CREATES(0, equalTo(Type.DOMAIN_CREATE)),
    DOMAIN_CREATES_ASCII(1, equalTo(Type.DOMAIN_CREATE), IS_IDN.negate()),
    DOMAIN_CREATES_IDN(1, equalTo(Type.DOMAIN_CREATE), IS_IDN),
    DOMAIN_CREATES_START_DATE_SUNRISE(1, equalTo(Type.DOMAIN_CREATE), OteStats::isSunrise),
    DOMAIN_CREATES_WITH_CLAIMS_NOTICE(1, equalTo(Type.DOMAIN_CREATE), OteStats::hasClaimsNotice),
    DOMAIN_CREATES_WITH_FEE(
        1,
        equalTo(Type.DOMAIN_CREATE),
        eppInput -> eppInput.getSingleExtension(FeeCreateCommandExtension.class).isPresent()),
    DOMAIN_CREATES_WITH_SEC_DNS(1, equalTo(Type.DOMAIN_CREATE), HAS_SEC_DNS),
    DOMAIN_CREATES_WITHOUT_SEC_DNS(0, equalTo(Type.DOMAIN_CREATE), HAS_SEC_DNS.negate()),
    DOMAIN_DELETES(1, equalTo(Type.DOMAIN_DELETE)),
    DOMAIN_RENEWS(0, equalTo(Type.DOMAIN_RENEW)),
    DOMAIN_RESTORES(1, equalTo(Type.DOMAIN_RESTORE)),
    DOMAIN_TRANSFER_APPROVES(1, equalTo(Type.DOMAIN_TRANSFER_APPROVE)),
    DOMAIN_TRANSFER_CANCELS(1, equalTo(Type.DOMAIN_TRANSFER_CANCEL)),
    DOMAIN_TRANSFER_REJECTS(1, equalTo(Type.DOMAIN_TRANSFER_REJECT)),
    DOMAIN_TRANSFER_REQUESTS(1, equalTo(Type.DOMAIN_TRANSFER_REQUEST)),
    DOMAIN_UPDATES(0, equalTo(Type.DOMAIN_UPDATE)),
    DOMAIN_UPDATES_WITH_SEC_DNS(1, equalTo(Type.DOMAIN_UPDATE), HAS_SEC_DNS),
    DOMAIN_UPDATES_WITHOUT_SEC_DNS(0, equalTo(Type.DOMAIN_UPDATE), HAS_SEC_DNS.negate()),
    HOST_CREATES(0, equalTo(Type.HOST_CREATE)),
    HOST_CREATES_EXTERNAL(0, equalTo(Type.HOST_CREATE), IS_SUBORDINATE.negate()),
    HOST_CREATES_SUBORDINATE(1, equalTo(Type.HOST_CREATE), IS_SUBORDINATE),
    HOST_DELETES(1, equalTo(Type.HOST_DELETE)),
    HOST_UPDATES(1, equalTo(Type.HOST_UPDATE)),
    UNCLASSIFIED_FLOWS(0, Predicates.alwaysFalse());

    /** StatTypes with a non-zero requirement */
    public static final ImmutableList<StatType> REQUIRED_STAT_TYPES =
        Arrays.stream(values())
            .filter(statType -> statType.requirement > 0)
            .collect(toImmutableList());

    /** Required number of times registrars must complete this action. */
    private final int requirement;

    /** Filter to check the HistoryEntry Type */
    @SuppressWarnings("ImmutableEnumChecker") // Predicates are immutable.
    private final Predicate<HistoryEntry.Type> typeFilter;

    /** Optional filter on the EppInput. */
    @SuppressWarnings("ImmutableEnumChecker") // Predicates are immutable.
    private final Optional<Predicate<EppInput>> eppInputFilter;

    StatType(int requirement, Predicate<HistoryEntry.Type> typeFilter) {
      this(requirement, typeFilter, null);
    }

    StatType(
        int requirement,
        Predicate<HistoryEntry.Type> typeFilter,
        Predicate<EppInput> eppInputFilter) {
      this.requirement = requirement;
      this.typeFilter = typeFilter;
      if (eppInputFilter == null) {
        this.eppInputFilter = Optional.empty();
      } else {
        this.eppInputFilter = Optional.of(eppInputFilter);
      }
    }

    /** Returns the number of times this StatType must be performed. */
    public int getRequirement() {
      return requirement;
    }

    /** Returns a more human-readable translation of the enum constant. */
    public String getDescription() {
      return Ascii.toLowerCase(this.name().replace('_', ' '));
    }

    /**
     * Check if the {@link HistoryEntry} type matches as well as the {@link EppInput} if supplied.
     */
    private boolean matches(HistoryEntry.Type historyType, Optional<EppInput> eppInput) {
      if (eppInputFilter.isPresent() && eppInput.isPresent()) {
        return typeFilter.test(historyType) && eppInputFilter.get().test(eppInput.get());
      } else {
        return typeFilter.test(historyType);
      }
    }
  }

  /** Stores counts of how many times each action type was performed. */
  private final Multiset<StatType> statCounts = HashMultiset.create();

  /**
   * Records data on what actions have been performed by the four numbered OT&amp;E variants of the
   * registrar name.
   *
   * <p>Stops when it notices that all tests have passed.
   */
  private OteStats recordRegistrarHistory(String registrarName) {
    ImmutableCollection<String> registrarIds =
        OteAccountBuilder.createClientIdToTldMap(registrarName).keySet();

    for (HistoryEntry historyEntry : HistoryEntryDao.loadHistoryObjectsByRegistrars(registrarIds)) {
      try {
        record(historyEntry);
      } catch (XmlException e) {
        throw new RuntimeException("Couldn't parse history entry " + Key.create(historyEntry), e);
      }
      // Break out early if all tests were passed.
      if (wereAllTestsPassed()) {
        break;
      }
    }
    return this;
  }

  /** Interprets the data in the provided HistoryEntry and increments counters. */
  private void record(final HistoryEntry historyEntry) throws XmlException {
    byte[] xmlBytes = historyEntry.getXmlBytes();
    // xmlBytes can be null on contact create and update for safe-harbor compliance.
    final Optional<EppInput> eppInput =
        (xmlBytes == null) ? Optional.empty() : Optional.of(unmarshal(EppInput.class, xmlBytes));
    if (!statCounts.addAll(
        EnumSet.allOf(StatType.class).stream()
            .filter(statType -> statType.matches(historyEntry.getType(), eppInput))
            .collect(toImmutableList()))) {
      statCounts.add(StatType.UNCLASSIFIED_FLOWS);
    }
  }

  private boolean wereAllTestsPassed() {
    return Arrays.stream(StatType.values()).allMatch(s -> statCounts.count(s) >= s.requirement);
  }

  /** Returns the total number of actions taken */
  public int getSize() {
    return statCounts.size();
  }

  /** Returns the number of times that a particular StatType was seen */
  public int getCount(StatType statType) {
    return statCounts.count(statType);
  }

  /**
   * Returns a list of failures, any cases where the passed stats fail to meet the required
   * thresholds, or the empty list if all requirements are met.
   */
  public ImmutableList<StatType> getFailures() {
    return StatType.REQUIRED_STAT_TYPES.stream()
        .filter(statType -> statCounts.count(statType) < statType.requirement)
        .collect(toImmutableList());
  }

  /** Returns a string showing all possible actions and how many times each was performed. */
  @Override
  public String toString() {
    return String.format(
        "%s\nTOTAL: %d",
        EnumSet.allOf(StatType.class).stream()
            .map(stat -> String.format("%s: %d", stat.getDescription(), statCounts.count(stat)))
            .collect(Collectors.joining("\n")),
        statCounts.size());
  }
}
