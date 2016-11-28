package google.registry.model.pricing;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatastoreHelper.persistCategorizedPremiumList;
import static google.registry.testing.DatastoreHelper.persistPricingCategory;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ImmutableSortedMap;
import google.registry.testing.AppEngineRule;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class CategorizedPremiumListPricingEngineTest {
  private static final String JAPANESE_PRICE_CATEGORY = "JAPANESE_PRICE_CATEGORY";
  private static final String JAPANESE_PRICE = CurrencyUnit.JPY + " 511";
  private static final String TLD_ONE = "tld_one";
  private static final String LABEL_ONE = "label_one";

  private final CategorizedPremiumListPricingEngine engine =
      new CategorizedPremiumListPricingEngine();

  @Rule public final AppEngineRule appEngine = AppEngineRule.builder().withDatastore().build();

  @Before
  public void init() {
    PricingCategory pc = persistPricingCategory(JAPANESE_PRICE_CATEGORY, JAPANESE_PRICE);

    persistCategorizedPremiumList(
        ImmutableSortedMap.of(START_OF_TIME, pc.getName()), TLD_ONE, LABEL_ONE);
  }

  @Test
  public void getDomainPrices_shouldReturnPremiumPrice() throws Exception {
    PremiumPricingEngine.DomainPrices prices =
        engine.getDomainPrices(LABEL_ONE + "." + TLD_ONE, DateTime.now());
    assertThat(prices.isPremium()).isTrue();
    assertThat(prices.getCreateCost()).isEqualTo(Money.parse(JAPANESE_PRICE));
  }

  @Test
  public void getDomainPrices_shouldNotBePremiumPrice() {
    PremiumPricingEngine.DomainPrices prices =
        engine.getDomainPrices("SomeLabel" + "." + TLD_ONE, DateTime.now());
    assertThat(prices.isPremium()).isFalse();
  }
}
