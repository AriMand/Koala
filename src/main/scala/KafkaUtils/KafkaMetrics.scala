package KafkaUtils

import org.I0Itec.zkclient.ZkClient
import org.I0Itec.zkclient.ZkConnection
import kafka.utils.ZkUtils
import scala.collection.mutable.ListBuffer
import org.apache.kafka.common.protocol.SecurityProtocol
import org.I0Itec.zkclient.serialize.ZkSerializer;
import org.I0Itec.zkclient.exception.{ZkBadVersionException, ZkException, ZkMarshallingError, ZkNoNodeException, ZkNodeExistsException}


case class BrokerInfo(id: Int, host: String, port: Int)

case class TopicInfo(name: String, partitionId: String, leader: String, replicas: String, inSyncReplicas: String)

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
      println(brokerMap)
      for (topic <- topicList) {
        topicSeq ++= showTopic(zkClient, topic, brokerMap)
      }
    }
    catch {
      case e : Throwable =>
        println(e)
        println("list topic failed")
    }
    val consumergrps=zkutils.getConsumerGroups().toSeq.map(a=>ZkUtils.ConsumersPath+ "/" +a+"/offsets").filter(hasChildren _).map(a=>a.split("/")(2))
    //println(consumergrps)
    //println(consumergrps.map(a=>a->zkutils.getConsumersInGroup(a)))
    //println(consumergrps.map(a=>a->zkutils.getConsumersPerTopic(a,true)))
    val top=topicSeq
    //println(top.map(a=>a->zkutils.getAllConsumerGroupsForTopic(a.name)))
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