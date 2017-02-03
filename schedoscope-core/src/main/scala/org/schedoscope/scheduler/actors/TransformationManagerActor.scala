/**
  * Copyright 2015 Otto (GmbH & Co KG)
  *
  * Licensed under the Apache License, Version 2.0 (the "License");
  * you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */
package org.schedoscope.scheduler.actors

import akka.actor.{Actor, ActorInitializationException, ActorRef, OneForOneStrategy, Props}
import akka.actor.SupervisorStrategy._
import akka.event.{Logging, LoggingReceive}
import akka.routing._
import org.schedoscope.conf.SchedoscopeSettings
import org.schedoscope.dsl.View
import org.schedoscope.dsl.transformations.{FilesystemTransformation, Transformation}
import org.schedoscope.scheduler.driver.{Driver, RetryableDriverException}
import org.schedoscope.scheduler.messages._
import org.schedoscope.scheduler.utils.ExponentialBackOff

import scala.collection.JavaConversions.asScalaSet
import scala.collection.mutable.HashMap
import scala.concurrent.duration._


/**
  * The transformation manager actor serves as a factory for all transformation requests, which are sent by view actors.
  * It pushes all requests to the correspondent transformation type driver router, which, in turn, load balances work
  * among its children, the Driver Actors.
  *
  */
class TransformationManagerActor(settings: SchedoscopeSettings,
                                 bootstrapDriverActors: Boolean) extends Actor {

  import context._

  val log = Logging(system, TransformationManagerActor.this)

  /**
    * Supervision strategy. If a driver actor raises a DriverException, the driver actor will be restarted.
    * If any other exception is raised, it is escalated.
    */
  override val supervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = -1) {
      case _: RetryableDriverException => Restart
      case _: ActorInitializationException => Restart
      case _ => Escalate
    }

  // used for determining BalancingDispatcher children' Supervision
  lazy val driverRouterSupervisorStrategy = OneForOneStrategy(maxNrOfRetries = -1) {
    case _: RetryableDriverException => Restart
    case _: ActorInitializationException => Restart
    case _ => Escalate
  }

  val driverStates = HashMap[String, TransformationStatusResponse[_]]()
  val driverBackOffWaitTime = HashMap[String, ExponentialBackOff]()

  def scheduleTick(driverActor: ActorRef, backOffTime: FiniteDuration) {
    system.scheduler.scheduleOnce(backOffTime, driverActor, "tick")
  }

  def manageDriverLifeCycle(asr: TransformationStatusResponse[_]) {
    if(asr.message == "booted") {
      if(driverBackOffWaitTime.contains(asr.actor.path.toStringWithoutAddress)) {
        val newBackOff = driverBackOffWaitTime(asr.actor.path.toStringWithoutAddress).nextBackOff
        scheduleTick(asr.actor, newBackOff.backOffWaitTime)
        driverBackOffWaitTime.put(asr.actor.path.toStringWithoutAddress, newBackOff)
        log.info(s"TRANFORMATION MANAGER ACTOR: Set new back-off waiting " +
          s"time to value ${newBackOff.backOffWaitTime} for rebooted actor ${asr.actor.path.toStringWithoutAddress}; " +
          s"(retries=${newBackOff.retries}, resets=${newBackOff.resets}, total-retries=${newBackOff.totalRetries})")
      } else {
        asr.actor ! "tick"
        val transformation = getTransformationName(asr.actor)
        val backOffSlotTime = settings.getDriverSettings(transformation).backOffSlotTime millis
        val backOffDelay = settings.getDriverSettings(transformation).backOffMinimumDelay millis

        val backOff = ExponentialBackOff(backOffSlotTime = backOffSlotTime, constantDelay = backOffDelay)
        log.debug(s"TRANFORMATION MANAGER ACTOR: Set initial back-off waiting " +
          s"time to value ${backOff.backOffWaitTime} for booted actor ${asr.actor.path.toStringWithoutAddress}; " +
          s"(retries=${backOff.retries}, resets=${backOff.resets}, total-retries=${backOff.totalRetries})")
        driverBackOffWaitTime.put(asr.actor.path.toStringWithoutAddress, backOff)
      }
    }
    driverStates.put(asr.actor.path.toStringWithoutAddress, asr)
  }

  def getTransformationName(actor: ActorRef): String = {
    val router = actor.path.toString
      .slice(self.path.toString.size, actor.path.toString.size)
      .split("/")(1)
    val transformation = router.split("-")(0)
    transformation
  }

  /**
    * Create one driver router per transformation type, which themselves spawn driver actors as required by configured transformation concurrency.
    */
  override def preStart {

    if (bootstrapDriverActors) {
      for (transformation <- Driver.transformationsWithDrivers) {
        actorOf(
          SmallestMailboxPool(
            nrOfInstances = settings.getDriverSettings(transformation).concurrency,
            supervisorStrategy = driverRouterSupervisorStrategy,
            routerDispatcher = "akka.actor.driver-router-dispatcher"
          ).props(routeeProps = DriverActor.props(settings, transformation, self)),
          s"${transformation}-driver"
        )
      }
    }
  }

  /**
    * Message handler
    */
  def receive = LoggingReceive({

    case asr: TransformationStatusResponse[_] => manageDriverLifeCycle(asr)

    case GetTransformations() => sender ! TransformationStatusListResponse(driverStates.values.toList)

    case commandToExecute: DriverCommand =>
      commandToExecute.command match {
        case TransformView(transformation, view) =>
          context.actorSelection(s"${self.path}/${transformation}-driver") forward commandToExecute
        case DeployCommand() =>
          context.actorSelection(s"${self.path}/*-driver/*") forward commandToExecute
        case transformation: Transformation =>
          context.actorSelection(s"${self.path}/${transformation.name}-driver") forward commandToExecute
      }

    case viewToTransform: View =>
      val transformation = viewToTransform.transformation().forView(viewToTransform)
      val commandRequest = DriverCommand(TransformView(transformation, viewToTransform), sender)
      context.actorSelection(s"${self.path}/${transformation.name}-driver") forward commandRequest

    case filesystemTransformation: FilesystemTransformation =>
      val driverCommand = DriverCommand(filesystemTransformation, sender)
      context.actorSelection(s"${self.path}/${filesystemTransformation.name}-driver") forward driverCommand

    case deploy: DeployCommand =>
      context.actorSelection(s"${self.path}/*-driver/*") forward DriverCommand(deploy, sender)
  })
}

/**
  * Factory for the actions manager actor.
  */
object TransformationManagerActor {
  def props(settings: SchedoscopeSettings,
            bootstrapDriverActors: Boolean = true) =
    Props(classOf[TransformationManagerActor],
      settings,
      bootstrapDriverActors).withDispatcher("akka.actor.transformation-manager-dispatcher")
}
