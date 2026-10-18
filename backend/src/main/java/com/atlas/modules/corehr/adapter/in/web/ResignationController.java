package com.atlas.modules.corehr.adapter.in.web;

import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.application.port.in.ResignationUseCases;
import com.atlas.modules.corehr.domain.model.Resignation;
import java.security.Principal;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/resignations")
public final class ResignationController {
  private final ResignationUseCases useCases;

  public ResignationController(ResignationUseCases useCases) {
    this.useCases = useCases;
  }

  @GetMapping
  ResignationPage list(
      Principal p,
      @RequestParam(defaultValue = "ALL") String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "12") int size) {
    return useCases.list(p.getName(), new RequestQuery(status, page, size));
  }

  @PostMapping
  Resignation submit(
      Principal p,
      @RequestBody ResignationCommands.Submit c,
      @RequestAttribute("correlationId") String correlation) {
    return useCases.submit(p.getName(), c, correlation);
  }

  @PostMapping("/{id}/decision")
  Resignation review(
      Principal p,
      @PathVariable String id,
      @RequestBody ResignationCommands.Review c,
      @RequestAttribute("correlationId") String correlation) {
    return useCases.review(p.getName(), id, c, correlation);
  }

  @PostMapping("/{id}/withdrawal")
  com.atlas.modules.corehr.domain.model.ResignationWithdrawal withdraw(
      Principal p,
      @PathVariable String id,
      @RequestBody ResignationCommands.Withdraw c,
      @RequestAttribute("correlationId") String correlation) {
    return useCases.withdraw(p.getName(), id, c, correlation);
  }

  @GetMapping("/{id}/withdrawal")
  ResignationCommands.WithdrawalContext withdrawal(Principal p, @PathVariable String id) {
    return new ResignationCommands.WithdrawalContext(
        useCases.withdrawal(p.getName(), id).orElse(null));
  }

  @GetMapping("/{id}/episode")
  com.atlas.modules.corehr.domain.model.CaseEpisode episode(Principal p, @PathVariable String id) {
    return useCases.episode(p.getName(), id);
  }

  @GetMapping("/{id}/history")
  List<ResignationAudit> history(Principal p, @PathVariable String id) {
    return useCases.history(p.getName(), id);
  }
}
