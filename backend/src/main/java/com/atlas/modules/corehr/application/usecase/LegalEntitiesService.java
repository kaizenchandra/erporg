package com.atlas.modules.corehr.application.usecase;

import com.atlas.modules.corehr.application.model.LegalEntityModels.*;
import com.atlas.modules.corehr.application.port.in.LegalEntitiesUseCases;
import com.atlas.modules.corehr.application.port.out.*;
import com.atlas.modules.corehr.domain.model.*;
import java.time.*;
import java.util.*;

public final class LegalEntitiesService implements LegalEntitiesUseCases {
  private final LegalEntityStore store;
  private final UnitOfWork writes, reads;
  private final Clock clock;

  public LegalEntitiesService(
      LegalEntityStore store, UnitOfWork writes, UnitOfWork reads, Clock clock) {
    this.store = store;
    this.writes = writes;
    this.reads = reads;
    this.clock = clock;
  }

  private void requireHr(Actor actor) {
    if (!actor.hr()) throw new ChangeRequest.Denied();
  }

  public Page list(Actor actor, Query query) {
    requireHr(actor);
    return reads.run(() -> store.list(actor.tenant(), query, LocalDate.now(clock)));
  }

  public Options options(Actor actor) {
    requireHr(actor);
    var countries =
        Arrays.stream(Locale.getISOCountries())
            .sorted()
            .map(
                c ->
                    new Choice(
                        c,
                        new Locale.Builder()
                            .setRegion(c)
                            .build()
                            .getDisplayCountry(Locale.ENGLISH)))
            .toList();
    var currencies =
        Currency.getAvailableCurrencies().stream()
            .sorted(Comparator.comparing(Currency::getCurrencyCode))
            .map(c -> new Choice(c.getCurrencyCode(), c.getDisplayName(Locale.ENGLISH)))
            .toList();
    return new Options(
        countries, currencies, ZoneId.getAvailableZoneIds().stream().sorted().toList());
  }

  public Entry create(Actor actor, Create c, String correlation) {
    requireHr(actor);
    var errors = new LinkedHashMap<String, String>();
    if (c.entity() == null) errors.put("entity", "Enter the legal-entity reference.");
    if (c.reason() == null
        || c.reason().isBlank()
        || c.reason().length() > 500
        || c.reason().codePoints().anyMatch(Character::isISOControl))
      errors.put("reason", "Enter a reason of 1–500 characters without control characters.");
    if (c.idempotencyKey() == null || !c.idempotencyKey().matches("[a-zA-Z0-9-]{8,80}"))
      errors.put("idempotencyKey", "A valid command key is required.");
    if (!errors.isEmpty()) throw new ChangeRequest.Validation(errors);
    var input = c.entity();
    var entity =
        new LegalEntity(
            input.code(),
            input.name(),
            input.countryCode(),
            input.currencyCode(),
            input.timeZone(),
            input.effectiveFrom());
    return writes.run(
        () -> {
          store.lock(actor.tenant());
          var previous = store.duplicate(actor.tenant(), actor.subject(), c.idempotencyKey());
          if (previous.isPresent()) {
            var p = previous.get();
            if (!p.entity().equals(entity) || !p.reason().equals(c.reason().trim()))
              throw new ChangeRequest.Conflict(
                  "Command key was used for different legal-entity data.");
            return p;
          }
          entity.validateNew(LocalDate.now(clock));
          if (store.codeExists(actor.tenant(), entity.code()))
            throw new ChangeRequest.Conflict("This legal-entity code is already recorded.");
          if (store.count(actor.tenant()) >= 200)
            throw new ChangeRequest.Conflict(
                "The provisional catalogue limit of 200 references has been reached.");
          var entry =
              new Entry(
                  entity,
                  actor.subject(),
                  c.reason().trim(),
                  clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                  correlation);
          store.insert(actor.tenant(), entry, c.idempotencyKey());
          return entry;
        });
  }
}
