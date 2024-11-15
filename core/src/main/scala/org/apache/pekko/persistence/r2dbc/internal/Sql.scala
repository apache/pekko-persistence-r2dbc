/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.persistence.r2dbc.internal

import scala.annotation.varargs
import org.apache.pekko
import pekko.annotation.InternalApi
import pekko.annotation.InternalStableApi
import pekko.persistence.r2dbc.Dialect

/**
 * INTERNAL API: Utility to format SQL strings. Replaces `?` with numbered `\$1`, `\$2` for bind parameters. Trims
 * whitespace, including line breaks.
 */
@InternalStableApi
object Sql {

  /**
   * INTERNAL API
   */
  @InternalApi
  private[r2dbc] implicit class DialectOps(dialect: Dialect) {
    def replaceParameters(sql: String): String = {
      dialect match {
        case Dialect.Postgres | Dialect.Yugabyte =>
          fillInParameterNumbers(sql)
        case Dialect.MySQL =>
          sql
      }
    }
  }

  /**
   * Scala string interpolation with `sql` prefix. Replaces `?` with numbered `\$1`, `\$2` for bind parameters. Trims
   * whitespace, including line breaks. Standard string interpolation arguments `$` can be used.
   */
  implicit class Interpolation(val sc: StringContext) extends AnyVal {
    def sql(args: Any*): String =
      fillInParameterNumbers(trimLineBreaks(sc.s(args: _*)))
  }

  /**
   * INTERNAL API
   */
  @InternalApi
  private[r2dbc] implicit class DialectInterpolation(val sc: StringContext) extends AnyVal {
    def sql(args: Any*)(implicit dialect: Dialect): String =
      dialect.replaceParameters(trimLineBreaks(sc.s(args: _*)))
  }

  /**
   * Java API: Replaces `?` with numbered `\$1`, `\$2` for bind parameters. Trims whitespace, including line breaks. The
   * arguments are used like in [[java.lang.String.format]].
   */
  @varargs
  def format(sql: String, args: AnyRef*): String =
    fillInParameterNumbers(trimLineBreaks(sql.format(args)))

  private def fillInParameterNumbers(sql: String): String = {
    if (sql.indexOf('?') == -1) {
      sql
    } else {
      val sb = new java.lang.StringBuilder(sql.length + 10)
      var n = 0
      var i = 0
      while (i < sql.length) {
        val c = sql.charAt(i)
        if (c == '?') {
          n += 1
          sb.append('$').append(n)
        } else {
          sb.append(c)
        }
        i += 1
      }
      sb.toString
    }
  }

  private def trimLineBreaks(sql: String): String = {
    if (sql.indexOf('\n') == -1) {
      sql.trim
    } else {
      sql.trim.split('\n').map(_.trim).mkString(" ")
    }
  }

}
