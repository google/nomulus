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

package google.registry.tools.params;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Ordering;
import google.registry.model.common.DatabaseTransitionSchedule.PrimaryDatabase;
import google.registry.model.domain.token.AllocationToken.TokenStatus;
import google.registry.model.registry.Registry.TldState;
import org.joda.money.Money;
import org.joda.time.DateTime;

/** Combined converter and validator class for transition list JCommander argument strings. */
// TODO(b/19031334): Investigate making this complex generic type work with the factory.
public abstract class TransitionListParameter<V> extends KeyValueMapParameter<DateTime, V> {

  private static final DateTimeParameter DATE_TIME_CONVERTER = new DateTimeParameter();

  public TransitionListParameter() {
    // This is not sentence-capitalized like most exception messages because it is appended to the
    // end of the toString() of the transition map in rendering a full exception message.
    super("not formatted correctly or has transition times out of order");
  }

  @Override
  protected final DateTime parseKey(String rawKey) {
    return DATE_TIME_CONVERTER.convert(rawKey);
  }

  @Override
  protected final ImmutableSortedMap<DateTime, V> processMap(ImmutableMap<DateTime, V> map) {
    checkArgument(Ordering.natural().isOrdered(map.keySet()), "Transition times out of order");
    return ImmutableSortedMap.copyOf(map);
  }

  /** Converter-validator for TLD state transitions. */
  public static class TldStateTransitions extends TransitionListParameter<TldState> {
    @Override
    protected TldState parseValue(String value) {
      return TldState.valueOf(value);
    }
  }

  /** Converter-validator for billing cost transitions. */
  public static class BillingCostTransitions extends TransitionListParameter<Money> {
    private static final MoneyParameter MONEY_CONVERTER = new MoneyParameter();

    @Override
    protected Money parseValue(String value) {
      return MONEY_CONVERTER.convert(value);
    }
  }

  /** Converter-validator for token status transitions. */
  public static class TokenStatusTransitions extends TransitionListParameter<TokenStatus> {
    @Override
    protected TokenStatus parseValue(String value) {
      return TokenStatus.valueOf(value);
    }
  }

  /** Converter-validator for primary database transitions. */
  public static class PrimaryDatabaseTransitions extends TransitionListParameter<PrimaryDatabase> {
    @Override
    protected PrimaryDatabase parseValue(String value) {
      return PrimaryDatabase.valueOf(value);
    }
  }
}
