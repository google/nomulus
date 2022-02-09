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

package google.registry.model.translators;

import static com.google.common.base.Preconditions.checkState;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;
import static org.joda.time.DateTimeZone.UTC;

import google.registry.model.UpdateAutoTimestamp;
import google.registry.persistence.transaction.Transaction;
import java.util.Date;
import org.joda.time.DateTime;

/** Saves {@link UpdateAutoTimestamp} as the current time. */
public class UpdateAutoTimestampTranslatorFactory
    extends AbstractSimpleTranslatorFactory<UpdateAutoTimestamp, Date> {

  public UpdateAutoTimestampTranslatorFactory() {
    super(UpdateAutoTimestamp.class);
  }

  @Override
  SimpleTranslator<UpdateAutoTimestamp, Date> createTranslator() {
    return new SimpleTranslator<UpdateAutoTimestamp, Date>() {

      /**
       * Load an existing timestamp. It can be assumed to be non-null since if the field is null in
       * Datastore then Objectify will skip this translator and directly load a null.
       */
      @Override
      public UpdateAutoTimestamp loadValue(Date datastoreValue) {
        // Load an existing timestamp, or treat it as START_OF_TIME if none exists.
        return UpdateAutoTimestamp.create(new DateTime(datastoreValue, UTC));
      }

      /** Save a timestamp, setting it to the current time. */
      @Override
      public Date saveValue(UpdateAutoTimestamp pojoValue) {

        // If we're in the course of Transaction serialization, we have to use the transaction time
        // here and the JPA transaction manager which is what will ultimately be saved during the
        // commit.
        // Note that this branch doesn't respect "auto update disabled", as this state is
        // specifically to address replay, so we add a runtime check for this.
        if (Transaction.inSerializationMode()) {
          checkState(
              UpdateAutoTimestamp.autoUpdateEnabled(),
              "Auto-update disabled during transaction serialization.");
          return jpaTm().getTransactionTime().toDate();
        }

        return UpdateAutoTimestamp.autoUpdateEnabled()
            ? ofyTm().getTransactionTime().toDate()
            : pojoValue.getTimestamp().toDate();
      }
    };
  }
}
