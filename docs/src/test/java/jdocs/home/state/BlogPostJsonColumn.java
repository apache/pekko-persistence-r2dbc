/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/**
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.home.state;

import org.apache.pekko.persistence.r2dbc.state.javadsl.AdditionalColumn;
// #additional-column-json
import io.r2dbc.postgresql.codec.Json;

public class BlogPostJsonColumn extends AdditionalColumn<BlogPost.State, Json> {
  @Override
  public Class<Json> fieldClass() {
    return Json.class;
  }

  @Override
  public String columnName() {
    return "query_json";
  }

  @Override
  public Binding<Json> bind(Upsert<BlogPost.State> upsert) {
    BlogPost.State state = upsert.value();
    if (state instanceof BlogPost.DraftState s) {
      // a json library would be used here
      String jsonString = "{\"title\": \"" + s.content.title + "\", \"published\": false}";
      Json json = Json.of(jsonString);
      return AdditionalColumn.bindValue(json);
    } else if (state instanceof BlogPost.PublishedState s) {
      // a json library would be used here
      String jsonString = "{\"title\": \"" + s.content.title + "\", \"published\": true}";
      Json json = Json.of(jsonString);
      return AdditionalColumn.bindValue(json);
    } else {
      return AdditionalColumn.skip();
    }
  }
}
// #additional-column-json
