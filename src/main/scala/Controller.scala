import spray.routing.SimpleRoutingApp
import akka.actor.ActorSystem
import KafkaUtils.KafkaMetrics
import org.I0Itec.zkclient._
import KafkaUtils.TopicInfo
import KafkaUtils.BrokerInfo
import KafkaUtils.ConsumerInfo
import KafkaUtils.ConsumerGroupInfo
import spray.json.DefaultJsonProtocol._
import spray.json._


case class Metrics(topics: Seq[TopicInfo], brokers:Seq[BrokerInfo])

object KafkaJsonProtocol extends DefaultJsonProtocol {
  implicit val brokerFormat = jsonFormat(BrokerInfo, "id", "host", "port")
  implicit val topicFormat = jsonFormat(TopicInfo, "name", "partition","leader","replicas","inSyncReplicas")
  implicit val consumerFormat = jsonFormat(ConsumerInfo, "consumerName", "threadCount","topicName")
  implicit val consumerGroupFormat = jsonFormat(ConsumerGroupInfo, "consumerGroupName", "consumerCount","topicCount","consumers")
}

import KafkaJsonProtocol._

object Main extends App with SimpleRoutingApp {

  implicit val system = ActorSystem("my-system")
  var km=new KafkaMetrics(new ZkClient("182.95.208.253:2181"), new ZkConnection("182.95.208.253:2181"))

  startServer(interface = "localhost", port = 28080) {
    path("monitor") {
      get {
        complete {
          //var km=new KafkaMetrics(new ZkClient("edhl3n5:2181"), new ZkConnection("edhl3n5:2181"))
          val topics=km.listTopics
          val brokers=km.listBrokers
          val consumerGroups=km.listConsumerGroups
          val met=Metrics(topics,brokers)
          val metrics=JsObject(Map("topics" -> JsArray(topics.map(_.toJson).toList),"brokers" -> JsArray(brokers.map(_.toJson).toList),"consumerGroups"->JsArray(consumerGroups.map(_.toJson).toList)))
          metrics.toString()
          //<h1>Hello World</h1>
        }
      }
    }
  }
}