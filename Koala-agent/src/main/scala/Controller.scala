import spray.routing.SimpleRoutingApp
import akka.actor.ActorSystem
import KafkaUtils.KafkaMetrics
import org.I0Itec.zkclient._
import KafkaUtils.TopicInfo
import KafkaUtils.BrokerInfo
import KafkaUtils.LoadInfo
import KafkaUtils.ConsumerInfo
import KafkaUtils.ConsumerGroupInfo
import spray.json.DefaultJsonProtocol._
import spray.json._
import scala.collection.mutable.ListBuffer


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
          var loads=new ListBuffer[LoadInfo]()
          val topics=km.listTopics
          val brokers=km.listBrokers
          val consumerGroups=km.listConsumerGroups
          for(congrp<-consumerGroups.toSeq)
          {
            val load=km.getloadMetrics(congrp.name)
           if(!load.isEmpty)
           {
              loads++=load
           } 
          }
          println(loads)
          val metrics=JsObject(Map("topics" -> JsArray(topics.map(_.toJson).toList),"brokers" -> JsArray(brokers.map(_.toJson).toList),"consumerGroups"->JsArray(consumerGroups.map(_.toJson).toList)))
          metrics.toString()
          //<h1>Hello World</h1>
        }
      }
    }
  }
}