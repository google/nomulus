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

package google.registry.model.domain.fee12;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import google.registry.model.ImmutableObject;
import google.registry.model.domain.fee.FeeCheckResponseExtension;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;
import org.joda.money.CurrencyUnit;

/**
 * An XML data object that represents version 0.12 of the fee extension that may be present on the
 * response to EPP domain check commands.
 */
@XmlRootElement(name = "chkData")
@XmlType(propOrder = {"currency", "items"})
public class FeeCheckResponseExtensionV12
    extends ImmutableObject implements FeeCheckResponseExtension<FeeCheckResponseExtensionItemV12> {

  CurrencyUnit currency;

  /** Check responses. */
  @XmlElement(name = "cd")
  ImmutableList<FeeCheckResponseExtensionItemV12> items;

  @Override
  public void setCurrencyIfSupported(CurrencyUnit currency) {
    this.currency = currency;
  }

  @VisibleForTesting
  @Override
  public ImmutableList<FeeCheckResponseExtensionItemV12> getItems() {
    return items;
  }

  @VisibleForTesting
  public static FeeCheckResponseExtensionV12 create(
      CurrencyUnit currency, ImmutableList<FeeCheckResponseExtensionItemV12> items) {
    FeeCheckResponseExtensionV12 instance = new FeeCheckResponseExtensionV12();
    instance.currency = currency;
    instance.items = items;
    return instance;
  }
}
