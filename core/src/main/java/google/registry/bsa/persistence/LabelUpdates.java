package google.registry.bsa.persistence;

import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.persistence.PersistenceModule.TransactionIsolationLevel.TRANSACTION_REPEATABLE_READ;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.InternetDomainName;
import google.registry.bsa.IdnChecker;
import google.registry.bsa.common.Label;
import google.registry.bsa.common.UnblockableDomain;
import google.registry.bsa.common.UnblockableDomain.Reason;
import google.registry.bsa.jobs.BsaJob;
import google.registry.flows.domain.DomainFlowUtils;
import google.registry.model.ForeignKeyUtils;
import google.registry.model.domain.Domain;
import google.registry.model.tld.Tld;
import google.registry.persistence.transaction.QueryComposer.Comparator;
import java.util.Optional;
import java.util.stream.Stream;
import org.joda.time.DateTime;

public final class LabelUpdates {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final Joiner DOMAIN_JOINER = Joiner.on('.');

  private LabelUpdates() {}

  public static ImmutableList<UnblockableDomain> applyLabelDiff(
      ImmutableList<Label> labels, IdnChecker idnChecker, BsaJob jobInfo, DateTime now) {
    ImmutableList.Builder<UnblockableDomain> unblockables = new ImmutableList.Builder<>();
    tm().transact(
            () -> {
              for (Label label : labels) {
                Optional<BlockedLabel> labelEntity =
                    tm().loadByKeyIfPresent(BlockedLabel.vKey(label.label()));
                switch (label.labelType()) {
                  case DELETE:
                    // Record the label's deletion time and leave it in the table and in effect (wrt
                    // blocking).
                    verify(labelEntity.isPresent(), "Missing label [%s].", label.label());
                    tm().put(labelEntity.get().setBsaRemovalTime(jobInfo.job().getCreationTime()));
                    break;
                  case NEW_ORDER_ASSOCIATION:
                    verify(labelEntity.isPresent(), "Missing label [%s].", label.label());
                    // Label already exists. Load registered and reserved names from DB.
                    tm()
                        .createQueryComposer(UnblockedDomain.class)
                        .where("label", Comparator.EQ, label.label())
                        .stream()
                        .map(
                            record ->
                                UnblockableDomain.of(
                                    label.label(),
                                    record.tld,
                                    UnblockableDomain.Reason.valueOf(record.reason.name())))
                        .forEach(unblockables::add);
                    // Invalid names are not in DB. Recalculate them.
                    getInvalidTldsForLabel(label, idnChecker)
                        .map(tld -> UnblockableDomain.of(label.label(), tld, Reason.INVALID))
                        .forEach(unblockables::add);
                    break;
                  case ADD:
                    BlockedLabel newLabelEntity = null;
                    tm().put(newLabelEntity);

                    getInvalidTldsForLabel(label, idnChecker)
                        .map(tld -> UnblockableDomain.of(label.label(), tld, Reason.INVALID))
                        .forEach(unblockables::add);

                    ImmutableSet<String> validDomainNames =
                        getValidTldsForLabel(label, idnChecker)
                            .map(tld -> DOMAIN_JOINER.join(label.label(), tld))
                            .collect(toImmutableSet());
                    ImmutableSet<String> registeredDomainNames =
                        ImmutableSet.copyOf(
                            ForeignKeyUtils.load(Domain.class, validDomainNames, now).keySet());
                    registeredDomainNames.stream()
                        .map(
                            domain ->
                                UnblockableDomain.of(
                                    label.label(),
                                    domain.substring(domain.lastIndexOf('.')),
                                    Reason.REGISTERED))
                        .forEach(unblockables::add);
                    // TODO: add to DB

                    Sets.difference(validDomainNames, registeredDomainNames).stream()
                        .filter(LabelUpdates::isReservedDomain)
                        .map(
                            domain ->
                                UnblockableDomain.of(
                                    label.label(),
                                    domain.substring(domain.lastIndexOf('.')),
                                    Reason.RESERVED))
                        .forEach(unblockables::add);
                    // TODO: save to DB.
                    break;
                }
              }
            },
            // Unnecessary to catch race between this txn and domain/reserved-list changes.
            TRANSACTION_REPEATABLE_READ);
    return unblockables.build();
  }

  static Stream<String> getInvalidTldsForLabel(Label label, IdnChecker idnChecker) {
    return idnChecker.getInvalidTlds(label.idnTables()).stream().map(Tld::getTldStr);
  }

  static Stream<String> getValidTldsForLabel(Label label, IdnChecker idnChecker) {
    return idnChecker.getValidTlds(label.idnTables()).stream().map(Tld::getTldStr);
  }

  static boolean isReservedDomain(String domain) {
    return DomainFlowUtils.isReserved(InternetDomainName.from(domain), /* isSunrise= */ false);
  }
}
