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

package google.registry.pricing;

import dagger.Binds;
import dagger.Module;
import dagger.multibindings.IntoMap;
import dagger.multibindings.StringKey;
import google.registry.model.pricing.PremiumPricingEngine;
import google.registry.model.pricing.StaticPremiumListPricingEngine;

/**
 * Dagger module for injecting pricing engines.
 *
 * <p>To add a new pricing engine, create a new class that implements {@link PremiumPricingEngine},
 * and add a module that provides an instance of {@link PremiumPricingEngine} with a unique {@link
 * StringKey} annotation, and also <code>@Provides @IntoMap</code> annotations.
 */
@Module
public abstract class PricingModule {

  @Binds
  @IntoMap
  @StringKey(StaticPremiumListPricingEngine.NAME)
  abstract PremiumPricingEngine provideStaticPremiumList(StaticPremiumListPricingEngine engine);
}
