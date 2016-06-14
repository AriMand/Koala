package KafkaUtils

import kafka.utils._
import kafka.consumer.SimpleConsumer
import kafka.api.{OffsetFetchRequest, OffsetFetchResponse, OffsetRequest}
import kafka.common.{OffsetMetadataAndError, TopicAndPartition}
import org.apache.kafka.common.errors.BrokerNotAvailableException
import org.apache.kafka.common.protocol.{Errors, SecurityProtocol}
import org.apache.kafka.common.security.JaasUtils

import scala.collection._
import kafka.client.ClientUtils
import kafka.network.BlockingChannel
import kafka.api.PartitionOffsetRequestInfo
import org.I0Itec.zkclient.exception.ZkNoNodeException
import scala.collection.mutable.ListBuffer

case class LoadInfo(group:String,topic:String, partitionID: Long,offset:Long,logsize:Long,lag:Long,owner:String)

object KafkaLoadMetrics extends Logging {

  private val consumerMap: mutable.Map[Int, Option[SimpleConsumer]] = mutable.Map()
  private val offsetMap: mutable.Map[TopicAndPartition, Long] = mutable.Map()
  private var topicPidMap: immutable.Map[String, Seq[Int]] = immutable.Map()

  private def createConsumer(zkUtils: ZkUtils, bid: Int): Option[SimpleConsumer] = {
    try {
      zkUtils.getBrokerInfo(bid)
        .map(_.getBrokerEndPoint(SecurityProtocol.PLAINTEXT))
        .map(endPoint => new SimpleConsumer(endPoint.host, endPoint.port, 10000, 100000, "ConsumerOffsetChecker"))
        .orElse(throw new BrokerNotAvailableException("Broker id %d does not exist".format(bid)))
    } catch {
      case t: Throwable =>
        println("Could not parse broker info due to ")
        t.printStackTrace
        None
    }
  }

  private def processPartition(zkUtils: ZkUtils,
                               group: String, topic: String, pid: Int):LoadInfo ={
    val topicPartition = TopicAndPartition(topic, pid)
    val offsetOpt = offsetMap.get(topicPartition)
    val groupDirs = new ZKGroupTopicDirs(group, topic)
    val owner = zkUtils.readDataMaybeNull(groupDirs.consumerOwnerDir + "/%s".format(pid))._1
    var loadInfo:LoadInfo=new LoadInfo(group,topic,pid,-2,-2,-2,"none")
    zkUtils.getLeaderForPartition(topic, pid) match {
      case Some(bid) =>
        val consumerOpt = consumerMap.getOrElseUpdate(bid, createConsumer(zkUtils, bid))
        consumerOpt match {
          case Some(consumer) =>
            val topicAndPartition = TopicAndPartition(topic, pid)
            val request =
              OffsetRequest(immutable.Map(topicAndPartition -> PartitionOffsetRequestInfo(OffsetRequest.LatestTime, 1)))
            val logSize = consumer.getOffsetsBefore(request).partitionErrorAndOffsets(topicAndPartition).offsets.head

            val lagString = offsetOpt.map(o => if (o == -1) -1 else (logSize - o))
            // println("%-15s %-30s %-3s %-15s %-15s %-15s %s".format(group, topic, pid, offsetOpt.getOrElse("unknown"), logSize, lagString.getOrElse("unknown"),
            //                                                      owner match {case Some(ownerStr) => ownerStr case None => "none"}))
            loadInfo=new LoadInfo(group,topic,pid,offsetOpt.getOrElse(-2).asInstanceOf[Long],logSize,lagString.getOrElse(-2).asInstanceOf[Long],owner match {case Some(ownerStr) => ownerStr case None => "none"})
           case None => // ignore
        }
      case None =>
        println("No broker for partition %s - %s".format(topic, pid))
      }
      loadInfo
  }

  private def processTopic(zkUtils: ZkUtils, group: String, topic: String): List[LoadInfo] ={
    var loadinfolist = new ListBuffer[LoadInfo]()
    topicPidMap.get(topic) match {
      case Some(pids) =>
        pids.sorted.foreach {
          //pid => println(processPartition(zkUtils, group, topic, pid))
          pid => loadinfolist +=processPartition(zkUtils, group, topic, pid)
        }
      case None => // ignore
    }
    loadinfolist.toList
  }

  def getLoadMetricsfForGroup(group:String,zkutils: ZkUtils):List[LoadInfo]={
    val groupDirs = new ZKGroupDirs(group)
    val channelSocketTimeoutMsOpt=6000
    val channelRetryBackoffMsOpt=3000
    val channelSocketTimeoutMs = 6000
    val channelRetryBackoffMs = 3000

    val topics = None
    var loadinfolist = new ListBuffer[LoadInfo]()
    var zkUtils: ZkUtils = zkutils
    var channel: BlockingChannel = null
    try {
      val topicList = zkUtils.getChildren(groupDirs.consumerGroupDir +  "/owners").toList
      topicPidMap = immutable.Map(zkUtils.getPartitionsForTopics(topicList).toSeq:_*)
      val topicPartitions = topicPidMap.flatMap { case(topic, partitionSeq) => partitionSeq.map(TopicAndPartition(topic, _)) }.toSeq
      val channel = ClientUtils.channelToOffsetManager(group, zkUtils, channelSocketTimeoutMs, channelRetryBackoffMs)

      debug("Sending offset fetch request to coordinator %s:%d.".format(channel.host, channel.port))
      channel.send(OffsetFetchRequest(group, topicPartitions))
      val offsetFetchResponse = OffsetFetchResponse.readFrom(channel.receive().payload())
      debug("Received offset fetch response %s.".format(offsetFetchResponse))

      offsetFetchResponse.requestInfo.foreach { case (topicAndPartition, offsetAndMetadata) =>
        if (offsetAndMetadata == OffsetMetadataAndError.NoOffset) {
          val topicDirs = new ZKGroupTopicDirs(group, topicAndPartition.topic)
          try {
            val offset = zkUtils.readData(topicDirs.consumerOffsetDir + "/%d".format(topicAndPartition.partition))._1.toLong
            offsetMap.put(topicAndPartition, offset)
          } catch {
            case z: ZkNoNodeException =>
              if(zkUtils.pathExists(topicDirs.consumerOffsetDir))
                offsetMap.put(topicAndPartition,-1)
              else
                /*println("error")
                throw z*/
                 offsetMap.put(topicAndPartition,-2)
            case x:Exception =>
              println("x error")
          }
        }
        else if (offsetAndMetadata.error == Errors.NONE.code)
          offsetMap.put(topicAndPartition, offsetAndMetadata.offset)
        else {
          println("Could not fetch offset for %s due to %s.".format(topicAndPartition, Errors.forCode(offsetAndMetadata.error).exception))
        }
      }
      channel.disconnect()

      //println("%-15s %-30s %-3s %-15s %-15s %-15s %s".format("Group", "Topic", "Pid", "Offset", "logSize", "Lag", "Owner"))
      topicList.sorted.foreach {
        topic => loadinfolist ++= processTopic(zkUtils, group, topic)
      }

      for ((_, consumerOpt) <- consumerMap)
        consumerOpt match {
          case Some(consumer) => consumer.close()
          case None => // ignore
        }
    }
    catch {
      case t: Throwable =>
        println("Exiting due to: %s.".format(t.getMessage))
    }
    finally {
      for (consumerOpt <- consumerMap.values) {
        consumerOpt match {
          case Some(consumer) => consumer.close()
          case None => // ignore
        }
      }
      /*if (zkUtils != null)
        zkUtils.close()*/

      if (channel != null)
        channel.disconnect()
    }
    loadinfolist.toList
  }
}