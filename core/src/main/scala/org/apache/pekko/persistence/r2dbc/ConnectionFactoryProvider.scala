/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2021 - 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.persistence.r2dbc

import java.time.{ Duration => JDuration }
import java.util.concurrent.ConcurrentHashMap

import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters._
import scala.jdk.DurationConverters._
import scala.util.Failure
import scala.util.Success

import org.apache.pekko
import pekko.Done
import pekko.actor.CoordinatedShutdown
import pekko.actor.typed.ActorSystem
import pekko.actor.typed.Extension
import pekko.actor.typed.ExtensionId
import pekko.persistence.r2dbc.ConnectionFactoryProvider.ConnectionFactoryOptionsCustomizer
import pekko.persistence.r2dbc.ConnectionFactoryProvider.NoopCustomizer
import pekko.persistence.r2dbc.internal.R2dbcExecutor.PublisherOps
import pekko.persistence.r2dbc.internal.h2.H2Connections
import pekko.persistence.r2dbc.internal.mariadb.MariaDBConnections
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionFactoryProvider
import io.r2dbc.postgresql.client.SSLMode
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryOptions
import io.r2dbc.spi.Option

object ConnectionFactoryProvider extends ExtensionId[ConnectionFactoryProvider] {
  def createExtension(system: ActorSystem[?]): ConnectionFactoryProvider = new ConnectionFactoryProvider(system)

  // Java API
  def get(system: ActorSystem[?]): ConnectionFactoryProvider = apply(system)

  /**
   * Enables customization of `ConnectionFactoryOptions` right before the connection factory is created.
   * This is particularly useful for setting options that support dynamically computed values rather than
   * just plain constants. Classes implementing this trait must have a constructor with a single parameter
   * of type [[ActorSystem]].
   *
   * @since 1.1.0
   */
  trait ConnectionFactoryOptionsCustomizer {

    /**
     * Customizes the `ConnectionFactoryOptions.Builder` instance based on the provided configuration.
     *
     * @param builder the options builder that has been pre-configured by the connection factory provider
     * @param config  the connection factory configuration
     * @return        the modified options builder with the applied customizations
     *
     * @since 1.1.0
     */
    def apply(builder: ConnectionFactoryOptions.Builder, config: Config): ConnectionFactoryOptions.Builder
  }

  private object NoopCustomizer extends ConnectionFactoryOptionsCustomizer {
    override def apply(builder: ConnectionFactoryOptions.Builder, config: Config): ConnectionFactoryOptions.Builder =
      builder
  }
}

class ConnectionFactoryProvider(system: ActorSystem[?]) extends Extension {

  private val sessions = new ConcurrentHashMap[String, ConnectionPool]

  CoordinatedShutdown(system)
    .addTask(CoordinatedShutdown.PhaseBeforeActorSystemTerminate, "close connection pools") { () =>
      import system.executionContext
      Future
        .sequence(sessions.asScala.values.map(_.disposeLater().asFutureDone()))
        .map(_ => Done)
    }

  def connectionFactoryFor(configPath: String): ConnectionFactory = {
    connectionFactoryFor(configPath, ConfigFactory.empty())
  }

  def connectionFactoryFor(configPath: String, config: Config): ConnectionFactory = {
    sessions
      .computeIfAbsent(
        configPath,
        _ => {
          val fullConfig = config.withFallback(system.settings.config)
          val connectionFactoryConfig = fullConfig.getConfig(configPath)
          val settings = new ConnectionFactorySettings(connectionFactoryConfig)
          val connectionFactory =
            if (settings.driver == "h2") {
              require(
                settings.connectionFactoryOptionsCustomizer.isEmpty,
                "connection-factory-options-customizer is not supported with the h2 driver")
              H2Connections.create(connectionFactoryConfig.getConfig("h2"))
            } else
              createConnectionFactory(settings, createConnectionFactoryOptionsCustomizer(settings), fullConfig)
          createConnectionPoolFactory(settings, connectionFactory)
        })
      .asInstanceOf[ConnectionFactory]
  }

  private def createConnectionFactoryOptionsCustomizer(
      settings: ConnectionFactorySettings): ConnectionFactoryOptionsCustomizer = {
    settings.connectionFactoryOptionsCustomizer match {
      case None       => NoopCustomizer
      case Some(fqcn) =>
        val args = List(classOf[ActorSystem[?]] -> system)
        system.dynamicAccess.createInstanceFor[ConnectionFactoryOptionsCustomizer](fqcn, args) match {
          case Success(customizer) => customizer
          case Failure(cause)      =>
            throw new IllegalArgumentException(s"Failed to create ConnectionFactoryOptionsCustomizer for class $fqcn",
              cause)
        }
    }
  }

  private def createConnectionFactory(settings: ConnectionFactorySettings,
      customizer: ConnectionFactoryOptionsCustomizer,
      config: Config): ConnectionFactory = {
    val builder =
      settings.urlOption match {
        case Some(url) =>
          ConnectionFactoryOptions.builder().from(ConnectionFactoryOptions.parse(url))
        case _ =>
          ConnectionFactoryOptions
            .builder()
            .option(ConnectionFactoryOptions.DRIVER, settings.driver)
            .option(ConnectionFactoryOptions.HOST, settings.host)
            .option(ConnectionFactoryOptions.PORT, Integer.valueOf(settings.port))
            .option(ConnectionFactoryOptions.USER, settings.user)
            .option(ConnectionFactoryOptions.PASSWORD, settings.password)
            .option(ConnectionFactoryOptions.DATABASE, settings.database)
            .option(ConnectionFactoryOptions.CONNECT_TIMEOUT, settings.connectTimeout.toJava)
      }

    builder
      .option(PostgresqlConnectionFactoryProvider.FORCE_BINARY, java.lang.Boolean.TRUE)
      .option(PostgresqlConnectionFactoryProvider.PREFER_ATTACHED_BUFFERS, java.lang.Boolean.TRUE)
      .option(
        PostgresqlConnectionFactoryProvider.PREPARED_STATEMENT_CACHE_QUERIES,
        Integer.valueOf(settings.statementCacheSize))

    settings.statementTimeout.foreach { timeout =>
      builder.option(PostgresqlConnectionFactoryProvider.STATEMENT_TIMEOUT, timeout.toJava)
    }

    val mariaDB = MariaDBConnections.isMariaDB(builder.build())

    if (settings.sslEnabled && mariaDB) {
      // the MariaDB driver ignores the SSL option and has its own names and values for the other options
      MariaDBConnections.sslOptions(settings).foreach { case (option, value) => builder.option(option, value) }
    } else if (settings.sslEnabled) {
      builder.option(ConnectionFactoryOptions.SSL, java.lang.Boolean.TRUE)

      if (settings.sslMode.nonEmpty)
        builder.option(PostgresqlConnectionFactoryProvider.SSL_MODE, SSLMode.fromValue(settings.sslMode))

      if (settings.sslRootCert.nonEmpty)
        builder.option(PostgresqlConnectionFactoryProvider.SSL_ROOT_CERT, settings.sslRootCert)

      if (settings.sslCert.nonEmpty)
        builder.option(PostgresqlConnectionFactoryProvider.SSL_CERT, settings.sslCert)

      if (settings.sslKey.nonEmpty)
        builder.option(PostgresqlConnectionFactoryProvider.SSL_KEY, settings.sslKey)

      if (settings.sslPassword.nonEmpty)
        builder.option(PostgresqlConnectionFactoryProvider.SSL_PASSWORD, settings.sslPassword)
    }

    if (settings.driver == "mysql") {
      // Either `connectionTimeZone = SERVER` or `forceConnectionTimeZoneToSession = true` need to be set for timezones to work correctly,
      // likely caused by bug in https://github.com/asyncer-io/r2dbc-mysql/pull/240.
      builder.option(Option.valueOf("connectionTimeZone"), "SERVER")
    }

    val options = customizer(builder, config).build()
    if (MariaDBConnections.isMariaDB(options))
      // Instants are bound and read as UTC LocalDateTime, which requires the UTC session time zone
      MariaDBConnections.wrap(ConnectionFactories.get(MariaDBConnections.requireUtcTimezone(options)))
    else
      ConnectionFactories.get(options)
  }

  private def createConnectionPoolFactory(settings: ConnectionFactorySettings,
      connectionFactory: ConnectionFactory): ConnectionPool = {

    val evictionInterval = {
      import settings.maxIdleTime
      import settings.maxLifeTime
      if (maxIdleTime <= Duration.Zero && maxLifeTime <= Duration.Zero) {
        JDuration.ZERO
      } else if (maxIdleTime <= Duration.Zero) {
        (maxLifeTime / 4).toJava
      } else if (maxLifeTime <= Duration.Zero) {
        (maxIdleTime / 4).toJava
      } else {
        (maxIdleTime.min(maxIdleTime) / 4).toJava
      }
    }

    val poolConfiguration = ConnectionPoolConfiguration
      .builder(connectionFactory)
      .initialSize(settings.initialSize)
      .maxSize(settings.maxSize)
      // Don't use maxCreateConnectionTime because it can cause connection leaks, see issue #182
      // ConnectionFactoryOptions.CONNECT_TIMEOUT is used instead.
      .maxAcquireTime(settings.acquireTimeout.toJava)
      .acquireRetry(settings.acquireRetry)
      .maxIdleTime(settings.maxIdleTime.toJava)
      .maxLifeTime(settings.maxLifeTime.toJava)
      .backgroundEvictionInterval(evictionInterval)

    if (settings.validationQuery.nonEmpty)
      poolConfiguration.validationQuery(settings.validationQuery)

    val pool = new ConnectionPool(poolConfiguration.build())

    // eagerly create initialSize connections
    pool.warmup().asFutureDone() // don't wait for it

    pool
  }

}
