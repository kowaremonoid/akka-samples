package sample.sharding.kafka

import akka.Done
import akka.actor.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.kafka.ConsumerSettings
import akka.kafka.Subscriptions
import akka.kafka.scaladsl.Consumer
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.Timeout
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.StringDeserializer

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Try

object UserEventsKafkaProcessor {

  sealed trait Command
  private case class KafkaConsumerStopped(reason: Try[Any]) extends Command

  def apply(): Behavior[Nothing] = {
    Behaviors
      .setup[Command] { ctx =>
        val processorSettings = ProcessorConfig(ctx.system.settings.config.getConfig("kafka-to-sharding-processor"))
        implicit val classic: ActorSystem = ctx.system.toClassic
        implicit val ec: ExecutionContextExecutor = ctx.executionContext
        // TODO config
        val timeout = Timeout(12.seconds)
        val rebalancerRef = ctx.spawn(TopicListener(UserEvents.TypeKey), "rebalancerRef")
        val shardRegion = UserEvents.init(ctx.system)
        val consumerSettings = ConsumerSettings(ctx.system.toClassic, new StringDeserializer, new StringDeserializer)
          .withBootstrapServers(processorSettings.bootstrapServers)
          .withGroupId(processorSettings.groupId)
          .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
          .withStopTimeout(0.seconds)

        val subscription = Subscriptions.topics(processorSettings.topic).withRebalanceListener(rebalancerRef.toClassic)

        val kafkaConsumer: Source[ConsumerRecord[String, String], Consumer.Control] =
          Consumer.plainSource(consumerSettings, subscription)

        // TODO deal with failures/restarts etc
        val stream: Future[Done] = kafkaConsumer
          .log("kafka-consumer")
          .filter(_.key() != null) // no entity id
          .mapAsync(20) { record =>
            // alternatively the user id could be in the message rather than use the kafka key
            ctx.log.info(s"entityId->partition ${record.key()}->${record.partition()}")
            ctx.log.info("Forwarding message for entity {} to cluster sharding", record.key())
            shardRegion.ask[Done](replyTo =>
              KafkaMessage(record.key(), UserEvents.UserAction(record.key(), record.value(), replyTo)))(
              timeout,
              ctx.system.scheduler)
          }
          .runWith(Sink.ignore)

        stream.onComplete { result =>
          ctx.self ! KafkaConsumerStopped(result)
        }
        Behaviors.receiveMessage[Command] {
          case KafkaConsumerStopped(reason) =>
            ctx.log.info("Consumer stopped {}", reason)
            Behaviors.stopped
        }
      }
      .narrow
  }

}
