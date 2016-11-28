package google.registry.model.pricing;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.emptyToNull;
import static google.registry.model.registry.Registry.TldState.SUNRISE;
import static google.registry.model.registry.label.ReservationType.NAME_COLLISION;
import static google.registry.model.registry.label.ReservedList.getReservation;
import static google.registry.util.DomainNameUtils.getTldFromDomainName;

import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.net.InternetDomainName;
import google.registry.model.registry.Registry;
import google.registry.model.registry.label.CategorizedPremiumList;
import org.joda.money.Money;
import org.joda.time.DateTime;

import javax.inject.Inject;

/**
 * A categorized premium list pricing engine that stores static pricing information in Datastore
 * entities.
 */
public class CategorizedPremiumListPricingEngine implements PremiumPricingEngine {
  /** The name of the pricing engine, as used in {@code Registry.pricingEngineClassName}. */
  public static final String NAME =
      "google.registry.model.pricing.CategorizedPremiumListPricingEngine";

  @Inject
  CategorizedPremiumListPricingEngine() {}

  @Override
  public PremiumPricingEngine.DomainPrices getDomainPrices(String fullyQualifiedDomainName, DateTime priceTime) {
    String tld = getTldFromDomainName(fullyQualifiedDomainName);
    String label = InternetDomainName.from(fullyQualifiedDomainName).parts().get(0);
    Registry registry = Registry.get(checkNotNull(tld, "tld"));
    Optional<Money> premiumPrice = Optional.<Money>absent();
    if (registry.getPremiumList() != null) {
      String listName = registry.getPremiumList().getName();
      Optional<CategorizedPremiumList> premiumList = CategorizedPremiumList.get(listName);
      checkState(premiumList.isPresent(), "Could not load categorized premium list: %s", listName);
      premiumPrice = premiumList.get().getPremiumPrice(label);
    }
    boolean isNameCollisionInSunrise =
        registry.getTldState(priceTime).equals(SUNRISE)
            && getReservation(label, tld) == NAME_COLLISION;
    String feeClass = emptyToNull(Joiner.on('-').skipNulls().join(
        premiumPrice.isPresent() ? "premium" : null,
        isNameCollisionInSunrise ? "collision" : null));
    return PremiumPricingEngine.DomainPrices.create(
        premiumPrice.isPresent(),
        premiumPrice.or(registry.getStandardCreateCost()),
        premiumPrice.or(registry.getStandardRenewCost(priceTime)),
        Optional.<String>fromNullable(feeClass));
  }
}
