package google.registry.model.pricing;

import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.Rule;
import org.junit.Test;

import google.registry.testing.AppEngineRule;

import static com.google.common.truth.Truth.assertThat;

public class PricingCategoryTest {
  private static final String USD_PRICE = CurrencyUnit.USD + " 100";
  private static final String AA = "AA";
  private static final String AA_PLUS = "AA+";

  private final DateTime now = DateTime.now(DateTimeZone.UTC);

  @Rule public final AppEngineRule appEngine = AppEngineRule.builder().withDatastore().build();

  @Test
  public void testVerifyActivationDateIsAlreadySet() {
    // PricingCategory with activation dates
    PricingCategory pricingCategory = new PricingCategory.Builder()
        .setName(AA_PLUS)
        .setPrice(Money.parse(USD_PRICE))
        .activate()
        .build();

    assertThat(pricingCategory.getPrice()).isEqualTo(Money.parse(USD_PRICE));
    assertThat(pricingCategory.getActivationDate()).isGreaterThan(now);
  }

  @Test
  public void testVerifyActivationDateIsSetAfterBeingCreated() {
    // Initial persistence of PricingCategory - without activation date
    PricingCategory category = new PricingCategory.Builder()
        .setName(AA)
        .setPrice(Money.parse(USD_PRICE))
        .build();

    category = category.asBuilder().activate().build();

    assertThat(category.getPrice()).isEqualTo(Money.parse(USD_PRICE));
    assertThat(category.getActivationDate()).isNotNull();
  }
}
