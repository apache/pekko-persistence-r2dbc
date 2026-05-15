/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.persistence.r2dbc.state;

import org.apache.pekko.persistence.r2dbc.state.javadsl.AdditionalColumn;

public class JavadslColumn extends AdditionalColumn<String, Integer> {
  @Override
  public Class<Integer> fieldClass() {
    return Integer.class;
  }

  @Override
  public String columnName() {
    return "col3";
  }

  @Override
  public Binding<Integer> bind(Upsert<String> upsert) {
    if (upsert.value().isEmpty())
      return AdditionalColumn.bindNull();
    else if (upsert.value().equals("SKIP"))
      return AdditionalColumn.skip();
    else
      return new AdditionalColumn.BindValue<>(upsert.value().length());
  }
}
