/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

addSbtPlugin("de.heikoseeberger" % "sbt-header" % "5.10.0") // for maintenance of copyright file header
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.5")
addSbtPlugin("com.github.sbt" % "sbt-java-formatter" % "0.9.0")
addSbtPlugin("com.github.sbt" % "sbt-unidoc" % "0.5.0")
addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "1.1.4")

// for releasing
addSbtPlugin("com.github.sbt" % "sbt-dynver" % "5.1.1")
addSbtPlugin("net.bzzt" % "sbt-reproducible-builds" % "0.32")
addSbtPlugin("com.github.pjfanning" % "sbt-pekko-build" % "0.4.5")
addSbtPlugin("com.github.pjfanning" % "sbt-source-dist" % "0.1.12")
addSbtPlugin("com.github.sbt" % "sbt-license-report" % "1.7.0")

//// docs
addSbtPlugin("org.apache.pekko" % "pekko-sbt-paradox" % "1.0.1")
addSbtPlugin(("com.github.sbt" % "sbt-site-paradox" % "1.7.0").excludeAll(
  "com.lightbend.paradox", "sbt-paradox"))
