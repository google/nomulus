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

import static com.google.common.base.MoreObjects.firstNonNull;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static org.joda.time.DateTimeZone.UTC;

import google.registry.model.CreateAutoTimestamp;
import java.util.Date;
import org.joda.time.DateTime;

/** Saves {@link CreateAutoTimestamp} as either its value, or the current time if it was null. */
public class CreateAutoTimestampTranslatorFactory
    extends AbstractSimpleTranslatorFactory<CreateAutoTimestamp, Date> {

  public CreateAutoTimestampTranslatorFactory() {
    super(CreateAutoTimestamp.class);
  }

  @Override
  SimpleTranslator<CreateAutoTimestamp, Date> createTranslator() {
    return new SimpleTranslator<CreateAutoTimestamp, Date>() {

      /**
       * Load an existing timestamp. It can be assumed to be non-null since if the field is null in
       * Datastore then Objectify will skip this translator and directly load a null.
       */
      @Override
      public CreateAutoTimestamp loadValue(Date datastoreValue) {
        return CreateAutoTimestamp.create(new DateTime(datastoreValue, UTC));
      }

      /** Save a timestamp, setting it to the current time if it did not have a previous value. */
      @Override
      public Date saveValue(CreateAutoTimestamp pojoValue) {
        return firstNonNull(pojoValue.getTimestamp(), tm().getTransactionTime()).toDate();
      }};
  }
}
