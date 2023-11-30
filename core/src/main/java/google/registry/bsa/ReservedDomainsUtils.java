package google.registry.bsa;

import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.bsa.StringifyUtils.DOMAIN_JOINER;
import static google.registry.flows.domain.DomainFlowUtils.isReserved;
import static google.registry.model.tld.Tlds.findTldForName;

import com.google.common.collect.ImmutableSet;
import com.google.common.net.InternetDomainName;
import google.registry.model.tld.Tld;
import google.registry.model.tld.Tld.TldState;
import google.registry.model.tld.label.ReservedList;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.joda.time.DateTime;

/**
 * Utility for looking up reserved domain names.
 *
 * <p>This utility is only concerned with reserved domains that can be created (with appropriate
 * tokens).
 */
public final class ReservedDomainsUtils {

  private ReservedDomainsUtils() {}

  /** Returns */
  public static ImmutableSet<String> enumerateReservedDomains(Tld tld, DateTime now) {
    return tld.getReservedListNames().stream()
        .map(ReservedList::get)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .map(ReservedList::getReservedListEntries)
        .map(Map::keySet)
        .flatMap(Set::stream)
        .map(label -> DOMAIN_JOINER.join(label, tld.getTldStr()))
        .filter(domain -> isReservedDomain(domain, now))
        .collect(toImmutableSet());
  }

  /**
   * Returns true if {@code domain} is a reserved name that can be registered (i.e., unblockable).
   */
  public static boolean isReservedDomain(String domain, DateTime now) {
    Optional<InternetDomainName> tldStr = findTldForName(InternetDomainName.from(domain));
    verify(tldStr.isPresent(), "Tld for domain [%s] unexpectedly missing.", domain);
    Tld tld = Tld.get(tldStr.get().toString());
    return isReserved(
        InternetDomainName.from(domain),
        Objects.equals(tld.getTldState(now), TldState.START_DATE_SUNRISE));
  }
}
