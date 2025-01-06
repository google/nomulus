// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.bsa.api;

import static com.google.common.base.Verify.verify;
import static google.registry.bsa.BsaStringUtils.PROPERTY_JOINER;

import google.registry.bsa.BsaStringUtils;
import google.registry.bsa.api.UnblockableDomain.Reason;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Change record of an {@link UnblockableDomain}. */
public record UnblockableDomainChange(UnblockableDomain unblockable, Optional<Reason> newReason) {

  /**
   * The text used in place of an empty {@link #newReason()} when an instance is serialized to
   * string.
   *
   * <p>This value helps manual inspection of the change files, making it easier to `grep` for
   * deletions in BSA reports.
   */
  private static final String DELETE_REASON_PLACEHOLDER = "IS_DELETE";

  public String domainName() {
    return unblockable().domainName();
  }

  public UnblockableDomain newValue() {
    verify(newReason().isPresent(), "Removed unblockable does not have new value.");
    return new UnblockableDomain(unblockable().domainName(), newReason().get());
  }

  public boolean isNewOrChange() {
    return newReason().isPresent();
  }

  public boolean isChangeOrDelete() {
    return !isNew();
  }

  public boolean isDelete() {
    return !this.isNewOrChange();
  }

  public boolean isNew() {
    return newReason().filter(unblockable().reason()::equals).isPresent();
  }

  public String serialize() {
    return PROPERTY_JOINER.join(
        unblockable().domainName(),
        unblockable().reason(),
        newReason().map(Reason::name).orElse(DELETE_REASON_PLACEHOLDER));
  }

  public static UnblockableDomainChange deserialize(String text) {
    List<String> items = BsaStringUtils.PROPERTY_SPLITTER.splitToList(text);
    return create(
        new UnblockableDomain(items.get(0), Reason.valueOf(items.get(1))),
        Objects.equals(items.get(2), DELETE_REASON_PLACEHOLDER)
            ? Optional.empty()
            : Optional.of(Reason.valueOf(items.get(2))));
  }

  public static UnblockableDomainChange createNew(UnblockableDomain unblockable) {
    return create(unblockable, Optional.of(unblockable.reason()));
  }

  public static UnblockableDomainChange createDeleted(UnblockableDomain unblockable) {
    return create(unblockable, Optional.empty());
  }

  public static UnblockableDomainChange createChanged(
      UnblockableDomain unblockable, Reason newReason) {
    return create(unblockable, Optional.of(newReason));
  }

  private static UnblockableDomainChange create(
      UnblockableDomain unblockable, Optional<Reason> newReason) {
    return new UnblockableDomainChange(unblockable, newReason);
  }
}
