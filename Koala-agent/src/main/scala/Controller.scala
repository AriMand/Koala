import spray.routing.SimpleRoutingApp
import scala.concurrent.duration.Duration
import spray.routing.HttpService
import spray.routing.authentication.BasicAuth
import spray.routing.directives.CachingDirectives._
import spray.httpx.encoding._
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
import com.typesafe.config.ConfigFactory


case class Metrics(topics: Seq[TopicInfo], brokers:Seq[BrokerInfo])

object KafkaJsonProtocol extends DefaultJsonProtocol {
  implicit val brokerFormat = jsonFormat(BrokerInfo, "id", "host", "port")
  implicit val topicFormat = jsonFormat(TopicInfo, "name", "partition","leader","replicas","inSyncReplicas")
  implicit val consumerFormat = jsonFormat(ConsumerInfo, "consumerName", "threadCount","topicName")
  implicit val consumerGroupFormat = jsonFormat(ConsumerGroupInfo, "consumerGroupName", "consumerCount","topicCount","consumers")
  implicit val loadFormat = jsonFormat(LoadInfo, "consumerGroupName", "topicName","pid","offset","logsize","lag","owner")
}

import KafkaJsonProtocol._

object Main extends App {

  implicit val system = ActorSystem("my-system")
  val conf = ConfigFactory.load("koala.conf");
  val zookeper = conf.getString("agent.zk")
  val serverPath=conf.getString("agent.server")
  var km=new KafkaMetrics(new ZkClient(zookeper), new ZkConnection(zookeper))
  while(true)
  {
    try{
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
        
        val metrics=JsObject(Map("topics" -> JsArray(topics.map(_.toJson).toList),"brokers" -> JsArray(brokers.map(_.toJson).toList),"consumerGroups"->JsArray(consumerGroups.map(_.toJson).toList),"loadMetrics"->JsArray(loads.map(_.toJson).toList)))
        println(metrics.toString())
        Thread.sleep(2000)
      }catch {
        case e:Throwable =>println(e)
      }

  }
}