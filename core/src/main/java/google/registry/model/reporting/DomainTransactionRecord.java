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

package google.registry.model.reporting;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.annotation.Embed;
import com.googlecode.objectify.annotation.Ignore;
import google.registry.model.Buildable;
import google.registry.model.ImmutableObject;
import google.registry.model.domain.DomainHistory.DomainHistoryId;
import google.registry.model.replay.DatastoreAndSqlEntity;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import org.joda.time.DateTime;

/**
 * The record of the mutations which contribute to transaction reporting.
 *
 * <p>This will only be constructed for a HistoryEntry which contributes to the transaction report,
 * i.e. only domain mutations.
 *
 * <p>The registrar accredited with this transaction is the enclosing HistoryEntry.clientId. The
 * only exception is for reportField = TRANSFER_LOSING_SUCCESSFUL or TRANSFER_LOSING_NACKED, which
 * uses HistoryEntry.otherClientId because the losing party in a transfer is always the otherClient.
 */
@Embed
@Entity
public class DomainTransactionRecord extends ImmutableObject
    implements Buildable, DatastoreAndSqlEntity {

  @Id
  @Ignore
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @ImmutableObject.Insignificant
  Long id;

  /** The TLD this record operates on. */
  @Column(nullable = false)
  String tld;

  // The following two fields are exposed in this entity to support bulk-loading in Cloud SQL by the
  // Datastore-SQL validation. They are excluded from equality check since they are not set in
  // Datastore.
  // TODO(b/203609782): post migration, decide whether to keep these two fields.
  @Ignore @ImmutableObject.Insignificant String domainRepoId;

  @Ignore @ImmutableObject.Insignificant Long historyRevisionId;

  /**
   * The time this Transaction takes effect (counting grace periods and other nuances).
   *
   * <p>Net adds, renews and transfers are modificationTime + 5 days for the grace period, while
   * Autorenews have a 45 day grace period. For deletions, this is the purge date of the domain. And
   * for restored names, this is the modificationTime, if done in the 30 day redemption period.
   *
   * @see <a
   *     href="https://www.icann.org/resources/unthemed-pages/registry-agmt-appc-10-2001-05-11-en">
   *     Grace period spec</a>
   */
  @Column(nullable = false)
  DateTime reportingTime;

  /** The transaction report field we add reportAmount to for this registrar. */
  @Column(nullable = false)
  @Enumerated(value = EnumType.STRING)
  TransactionReportField reportField;

  /**
   * The amount this record increases or decreases a registrar's report field.
   *
   * <p>For adds, renews, deletes, and restores, this is +1. For their respective cancellations,
   * this is -1.
   *
   * <p>For transfers, the gaining party gets a +1 for TRANSFER_GAINING_SUCCESSFUL, whereas the
   * losing party gets a +1 for TRANSFER_LOSING_SUCCESSFUL. Nacks result in +1 for
   * TRANSFER_GAINING_NACKED and TRANSFER_LOSING_NACKED, as well as -1 entries to cancel out the
   * original SUCCESSFUL transfer counters. Finally, if we explicitly allow a transfer, the report
   * amount is 0, as we've already counted the transfer in the original request.
   */
  @Column(nullable = false)
  Integer reportAmount;

  /**
   * The field added to by reportAmount within the transaction report.
   *
   * <p>The reportField specifies which column the reportAmount contributes to in the overall
   * report. ICANN wants a column for every add/renew broken down by number of years, so we have
   * the NET_ADDS_#_YR and NET_RENEWS_#_YR boilerplate to facilitate report generation.
   */
  public enum TransactionReportField {
    NET_ADDS_1_YR,
    NET_ADDS_2_YR,
    NET_ADDS_3_YR,
    NET_ADDS_4_YR,
    NET_ADDS_5_YR,
    NET_ADDS_6_YR,
    NET_ADDS_7_YR,
    NET_ADDS_8_YR,
    NET_ADDS_9_YR,
    NET_ADDS_10_YR,
    NET_RENEWS_1_YR,
    NET_RENEWS_2_YR,
    NET_RENEWS_3_YR,
    NET_RENEWS_4_YR,
    NET_RENEWS_5_YR,
    NET_RENEWS_6_YR,
    NET_RENEWS_7_YR,
    NET_RENEWS_8_YR,
    NET_RENEWS_9_YR,
    NET_RENEWS_10_YR,
    TRANSFER_SUCCESSFUL,
    TRANSFER_NACKED,
    DELETED_DOMAINS_GRACE,
    DELETED_DOMAINS_NOGRACE,
    RESTORED_DOMAINS;

    /** Boilerplate to simplify getting the NET_ADDS_#_YR enum from a number of years. */
    public static TransactionReportField netAddsFieldFromYears(int years) {
      return nameToField("NET_ADDS_%d_YR", years);
    }

    /** Boilerplate to simplify getting the NET_RENEWS_#_YR enum from a number of years. */
    public static TransactionReportField netRenewsFieldFromYears(int years) {
      return nameToField("NET_RENEWS_%d_YR", years);
    }

    public static final ImmutableSet<TransactionReportField> ADD_FIELDS =
        ImmutableSet.of(
            NET_ADDS_1_YR,
            NET_ADDS_2_YR,
            NET_ADDS_3_YR,
            NET_ADDS_4_YR,
            NET_ADDS_5_YR,
            NET_ADDS_6_YR,
            NET_ADDS_7_YR,
            NET_ADDS_8_YR,
            NET_ADDS_9_YR,
            NET_ADDS_10_YR);

    public static final ImmutableSet<TransactionReportField> RENEW_FIELDS =
        ImmutableSet.of(
            NET_RENEWS_1_YR,
            NET_RENEWS_2_YR,
            NET_RENEWS_3_YR,
            NET_RENEWS_4_YR,
            NET_RENEWS_5_YR,
            NET_RENEWS_6_YR,
            NET_RENEWS_7_YR,
            NET_RENEWS_8_YR,
            NET_RENEWS_9_YR,
            NET_RENEWS_10_YR);

    private static TransactionReportField nameToField(String enumTemplate, int years) {
      checkArgument(
          years >= 1 && years <= 10, "domain add and renew years must be between 1 and 10");
      try {
        return TransactionReportField.valueOf(String.format(enumTemplate, years));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "Unexpected error converting add/renew years to enum TransactionReportField", e);
      }
    }
  }

  public DomainHistoryId getDomainHistoryId() {
    return new DomainHistoryId(domainRepoId, historyRevisionId);
  }

  public DateTime getReportingTime() {
    return reportingTime;
  }

  public String getTld() {
    return tld;
  }

  public TransactionReportField getReportField() {
    return reportField;
  }

  public int getReportAmount() {
    return reportAmount;
  }

  /** An alternative construction method when the builder is not necessary. */
  public static DomainTransactionRecord create(
      String tld,
      DateTime reportingTime,
      TransactionReportField transactionReportField,
      int reportAmount) {
    return new DomainTransactionRecord.Builder()
        .setTld(tld)
        // We report this event when the grace period ends, if applicable
        .setReportingTime(reportingTime)
        .setReportField(transactionReportField)
        .setReportAmount(reportAmount)
        .build();
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** A builder for {@link DomainTransactionRecord} since it is immutable. */
  public static class Builder extends Buildable.Builder<DomainTransactionRecord> {

    public Builder() {}

    public Builder(DomainTransactionRecord instance) {
      super(instance);
      checkArgumentNotNull(instance, "DomainTransactionRecord instance must not be null");
    }

    public Builder setTld(String tld) {
      checkArgumentNotNull(tld, "tld must not be null");
      getInstance().tld = tld;
      return this;
    }

    public Builder setReportingTime(DateTime reportingTime) {
      checkArgumentNotNull(reportingTime, "reportingTime must not be mull");
      getInstance().reportingTime = reportingTime;
      return this;
    }

    public Builder setReportField(TransactionReportField reportField) {
      checkArgumentNotNull(reportField, "reportField must not be null");
      getInstance().reportField = reportField;
      return this;
    }

    public Builder setReportAmount(Integer reportAmount) {
      checkArgumentNotNull(reportAmount, "reportAmount must not be null");
      getInstance().reportAmount = reportAmount;
      return this;
    }

    @Override
    public DomainTransactionRecord build() {
      checkArgumentNotNull(getInstance().reportingTime, "reportingTime must be set");
      checkArgumentNotNull(getInstance().tld, "tld must be set");
      checkArgumentNotNull(
          getInstance().reportField, "reportField must be set");
      checkArgumentNotNull(getInstance().reportAmount, "reportAmount must be set");
      return super.build();
    }
  }
}
