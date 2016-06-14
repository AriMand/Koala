package KafkaUtils

import org.I0Itec.zkclient.ZkClient
import org.I0Itec.zkclient.ZkConnection
import kafka.utils.ZkUtils
import scala.collection.mutable.ListBuffer
import org.apache.kafka.common.protocol.SecurityProtocol
import org.I0Itec.zkclient.serialize.ZkSerializer;
import org.I0Itec.zkclient.exception.{ZkBadVersionException, ZkException, ZkMarshallingError, ZkNoNodeException, ZkNodeExistsException}
import kafka.api.{OffsetFetchRequest, OffsetFetchResponse, OffsetRequest}
import kafka.common.{OffsetMetadataAndError, TopicAndPartition}
import kafka.consumer.SimpleConsumer
import kafka.consumer.{ConsumerThreadId, TopicCount}
import kafka.api.PartitionOffsetRequestInfo
import org.apache.kafka.common.errors.BrokerNotAvailableException
import KafkaLoadMetrics._

case class BrokerInfo(id: Int, host: String, port: Int)

case class TopicInfo(name: String, partitionId: String, leader: String, replicas: String, inSyncReplicas: String)

case class ConsumerInfo(name:String,threadCount:Int,topicName:String)

case class ConsumerGroupInfo(name:String,consumerCount:Int,topicCount:Int,consumers:Seq[ConsumerInfo])

class KafkaMetrics(zkClient: ZkClient,zkConnection: ZkConnection) {

  zkClient.setZkSerializer(ZKStringSerializer)

  var zkutils=new ZkUtils(zkClient,zkConnection,true)

  def listTopics : Seq[TopicInfo] = {

    var topicSeq = new ListBuffer[TopicInfo]()
    try {
      var topicList: Seq[String] = Nil

     topicList = zkutils.getChildrenParentMayNotExist(ZkUtils.BrokerTopicsPath).sorted
      if (topicList.size <= 0)
        println("no topics exist")
      else
        println("Found Topics:" + topicList.size)
      val brokerMap: Map[Int, String] = zkutils.getAllBrokersInCluster().map(a => a.getBrokerEndPoint(SecurityProtocol.PLAINTEXT).id -> (a.getBrokerEndPoint(SecurityProtocol.PLAINTEXT).host + ":" + a.getBrokerEndPoint(SecurityProtocol.PLAINTEXT).port) ).toMap
      //println(brokerMap)
      for (topic <- topicList) {
        topicSeq ++= showTopic(zkClient, topic, brokerMap)
      }
    }
    catch {
      case e : Throwable =>
        println(e)
        println("list topic failed")
    }
    //println(listConsumerGroups)
    topicSeq
  }
  def hasChildren(path: String): Boolean = zkClient.countChildren(path) > 0

  def showTopic(zkClient: ZkClient, topic: String, brokerMap: Map[Int, String]) : ListBuffer[TopicInfo] = {
    var topicObj = new ListBuffer[TopicInfo]()
    zkutils.getPartitionAssignmentForTopics(List(topic)).get(topic) match {
      case Some(topicPartitionAssignment) =>
        val sortedPartitions = topicPartitionAssignment.toList.sortWith((m1, m2) => m1._1 < m2._1)
        for ((partitionId, assignedReplicas) <- sortedPartitions) {
          val inSyncReplicas = zkutils.getInSyncReplicasForPartition(topic, partitionId)
          val leader = zkutils.getLeaderForPartition(topic, partitionId)
          val leaderNode =  brokerIdToHost(leader.getOrElse(-99), brokerMap)
          val replicas =  assignedReplicas.map( a => brokerIdToHost(a, brokerMap)).mkString(",")
          val isr = inSyncReplicas.map( a => brokerIdToHost(a, brokerMap)).mkString(",")
          topicObj += new TopicInfo(topic, partitionId.toString, leaderNode, replicas, isr)
        }
      case None =>
        println("topic " + topic + " doesn't exist!")
    }
    topicObj
  }

  def brokerIdToHost(brokerId: Int, brokerMap: Map[Int, String]): String = {
    brokerMap.getOrElse(brokerId, "None")
  }


  def listBrokers : Seq[BrokerInfo] =  {
    var brokers: Seq[BrokerInfo] = Seq.empty[BrokerInfo]
    try {
      brokers = zkutils.getAllBrokersInCluster().map( s => BrokerInfo(s.getBrokerEndPoint(SecurityProtocol.PLAINTEXT).id, s.getBrokerEndPoint(SecurityProtocol.PLAINTEXT).host, s.getBrokerEndPoint(SecurityProtocol.PLAINTEXT).port)).toSeq
    }
    catch {
      case e : Throwable =>
        println("list brokers failed")
    }
    brokers
  }

  def listConsumerGroups : Seq[ConsumerGroupInfo] = {
    var consumergrps= new scala.collection.mutable.ListBuffer[ConsumerGroupInfo]()
    try {
      val consumerGroups=zkutils.getConsumerGroups().toSeq
      for(consumerGroup<-consumerGroups){
        if(hasChildren(ZkUtils.ConsumersPath+ "/" +consumerGroup+"/ids")){
          val topicGroupThreads=zkutils.getConsumersPerTopic(consumerGroup,true)
          val consumersInfo=new scala.collection.mutable.ListBuffer[ConsumerInfo]()
          val consumersInGrp=zkutils.getConsumersInGroup(consumerGroup)
          val topicsInGrp=zkutils.getTopicsByConsumerGroup(consumerGroup)
          for(consumer<-consumersInGrp){
            consumersInfo ++= getConsumerInfo(consumer,true,topicGroupThreads)
          }
          consumergrps += ConsumerGroupInfo(consumerGroup,consumersInGrp.size,topicsInGrp.size,consumersInfo.toSeq)
        }else{

           consumergrps += ConsumerGroupInfo(consumerGroup,0,0,Seq.empty[ConsumerInfo])
        }
        
      }
    }
    catch {
      case e : Throwable =>
        println(e)
        println("list consumers error")
    }
    consumergrps.toSeq
  }

  def getConsumerInfo(consumer: String,excludeInternalTopics: Boolean,topicinfo: collection.mutable.Map[String, List[ConsumerThreadId]]) : List[ConsumerInfo] = {
    val threadsPerTopic=new collection.mutable.HashMap[String, List[String]]
    for ((topic, consumerThreadIDs) <- topicinfo){
      for(consumerThreadID<-consumerThreadIDs){
          if(consumerThreadID.consumer==consumer)
          {
          threadsPerTopic.get(topic) match {
            case Some(curConsumers) => threadsPerTopic.put(topic, consumerThreadID.consumer :: curConsumers)
            case _ => threadsPerTopic.put(topic, List(consumerThreadID.consumer))
          }
        }
      }
    }
    val topicCountMap=threadsPerTopic.map{case (a,b)=>(a,b.size)}
    var topicCountList = new scala.collection.mutable.ListBuffer[ConsumerInfo]()
    for((topic,threadCount)<-topicCountMap)
    {
      topicCountList += ConsumerInfo(consumer,threadCount,topic)
    }
    val consumerInfo=topicCountList.toList
    consumerInfo
  }
  def getloadMetrics(group:String):List[LoadInfo]={
    var klm=KafkaLoadMetrics
    klm.getLoadMetricsfForGroup(group,zkutils)
  }
}
private object ZKStringSerializer extends ZkSerializer {

  @throws(classOf[ZkMarshallingError])
  def serialize(data : Object) : Array[Byte] = data.asInstanceOf[String].getBytes("UTF-8")

  @throws(classOf[ZkMarshallingError])
  def deserialize(bytes : Array[Byte]) : Object = {
    if (bytes == null)
      null
    else
      new String(bytes, "UTF-8")
  }
}