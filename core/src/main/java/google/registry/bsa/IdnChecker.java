package google.registry.bsa;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Maps.transformValues;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import google.registry.model.tld.Tld;
import google.registry.model.tld.Tld.TldType;
import google.registry.model.tld.Tlds;
import google.registry.tldconfig.idn.IdnLabelValidator;
import google.registry.tldconfig.idn.IdnTableEnum;

public final class IdnChecker {
  private static final IdnLabelValidator IDN_LABEL_VALIDATOR = new IdnLabelValidator();

  private final ImmutableMap<IdnTableEnum, ImmutableSet<Tld>> idnToTlds;
  private final ImmutableSet<Tld> allTlds;

  IdnChecker() {
    this.idnToTlds = getIdnToTldMap();
    allTlds = idnToTlds.values().stream().flatMap(ImmutableSet::stream).collect(toImmutableSet());
  }

  ImmutableSet<IdnTableEnum> getAllValidIdns(String label) {
    return idnToTlds.keySet().stream()
        .filter(idnTable -> idnTable.getTable().isValidLabel(label))
        .collect(toImmutableSet());
  }

  public ImmutableSet<Tld> getValidTlds(ImmutableSet<String> idnTables) {
    return idnTables.stream()
        .map(IdnTableEnum::valueOf)
        .filter(idnToTlds::containsKey)
        .map(idnToTlds::get)
        .flatMap(ImmutableSet::stream)
        .collect(toImmutableSet());
  }

  public SetView<Tld> getInvalidTlds(ImmutableSet<String> idnTables) {
    return Sets.difference(allTlds, getInvalidTlds(idnTables));
  }

  private static ImmutableMap<IdnTableEnum, ImmutableSet<Tld>> getIdnToTldMap() {
    ImmutableMultimap.Builder<IdnTableEnum, Tld> idnToTldMap = new ImmutableMultimap.Builder();
    Tlds.getTldEntitiesOfType(TldType.REAL).stream()
        .filter(Tld::isEnrolledWithBsa)
        .forEach(
            tld -> {
              for (IdnTableEnum idn : IDN_LABEL_VALIDATOR.getIdnTablesForTld(tld)) {
                idnToTldMap.put(idn, tld);
              }
            });
    return ImmutableMap.copyOf(transformValues(idnToTldMap.build().asMap(), ImmutableSet::copyOf));
  }
}
