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

package google.registry.bsa.api;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.bsa.StringifyUtils.PROPERTY_JOINER;

import com.google.auto.value.AutoValue;
import google.registry.bsa.StringifyUtils;
import google.registry.bsa.api.NonBlockedDomain.Reason;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Change record of an {@link NonBlockedDomain}. */
@AutoValue
public abstract class UnblockableDomainChange {

  abstract NonBlockedDomain original();

  abstract Optional<Reason> newReason();

  public boolean isChange() {
    return newReason().isPresent();
  }

  public boolean isRemoval() {
    return !this.isChange();
  }

  public String serialize() {
    return PROPERTY_JOINER.join(original(), newReason().map(Reason::name).orElse(""));
  }

  public static UnblockableDomainChange deserialize(String text) {
    List<String> items = StringifyUtils.PROPERTY_SPLITTER.splitToList(text);
    return of(
        NonBlockedDomain.of(items.get(0), Reason.valueOf(items.get(1))),
        items.size() < 3 ? Optional.empty() : Optional.of(Reason.valueOf(items.get(2))));
  }

  public static UnblockableDomainChange of(
      NonBlockedDomain unblockable, Optional<Reason> newReason) {
    newReason.ifPresent(
        reason ->
            checkArgument(
                !Objects.equals(reason, unblockable.reason()),
                "No change in reason for %s",
                unblockable.domainName()));
    return new AutoValue_UnblockableDomainChange(unblockable, newReason);
  }
}
