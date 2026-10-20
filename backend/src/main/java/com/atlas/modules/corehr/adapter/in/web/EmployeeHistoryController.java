package com.atlas.modules.corehr.adapter.in.web;

import com.atlas.modules.corehr.application.model.EmployeeHistoryModels.*;
import com.atlas.modules.corehr.application.port.in.*;
import java.security.Principal;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
public final class EmployeeHistoryController {
  private final PeopleUseCases people;
  private final EmployeeHistoryUseCases history;

  public EmployeeHistoryController(PeopleUseCases people, EmployeeHistoryUseCases history) {
    this.people = people;
    this.history = history;
  }

  record Response(
      List<Item> items,
      long total,
      int size,
      String order,
      List<String> fields,
      String nextCursor) {}

  @GetMapping("/api/v1/employees/{id}/change-history")
  Response history(
      Principal p,
      @PathVariable String id,
      @RequestParam(defaultValue = "ALL") String field,
      @RequestParam(defaultValue = "ALL") String action,
      @RequestParam(defaultValue = "desc") String order,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) String cursor) {
    var result =
        history.history(
            people.actor(p.getName()),
            id,
            new Query(field, action, order, size, EmployeeHistoryCursorCodec.decode(cursor)));
    return new Response(
        result.items(),
        result.total(),
        result.size(),
        result.order(),
        result.fields(),
        EmployeeHistoryCursorCodec.encode(result.next()));
  }
}
