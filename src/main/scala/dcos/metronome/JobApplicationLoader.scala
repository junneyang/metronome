package dcos.metronome

import java.time.Clock

import controllers.AssetsComponents
import com.softwaremill.macwire._
import com.typesafe.scalalogging.StrictLogging
import dcos.metronome.api.v1.LeaderProxyFilter
import dcos.metronome.api.{ApiModule, ErrorHandler}
import mesosphere.marathon.MetricsModule
import mesosphere.marathon.core.async.ExecutionContexts
import mesosphere.marathon.core.base.JvmExitsCrashStrategy
import org.slf4j.LoggerFactory
import play.shaded.ahc.org.asynchttpclient.{AsyncHttpClientConfig, DefaultAsyncHttpClient}
import play.api.ApplicationLoader.Context
import play.api._
import play.api.i18n._
import play.api.libs.ws.ahc.{AhcConfigBuilder, AhcWSClient, AhcWSClientConfig, StandaloneAhcWSClient}
import play.api.libs.ws.{WSClient, WSConfigParser}
import play.api.mvc.EssentialFilter
import play.api.routing.Router

import scala.concurrent.Future
import scala.util.Failure

/**
  * Application loader that wires up the application dependencies using Macwire
  */
class JobApplicationLoader extends ApplicationLoader with StrictLogging {
  private[this] val log = LoggerFactory.getLogger(getClass)

  def load(context: Context): Application = {
    val jobComponents = new JobComponents(context)

    jobComponents.metricsModule.start(jobComponents.actorSystem)

    Future {
      jobComponents.schedulerService.run()
    }(scala.concurrent.ExecutionContext.global).onComplete {
      case Failure(e) =>
        log.error("Error during application initialization. Shutting down.", e)
        JvmExitsCrashStrategy.crash()
      case _ => // intentionally nothing, initialization went well
    }(ExecutionContexts.callerThread)

    jobComponents.application
  }
}

class JobComponents(context: Context) extends BuiltInComponentsFromContext(context) with I18nComponents with AssetsComponents {
  // set up logger
  LoggerConfigurator(context.environment.classLoader).foreach {
    _.configure(context.environment)
  }
  lazy val clock: Clock = Clock.systemUTC()

  override lazy val httpErrorHandler = new ErrorHandler

  lazy val metricsModule = MetricsModule(config.scallopConf, configuration.underlying)

  private[this] lazy val jobsModule: JobsModule = wire[JobsModule]

  private[this] lazy val apiModule: ApiModule = new ApiModule(controllerComponents, assets, httpErrorHandler, config,
    jobsModule.jobSpecModule.jobSpecService,
    jobsModule.jobRunModule.jobRunService,
    jobsModule.jobInfoModule.jobInfoService,
    jobsModule.pluginManger,
    jobsModule.queueModule.launchQueueService,
    jobsModule.actorsModule,
    metricsModule)

  def schedulerService = jobsModule.schedulerModule.schedulerService

  lazy val wsClient: WSClient = {
    val parser = new WSConfigParser(configuration.underlying, environment.classLoader)
    val config = AhcWSClientConfig(wsClientConfig = parser.parse())
    val builder = new AhcConfigBuilder(config)
    val logging = new AsyncHttpClientConfig.AdditionalChannelInitializer() {
      override def initChannel(channel: play.shaded.ahc.io.netty.channel.Channel): Unit = {
        channel.pipeline.addFirst("log", new play.shaded.ahc.io.netty.handler.logging.LoggingHandler(classOf[WSClient]))
      }
    }
    val ahcBuilder = builder.configure()
    ahcBuilder.setHttpAdditionalChannelInitializer(logging)
    val ahcConfig = ahcBuilder.build()
    val asyncHttpClient = new DefaultAsyncHttpClient(ahcConfig)
    new AhcWSClient(new StandaloneAhcWSClient(asyncHttpClient)(jobsModule.actorsModule.materializer))
  }

  override lazy val httpFilters: Seq[EssentialFilter] = Seq(
    new LeaderProxyFilter(wsClient, jobsModule.schedulerModule.electionService, config))

  override def router: Router = apiModule.router

  lazy val config = new MetronomeConfig(configuration)
}
