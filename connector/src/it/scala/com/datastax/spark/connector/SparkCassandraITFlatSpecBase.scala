package com.datastax.spark.connector

import java.io.{ByteArrayOutputStream, ObjectOutputStream}
import java.util.concurrent.Executors

import com.datastax.dse.driver.api.core.metadata.DseNodeProperties
import com.datastax.oss.driver.api.core.config.DefaultDriverOption.{CONNECTION_MAX_REQUESTS, CONNECTION_POOL_LOCAL_SIZE}
import com.datastax.oss.driver.api.core.cql.{AsyncResultSet, BoundStatement, SimpleStatement, Statement}
import com.datastax.oss.driver.api.core.cql.SimpleStatement._
import com.datastax.oss.driver.api.core.{CqlSession, ProtocolVersion, Version}
import com.datastax.spark.connector.cluster.ClusterProvider
import com.datastax.spark.connector.cql.{CassandraConnector, DefaultAuthConfFactory}
import com.datastax.spark.connector.embedded.SparkTemplate
import com.datastax.spark.connector.testkit.AbstractSpec
import com.datastax.spark.connector.util.Logging
import com.datastax.spark.connector.writer.AsyncExecutor
import org.apache.commons.lang3.StringUtils
import org.apache.commons.lang3.exception.ExceptionUtils
import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession
import org.scalatest._
import org.scalatest.concurrent.Eventually._
import org.scalatest.time.{Seconds, Span}

import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

trait SparkCassandraITFlatSpecBase extends FlatSpec with SparkCassandraITSpecBase {
}

trait SparkCassandraITWordSpecBase extends WordSpec with SparkCassandraITSpecBase {
}

trait SparkCassandraITAbstractSpecBase extends AbstractSpec with SparkCassandraITSpecBase {
}

trait SparkCassandraITSpecBase
  extends TestSuite
  with Matchers
  with BeforeAndAfterAll
  with ClusterProvider
  with Logging
  with Alerting {

  final def defaultConf: SparkConf = {
    SparkTemplate.defaultConf
      .setAll(cluster.connectionParameters)
  }
  final def sparkConf = defaultConf

  lazy val spark = SparkSession.builder().config(sparkConf).getOrCreate().newSession()
  lazy val sparkSession = spark
  lazy val sc = spark.sparkContext

  val originalProps = sys.props.clone()

  private  def isSerializable(e: Throwable): Boolean =
    Try(new ObjectOutputStream(new ByteArrayOutputStream()).writeObject(e)).isSuccess

  // Exceptions thrown by test code are serialized and sent back to test framework main process.
  // Unserializable exceptions break communication between forked test and main test process".
  private def wrapUnserializableExceptions[T](f: => T): T = {
    try {
      f
    } catch {
      case e: Throwable =>
        if (isSerializable(e)) {
          throw e
        } else {
          logError(s"$this failed due to unserializable exception", e)
          throw new java.io.NotSerializableException(s"Unserializable exception was thrown by $this. The exception " +
            s"message was: ${ExceptionUtils.getMessage(e)}, with root cause: ${ExceptionUtils.getRootCauseMessage(e)}." +
            s"Full stack trace should be logged above.")
        }
    }
  }

  final override def beforeAll(): Unit = wrapUnserializableExceptions {
    initHiveMetastore()
    beforeClass
  }

  def beforeClass: Unit = {}

  def afterClass: Unit = {}

  final override def afterAll(): Unit = wrapUnserializableExceptions {
    afterClass
    restoreSystemProps()
  }

  override def withFixture(test: NoArgTest): Outcome = wrapUnserializableExceptions {
    super.withFixture(test)
  }

  def getKsName = {
    val className = this.getClass.getSimpleName
    val suffix = StringUtils.splitByCharacterTypeCamelCase(className.filter(_.isLetterOrDigit)).mkString("_")
    s"test_$suffix".toLowerCase()
  }

  def conn: CassandraConnector = ???

  lazy val executor = getExecutor(CassandraConnector(sc.getConf).openSession)

  def getExecutor(session: CqlSession): AsyncExecutor[Statement[_], AsyncResultSet] = {
    val profile = session.getContext.getConfig.getDefaultProfile
    val maxConcurrent = profile.getInt(CONNECTION_POOL_LOCAL_SIZE) * profile.getInt(CONNECTION_MAX_REQUESTS)
    new AsyncExecutor[Statement[_], AsyncResultSet](
      stmt => stmt match {
        //Handling Types
        case bs: BoundStatement => session.executeAsync(bs.setIdempotent(true))
        case ss: SimpleStatement => session.executeAsync(ss.setIdempotent(true))
        case unknown => throw new IllegalArgumentException(
          s"""Extend SparkCassandraITFlatSpecBase to utilize statement type,
             | currently does not support ${unknown.getClass}""".stripMargin)
      },
      maxConcurrent,
      None,
      None
    )
  }

  def initHiveMetastore() {
    /**
      * Creates CassandraHiveMetastore
      */
    //For Auth Clusters we have to wait for the default User before a connection will work
    if (sparkConf.contains(DefaultAuthConfFactory.PasswordParam.name)) {
      eventually(timeout(Span(60, Seconds))) {
        CassandraConnector(sparkConf).withSessionDo(session => assert(session != null))
      }
    }
    val conn = CassandraConnector(sparkConf)
    conn.withSessionDo { session =>
      val executor = getExecutor(session)
      session.execute(
        """
          |CREATE KEYSPACE IF NOT EXISTS "HiveMetaStore" WITH REPLICATION =
          |{ 'class' : 'SimpleStrategy', 'replication_factor' : 1 }; """
          .stripMargin)
      session.execute(
        """CREATE TABLE IF NOT EXISTS "HiveMetaStore"."sparkmetastore"
          |(key text,
          |entity text,
          |value blob,
          |PRIMARY KEY (key, entity))""".stripMargin)
    }
  }

  def pv: ProtocolVersion = conn.withSessionDo(_.getContext.getProtocolVersion)

  def report(message: String): Unit = alert(message)

  val ks = getKsName

  def skipIfProtocolVersionGTE(protocolVersion: ProtocolVersion)(f: => Unit): Unit = {
    if (!(pv.getCode >= protocolVersion.getCode)) f
    else report(s"Skipped Because ProtocolVersion $pv >= $protocolVersion")
  }

  def skipIfProtocolVersionLT(protocolVersion: ProtocolVersion)(f: => Unit): Unit = {
    if (!(pv.getCode < protocolVersion.getCode)) f
    else report(s"Skipped Because ProtocolVersion $pv < $protocolVersion")
  }

  /** Skips the given test if the Cluster Version is lower or equal to the given `cassandra` Version or `dse` Version
    * (if this is a DSE cluster) */
  def from(cassandra: Version, dse: Version)(f: => Unit): Unit = {
    if (isDse(conn)) {
      from(dse)(f)
    } else {
      from(cassandra)(f)
    }
  }

  /** Skips the given test if the Cluster Version is lower or equal to the given version */
  def from(version: Version)(f: => Unit): Unit = {
    skip(cluster.getCassandraVersion, version) { f }
  }

  /** Skips the given test if the cluster is not DSE */
  def dseOnly(f: => Unit): Unit = {
    if (isDse(conn)) f
    else report(s"Skipped because not DSE")
  }

  /** Skips the given test if the Cluster Version is lower or equal to the given version or the cluster is not DSE */
  def dseFrom(version: Version)(f: => Any): Unit = {
    dseOnly {
      skip(cluster.getDseVersion.get, version) { f }
    }
  }

  private def skip(clusterVersion: Version, minVersion: Version)(f: => Unit): Unit = {
    val verOrd = implicitly[Ordering[Version]]
    import verOrd._
    if (clusterVersion >= minVersion) f
    else report(s"Skipped because cluster Version ${cluster.getCassandraVersion} < $minVersion")
  }

  private def isDse(connector: CassandraConnector): Boolean = {
    val firstNodeExtras = connector.withSessionDo(_.getMetadata.getNodes.values().asScala.head.getExtras)
    firstNodeExtras.containsKey(DseNodeProperties.DSE_VERSION)
  }

  implicit val ec = SparkCassandraITSpecBase.ec

  def await[T](unit: Future[T]): T = {
    Await.result(unit, Duration.Inf)
  }

  def awaitAll[T](units: Future[T]*): Seq[T] = {
    Await.result(Future.sequence(units), Duration.Inf)
  }

  def awaitAll[T](units: TraversableOnce[Future[T]]): TraversableOnce[T] = {
    Await.result(Future.sequence(units), Duration.Inf)
  }

  def keyspaceCql(name: String = ks) =
    s"""
       |CREATE KEYSPACE IF NOT EXISTS $name
       |WITH REPLICATION = { 'class': 'SimpleStrategy', 'replication_factor': 1 }
       |AND durable_writes = false
       |""".stripMargin

  def createKeyspace(session: CqlSession, name: String = ks): Unit = {
    val ks_ex = getExecutor(session)
    ks_ex.execute(newInstance(s"DROP KEYSPACE IF EXISTS $name"))
    ks_ex.execute(newInstance(keyspaceCql(name)))
  }

  /**
    * Ensures that the tables exist in the metadata object for this session. This can be
    * an issue with some schema debouncing.
    */
  def awaitTables(tableNames: String*): Unit = {
    eventually(timeout(Span(2, Seconds))) {
      conn.withSessionDo(session =>
        session
          .getMetadata
          .getKeyspace(ks).get()
          .getTables().keySet()
          .containsAll(tableNames.asJava)
      )
    }
  }


  def restoreSystemProps(): Unit = {
    sys.props ++= originalProps
    sys.props --= (sys.props.keySet -- originalProps.keySet)
  }

}

object SparkCassandraITSpecBase {
  val executor = Executors.newFixedThreadPool(100)
  val ec = ExecutionContext.fromExecutor(executor)
}
