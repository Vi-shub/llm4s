package org.llm4s.config

import io.github.cdimascio.dotenv.Dotenv

object EnvLoader {
  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)
  private lazy val dotenv: Dotenv = Dotenv
    .configure()
    .ignoreIfMissing()
    .load()

  logger.info(s"Environment variables loaded from .env file ${EnvLoader.dotenv}")
  def get(key: String): Option[String] =
    Option(dotenv.get(key))

  def getOrElse(key: String, default: String): String =
    get(key).getOrElse(default)
}
