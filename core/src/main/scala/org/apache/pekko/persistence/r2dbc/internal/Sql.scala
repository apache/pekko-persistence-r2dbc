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

import org.apache.pekko.annotation.InternalStableApi

/**
 * INTERNAL API: Utility to format SQL strings. Replaces `?` with numbered `\$1`, `\$2` for bind parameters. Trims
 * whitespace, including line breaks.
 */
@InternalStableApi
object Sql {

  trait Replacements {
    def apply(sql: String): String
  }
  object Replacements {
    object None extends Replacements {
      def apply(sql: String): String = sql
    }
    object Numbered extends Replacements {
      def apply(sql: String): String = {
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
    }
  }

  /**
   * Scala string interpolation with `sql` prefix. Replaces `?` with numbered `\$1`, `\$2` for bind parameters. Trims
   * whitespace, including line breaks. Standard string interpolation arguments `$` can be used.
   */
  implicit class Interpolation(val sc: StringContext) extends AnyVal {
    def sql(args: Any*): String =
      Replacements.Numbered(trimLineBreaks(sc.s(args: _*)))
  }

  implicit class ConfigurableInterpolation(val sc: StringContext) extends AnyVal {
    def sql(args: Any*)(implicit replacements: Replacements = Replacements.Numbered): String =
      replacements(trimLineBreaks(sc.s(args: _*)))
  }

  /**
   * Java API: Replaces `?` with numbered `\$1`, `\$2` for bind parameters. Trims whitespace, including line breaks. The
   * arguments are used like in [[java.lang.String.format]].
   */
  @varargs
  def format(sql: String, args: AnyRef*): String =
    Replacements.Numbered(trimLineBreaks(sql.format(args)))

  @varargs
  def format(sql: String, replacements: Replacements, args: AnyRef*): String =
    replacements(trimLineBreaks(sql.format(args)))

  private def trimLineBreaks(sql: String): String = {
    if (sql.indexOf('\n') == -1) {
      sql.trim
    } else {
      sql.trim.split('\n').map(_.trim).mkString(" ")
    }
  }
}
