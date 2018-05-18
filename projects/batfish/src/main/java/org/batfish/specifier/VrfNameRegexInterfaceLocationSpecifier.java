package org.batfish.specifier;

import com.google.common.collect.ImmutableSet;
import java.util.Set;
import java.util.regex.Pattern;

public class VrfNameRegexInterfaceLocationSpecifier implements LocationSpecifier {
  private final Pattern _pattern;

  public VrfNameRegexInterfaceLocationSpecifier(Pattern pattern) {
    _pattern = pattern;
  }

  protected Location makeLocation(String node, String iface) {
    return new InterfaceLocation(node, iface);
  }

  @Override
  public Set<Location> resolve(SpecifierContext ctxt) {
    return ctxt.getConfigs()
        .entrySet()
        .stream()
        .flatMap(
            entry -> {
              String node = entry.getKey();
              return entry
                  .getValue()
                  .getInterfaces()
                  .values()
                  .stream()
                  .filter(iface -> _pattern.matcher(iface.getVrfName()).matches())
                  .map(iface -> makeLocation(node, iface.getName()));
            })
        .collect(ImmutableSet.toImmutableSet());
  }
}