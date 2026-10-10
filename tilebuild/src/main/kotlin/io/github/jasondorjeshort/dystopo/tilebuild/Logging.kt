package io.github.jasondorjeshort.dystopo.tilebuild

import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.appender.ConsoleAppender
import org.apache.logging.log4j.core.config.Configurator
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory

/**
 * Planetiler logs through log4j2 and its bundled configuration writes to stdout, which would mix
 * progress lines into the JSON report. This replaces that configuration with one that writes to
 * stderr, so stdout carries exactly one JSON object.
 */
object Logging {
    fun routeToStderr(quiet: Boolean) {
        val builder = ConfigurationBuilderFactory.newConfigurationBuilder()
        builder.setStatusLevel(Level.ERROR)
        builder.setConfigurationName("tilebuild")
        val appender = builder.newAppender("stderr", "Console")
            .addAttribute("target", ConsoleAppender.Target.SYSTEM_ERR)
        appender.add(builder.newLayout("PatternLayout").addAttribute("pattern", "%d{HH:mm:ss} %-5level %X{stage}- %msg%n%throwable"))
        builder.add(appender)
        builder.add(builder.newRootLogger(if (quiet) Level.WARN else Level.INFO).add(builder.newAppenderRef("stderr")))
        builder.add(builder.newLogger("org.apache", Level.WARN))
        builder.add(builder.newLogger("org.geotools", Level.WARN))
        builder.add(builder.newLogger("org.hsqldb", Level.WARN))
        Configurator.reconfigure(builder.build())
    }
}
