package com.atlas.archfixture.adapter.out;

import com.atlas.modules.corehr.domain.model.Actor;

public class BadPolicy {
  public boolean forbidden(Actor actor) {
    return actor.hr();
  }
}
