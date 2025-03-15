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

package google.registry.pricing;

import dagger.Component;
import google.registry.model.pricing.PremiumPricingEngine;
import jakarta.inject.Singleton;
import java.util.Map;

/**
 * Dagger component with instance lifetime for pricing engines.
 *
 * <p>This component only exists because the flows themselves are not yet injected.  Once they are,
 * this separate component can be removed, and the pricingEngines() map added to the relevant
 * component used to construct the flows.
 */
@Singleton
@Component(modules = {PricingModule.class})
interface PricingComponent {
  Map<String, PremiumPricingEngine> premiumPricingEngines();
}
